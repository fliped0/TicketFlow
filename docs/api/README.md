# TicketFlow 接口文档

版本 0.6.0，基地址 `http://localhost:8080`。已实现用户认证、活动目录及可选Redis缓存/下单限流、订单生命周期、本人订单查询、管理员订单查询与统计和健康检查，共 27 个 HTTP 操作。支付和退款为本地模拟。

[OpenAPI 3.1 JSON](openapi.json) 可导入 Apifox / Postman。当前不提供在线 Swagger UI。

## 1. 通用协议

JSON 请求及响应，ID 使用十进制字符串。业务响应包含 code、message、data、traceId、replayed；交易同键重放为 true，首次处理与查询为 false。响应头 X-Trace-Id 与本次响应 traceId 一致，健康检查使用 Actuator 原生响应。

账号为 4—32 位 ASCII 字母、数字或下划线，注册和登录均转为小写。密码为 8—64 个 Unicode 码点，不删除空格、不截断。请求包含未知字段（如 role）返回 400。

JWT 使用 RS256，有效期 1800 秒，验证签名、issuer、audience、exp、nbf，容差 30 秒。受保护接口携带 `Authorization: Bearer <accessToken>`。每次请求检查账号启用状态和当前角色。不提供刷新令牌、注销或修改密码接口。目录管理写入要求 ADMIN；时间输入为带时区的 ISO 8601 字符串，输出统一为 UTC。分页从 1 开始，默认 20，最多 100。

## 2. 已实现接口

| 方法 | 路径 | 认证 | 成功结果 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/register | 无 | 201，userId、username、role=USER |
| POST | /api/v1/auth/login | 无 | 200，accessToken、tokenType、expiresIn |
| GET | /api/v1/users/me | Bearer | 200，userId、username、role |
| POST | /api/v1/orders | Bearer + Idempotency-Key | 201，TradeResult；一单一张 |
| GET | /api/v1/orders | Bearer | 200，本人订单分页，支持 status、page、size |
| GET | /api/v1/orders/{orderId} | Bearer | 200，本人订单详情及成交快照 |
| POST | /api/v1/orders/{orderId}/cancel | Bearer + Idempotency-Key | 200，取消或已关闭结果 |
| POST | /api/v1/orders/{orderId}/payments | Bearer + Idempotency-Key | 200，模拟全额支付及 paymentId |
| POST | /api/v1/orders/{orderId}/refunds | Bearer + Idempotency-Key | 200，模拟全额退款及 refundId |
| GET | /actuator/health | 无 | 200，status=UP，无组件详情 |
| GET | /api/v1/events、/api/v1/events/{id} | 无 | 200，仅上架活动；不可见对象 404 |
| GET | /api/v1/events/{id}/sessions、/api/v1/sessions/{id}/tiers | 无 | 200，仅上架活动的子资源 |
| POST/PUT | /api/v1/admin/events、/api/v1/admin/events/{id}、/api/v1/admin/events/{id}/status | ADMIN | 创建、更新和上下架活动 |
| POST/PUT | /api/v1/admin/events/{id}/sessions、/api/v1/admin/sessions/{id} | ADMIN | 创建及更新时间配置 |
| POST/PUT | /api/v1/admin/sessions/{id}/tiers、/api/v1/admin/tiers/{id} | ADMIN | 创建及更新票档和容量 |
| GET | /api/v1/admin/events、/api/v1/admin/events/{id}、/api/v1/admin/events/{id}/sessions、/api/v1/admin/sessions/{id}/tiers | ADMIN | 草稿与下架可见的后台查询 |
| GET | /api/v1/admin/orders | ADMIN | 全量订单分页，附 userId、支付及退款记录 |
| GET | /api/v1/admin/statistics | ADMIN | 期间订单数、支付额、退款额和交易净额 |

### 2.1 注册

```http
POST /api/v1/auth/register
Content-Type: application/json

{"username":"example_user","password":"ExampleOnly_123"}
```

```json
{
  "code": "OK",
  "message": "成功",
  "data": {"userId": "1", "username": "example_user", "role": "USER"},
  "traceId": "00000000-0000-4000-8000-000000000000",
  "replayed": false
}
```

示例账号不会预置到数据库。重复账号（包括仅大小写不同）返回 409 / USERNAME_EXISTS。

### 2.2 登录

请求体与注册相同。成功响应 data：

```json
{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":1800}
```

账号不存在、密码错误和禁用账号统一返回 401 / BAD_CREDENTIALS。

### 2.3 当前用户

```http
GET /api/v1/users/me
Authorization: Bearer <JWT>
```

成功 data 与注册一致。JWT 无效、缺少令牌或账号已禁用返回 401 / UNAUTHENTICATED。

