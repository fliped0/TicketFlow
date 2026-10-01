# TicketFlow 接口文档

版本 0.3.0，基地址 `http://localhost:8080`。已实现用户认证、活动目录、幂等下单、场次限购、库存占用、本人订单查询和健康检查。支付、取消、关单和退款留到批次 4。

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
| GET | /actuator/health | 无 | 200，status=UP，无组件详情 |
| GET | /api/v1/events、/api/v1/events/{id} | 无 | 200，仅上架活动；不可见对象 404 |
| GET | /api/v1/events/{id}/sessions、/api/v1/sessions/{id}/tiers | 无 | 200，仅上架活动的子资源 |
| POST/PUT | /api/v1/admin/events、/api/v1/admin/events/{id}、/api/v1/admin/events/{id}/status | ADMIN | 创建、更新和上下架活动 |
| POST/PUT | /api/v1/admin/events/{id}/sessions、/api/v1/admin/sessions/{id} | ADMIN | 创建及更新时间配置 |
| POST/PUT | /api/v1/admin/sessions/{id}/tiers、/api/v1/admin/tiers/{id} | ADMIN | 创建及更新票档和容量 |
| GET | /api/v1/admin/events、/api/v1/admin/events/{id}、/api/v1/admin/events/{id}/sessions、/api/v1/admin/sessions/{id}/tiers | ADMIN | 草稿与下架可见的后台查询 |

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

`GET /api/v1/orders?status=PENDING&page=1&size=20` 按订单 ID 降序返回 `{items,page,size,total}`。status 可取 PENDING、PAID、CANCELLED、CLOSED、REFUNDED；page 从 1 开始，size 为 1—100，默认 20。详情及列表元素包含 orderId、status、quantity、unitPriceFen、amountFen、snapshot、createdAt、expiresAt、payment、refund。snapshot 固定保存 schemaVersion=1、活动/场次/票档 ID 与名称、城市、地点、开场时间、金额、数量和退款政策；下架或修改目录文案不会改写成交快照。本批 payment/refund 为 null。

查询无需幂等头；仅返回本人订单，他人订单与不存在均为 404 / NOT_FOUND。支付、取消、关单和退款入口尚未实现，到期库存释放随批次 4 交付。

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
| 503 | TEMPORARILY_UNAVAILABLE | 数据访问暂不可用 |
| 500 | INTERNAL_ERROR | 内部错误 |

错误响应不返回 SQL、堆栈、密码哈希或他人资料。

## 4. 更新规则

每个模块完成时同步修改代码、OpenAPI、示例和测试报告，通过测试后提交并推送。未实现接口不在本规范中承诺可调用。

## 5. 实现结构记录

2026-09-29 调整为统一 controller/service/mapper/model 分层，请求对象为 CredentialsDTO，返回对象为 UserVO、TokenVO。URL、JSON 字段及成功响应状态保持 0.1.0 契约；当前用户查询由 Service 统一校验账号状态。

2026-10-01 批次 3 使用 TradeController → OrderApplicationService / TradeExecutor → JdbcTemplate Mapper。交易使用单个事务管理器与 READ COMMITTED，协作者不另开事务，保存点由 TransactionStatus 控制。未修改既有 Flyway V1。