### 2.4 目录管理与查询

管理员先创建活动，再创建至少一个场次及每场次至少一个票档，最后将活动设为 `ON_SALE`。创建票档时同步建立库存：`available=capacity`、`reserved=sold=0`。上架活动不能新增场次；先下架、补齐场次和票档，再重新上架。

```http
POST /api/v1/admin/events/{eventId}/sessions
Authorization: Bearer <ADMIN_JWT>
Content-Type: application/json

{"startsAt":"2026-12-01T12:00:00+08:00","saleStartAt":"2026-11-01T10:00:00+08:00","saleEndAt":"2026-11-30T10:00:00+08:00"}
```

更新请求需携带当前 `expectedVersion`；成功更新版本加一。重复提交已处于目标状态的上下架请求返回当前对象，不增加版本。`freeze_at` 初始化为开售时间，修改开售时间时只可能提前；达到冻结时间后，场次时间、票档名称、价格、容量不能修改，也不能追加票档。任一场次冻结后，活动城市和场地不能修改。

公开活动列表支持 `keyword`、`city`、`category` 组合筛选，关键词中的 `%`、`_` 按字面匹配。列表按活动 ID 降序，子资源按 ID 升序，结果为 `{items,page,size,total}`。后台活动列表支持 `status`、`keyword`。场次与票档的 `saleStatus` 按 `NOT_ON_SALE`、`SALE_NOT_STARTED`、`SALE_ENDED`、`SOLD_OUT`、`ON_SALE` 的优先级计算；场次以全部票档可售量判断是否售罄。

### 2.5 幂等下单与本人订单

```http
POST /api/v1/orders
Authorization: Bearer <JWT>
Idempotency-Key: create_20261001_001
Content-Type: application/json

{"tierId":"101","quantity":1}
```

成功返回 HTTP 201：

```json
{"code":"OK","message":"成功","data":{"orderId":"201","operationStatus":"PENDING","currentOrderStatus":"PENDING","amountFen":58000,"expiresAt":"2026-10-01T02:15:00Z"},"traceId":"00000000-0000-4000-8000-000000000000","replayed":false}
```

金额、名称、地点和开场时间均由服务端决定。有效订单占用同用户同场次的唯一购买资格，跨票档也限购；当前有效状态为 PENDING / PAID。创建时间采用库存锁取得后的数据库 UTC 时间，支付期限为该时间加 15 分钟与开场时间的较早值。下架与下单通过活动锁串行判定。

请求键格式 `[A-Za-z0-9_-]{16,64}`，区分大小写，作用域为用户与操作。相同键、相同参数重放原业务结果及 HTTP 状态（成功仍为 201），`replayed=true`，traceId 使用本次请求编号；同键异参返回 409 / IDEMPOTENCY_CONFLICT。已知业务拒绝同样保存并重放，即使库存或销售状态后来变化也不重新下单。格式/认证错误不写请求记录。请求记录不自动清理。

订单、资格、占用流水与成功请求共同提交。已知拒绝回滚业务保存点后提交 REJECTED；数据库与系统异常整笔回滚，不记录虚假的拒绝。遇到 503、500 或响应丢失时，保留原键重试或查询确认结果。503 包含 `data.retryWithSameKey=true`。可恢复锁错误仅在事务外最多重试两次，使用原键及剩余的 5 秒请求预算；客户端不能把 HTTP 失败视为订单一定不存在。

`GET /api/v1/orders?status=PENDING&page=1&size=20` 按订单 ID 降序返回 `{items,page,size,total}`。status 可取 PENDING、PAID、CANCELLED、CLOSED、REFUNDED；page 从 1 开始，size 为 1—100，默认 20。详情及列表元素包含 orderId、status、quantity、unitPriceFen、amountFen、snapshot、createdAt、expiresAt、payment、refund。snapshot 固定保存 schemaVersion=1、活动/场次/票档 ID 与名称、城市、地点、开场时间、金额、数量和退款政策；下架或修改目录文案不会改写成交快照。列表 payment/refund 为 null；详情在同一只读一致性快照中返回支付和退款记录，尚未发生则为 null。

查询无需幂等头；仅返回本人订单，他人订单与不存在均为 404 / NOT_FOUND。

### 2.6 取消、模拟支付与模拟退款

三项请求均要求 Bearer、合法 `Idempotency-Key` 及空对象 `{}`。不接受金额、成功结果、身份或状态字段；空请求体、null、数组及非空对象返回 400，且不写请求记录。

```http
POST /api/v1/orders/201/payments
Authorization: Bearer <JWT>
Idempotency-Key: payment_20261003_001
Content-Type: application/json

{}
```

模拟支付成功 data 示例：

```json
{"orderId":"201","operationStatus":"PAID","currentOrderStatus":"PAID","amountFen":58000,"expiresAt":"2026-10-03T02:15:00Z","paymentId":"301","refundId":null}
```

取消路径为 `/orders/{orderId}/cancel`。PENDING 且未到期转 CANCELLED，到期后转 CLOSED；释放占用与限购资格。对 CANCELLED / CLOSED 使用新键返回既有状态；PAID / REFUNDED 拒绝取消。旧键仍保留原操作结果，不重新迁移。

模拟支付路径为 `/orders/{orderId}/payments`。在订单、资格、库存锁齐备后读取数据库时间，必须严格早于 expiresAt；成功将 reserved 转 sold，保留限购资格，并记录唯一成功支付及 PAY 流水。成功历史支付可用新键返回，退款后也返回历史 paymentId、operationStatus=PAID、currentOrderStatus=REFUNDED。到期支付返回 ORDER_EXPIRED，由关单回收占用。

模拟退款路径为 `/orders/{orderId}/refunds`。首次要求 PAID 且锁后时间严格早于成交快照的 startsAt；从订单和支付记录取得全额金额，归还 sold 并释放资格，记录唯一退款及 REFUND 流水。重复退款返回原 refundId，不重复归还。退款后的新购买仍需符合当前上下架、销售时间和库存规则。

模拟失败只在隔离测试上下文替换 PaymentSimulator。支付失败为 422 / PAYMENT_SIMULATED_FAILURE，保持 PENDING；退款失败为 422 / REFUND_SIMULATED_FAILURE，保持 PAID。原键始终重放该失败；在业务窗口内使用新键才会重新尝试。这里没有外部扣款或退款调用。

交易结果统一增加可空 paymentId、refundId；CREATE / CANCEL 为 null，PAY 返回 paymentId，REFUND 返回 paymentId 与 refundId。重放保持原 ID 与 operationStatus，同时更新 currentOrderStatus；批次 3 已保存的无新增字段结果仍可重放。操作键按 CREATE / PAY / CANCEL / REFUND 隔离，同操作同键换订单返回 IDEMPOTENCY_CONFLICT。

订单详情 payment 为 `{paymentId,amountFen,paidAt}`，refund 为 `{refundId,amountFen,refundedAt}`，ID 为字符串，时间为 UTC。

### 2.7 自动到期关闭

无公开系统关单接口。默认启动立即补扫，随后每次扫描完成后间隔 10 秒再扫描；每轮固定数据库 cutoff，按 `(expiresAt,id)` 升序，每批最多 100 条，10 秒处理预算。候选逐单独立事务；失败记录 orderId、traceId 和异常类型，继续后续候选，下轮从最早过期项重新扫描。不会跨轮保存游标，同实例扫描互斥。

关单先定位所属用户，再按用户、订单、资格、库存顺序锁定，重新取时，只将到期 PENDING 转 CLOSED。账号禁用不妨碍回收；支付、取消或其他关单已先完成时跳过。状态、RELEASE 流水、库存及资格共同提交。测试可设置 `ticketflow.expiry.enabled=false` 隔离自动调度；正常运行默认启用。

### 2.8 管理员订单与统计

`GET /api/v1/admin/orders` 支持 orderId、sessionId、status、from、to、page、size。ID 为正 BIGINT 十进制字符串；status 沿用五种订单状态。按订单 ID 降序，返回 `{items,page,size,total}`；每项为 OrderDetail 增加 userId，且包含完整支付、退款记录。from/to 可分别提供，按创建时间下界包含、上界排除；同时提供须 from < to。订单行、交易记录及总数来自同一只读 REPEATABLE READ 快照。

`GET /api/v1/admin/statistics?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z` 要求两项时间均提供，from < to，区间最长 31 天。时间须带时区，精度不超过微秒，年份为 MySQL DATETIME 支持的 1000—9999；输出归一 UTC。区间为 `[from,to)`，订单数按 created_at、支付额按 paid_at、退款额按 refunded_at 聚合，三项来自同一只读快照。金额单位为分，使用整数。

```json
{"from":"2026-10-01T00:00:00Z","to":"2026-10-02T00:00:00Z","orderCount":1,"paidAmountFen":58000,"refundAmountFen":0,"netAmountFen":58000,"orderTimeBasis":"created_at","paymentTimeBasis":"paid_at","refundTimeBasis":"refunded_at"}
```

netAmountFen 为该期间支付额减退款额，可为负数；不是按订单创建日期归属的销售收入。取消或退款不会删减历史订单数，退款后的成功支付仍计入原支付发生区间。普通用户 403、未登录或禁用账号 401；查询不要求幂等头。

只读对账为运维命令，不新增 HTTP 接口，使用方法见[部署与运维](../05_部署与运维.md)。

## 3. 错误码

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 参数或未知 JSON 字段不合法 |
| 401 | BAD_CREDENTIALS | 登录失败 |
| 401 | UNAUTHENTICATED | 受保护接口认证失败 |
| 403 | FORBIDDEN | 普通用户不能访问管理员路径 |
| 404 | NOT_FOUND | 票档不存在、订单不存在或非本人 |
| 409 | USERNAME_EXISTS | 账号已存在 |
| 409 | VERSION_CONFLICT / CONFIG_FROZEN | 管理资源版本过期或关键配置已冻结 |
| 409 | INCOMPLETE_CATALOG / TIER_NAME_EXISTS | 发布缺少完整场次、票档或票档名称重复 |
| 409 | IDEMPOTENCY_CONFLICT | 同键参数不同 |
| 409 | PURCHASE_LIMIT / SOLD_OUT | 同场次已有有效订单或票档售罄 |
| 409 | NOT_ON_SALE / SALE_NOT_STARTED / SALE_ENDED | 活动下架、未开售或已停售 |
| 409 | ORDER_STATE_CONFLICT / ORDER_EXPIRED / REFUND_CLOSED | 状态不允许、支付到期或已到开场退款截止点 |
| 422 | PAYMENT_SIMULATED_FAILURE / REFUND_SIMULATED_FAILURE | 受控模拟失败；原键保留失败，新键可在窗口内重试 |
| 503 | TEMPORARILY_UNAVAILABLE | 数据访问暂不可用 |
| 429 | TOO_MANY_REQUESTS | 启用Redis后用户或场次新请求额度耗尽；Retry-After为至少等待的秒数，原键未落库，可等待后原键重试 |
| 503 | CATALOG_BUSY | 启用缓存时目录查询并发额度或热点等待耗尽 |
| 500 | INTERNAL_ERROR | 内部错误 |

错误响应不返回 SQL、堆栈、密码哈希或他人资料。

## 4. 更新规则

每个模块完成时同步修改代码、OpenAPI、示例和测试报告，通过测试后提交并推送。未实现接口不在本规范中承诺可调用。

## 5. 实现结构记录

2026-09-29 调整为统一 controller/service/mapper/model 分层，请求对象为 CredentialsDTO，返回对象为 UserVO、TokenVO。URL、JSON 字段及成功响应状态保持 0.1.0 契约；当前用户查询由 Service 统一校验账号状态。

2026-10-01 批次 3 使用 TradeController → OrderApplicationService / TradeExecutor → JdbcTemplate Mapper。交易使用单个事务管理器与 READ COMMITTED，协作者不另开事务，保存点由 TransactionStatus 控制。未修改既有 Flyway V1。

2026-10-03 批次 4 扩展既有应用服务与执行器，新增 PaymentMapper、PaymentSimulator、OrderExpiryJob。系统内部事务不要求请求键和 enabled，但仍要求所属用户存在并取得锁。订单详情使用只读 REPEATABLE READ，避免将不同瞬间的状态和支付/退款记录混在一个响应中。既有 Flyway V1 未修改。

2026-10-04 批次 5 新增 AdminOrderController → AdminOrderService → AdminOrderMapper，采用 JdbcTemplate 与只读 REPEATABLE READ。TraceFilter 记录 traceId、方法、路径、HTTP 状态、结果分类和耗时；不记录请求体、查询参数、密码或令牌。既有 Flyway V1 不变。

## V2设计状态

批次6的异步提交/结果/模式配置仅在[草案](v2-draft.md)中描述，尚未实现；本已实现契约与OpenAPI为0.6.0、27个HTTP操作。

## 批次7缓存与限流边界

通过TF_REDIS_ENABLED启用，默认关闭；公开目录缓存不用于交易校验。页码/大小/关键词/城市/分类参与键，管理员查询不缓存。库存与saleStatus每次读取数据库或数据库时间重新计算。管理写事务提交后推进持久目录版本，后续请求只读新版本键；在提交前开始的查询可返回自己的旧快照。

限流只用于POST /api/v1/orders的新尝试；已落库原键优先重放，异参仍409。短窗口内同键正在执行的尝试不重复计数，业务执行仍在数据库内按原协议串行判定。订单查询、支付/取消/退款不共用抢票额度。Redis故障时同步交易继续走原DB锁/资格/库存链路；目录在有界并发额度内回源。未来ASYNC新受理必须暂停，不得照搬同步的故障放行策略。
