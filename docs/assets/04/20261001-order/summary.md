# 批次 3：基础交易测试报告

- 执行批次：20261001-order；日期：2026-10-01（Asia/Shanghai）。
- 环境：Windows、Java 17、Maven 3.9.12、Spring Boot 4.1.1、MySQL 8.0.45。
- 测试库：ticketflow_test / tf_test；应用与 Flyway 启动前验证身份；每个测试使用独立随机用户和目录，未重置数据库。
- 入口：加载 scripts/common.ps1 的 test 配置，执行 `Invoke-TicketFlowMaven -Goals @('clean','verify')`。常规完整回归入口仍为 `scripts/test.ps1`。
- 结果：BUILD SUCCESS；12 项单元测试 + 33 项真实 MySQL / HTTP 集成测试，共 45 项，0 失败、0 错误、0 跳过。
- 最终完成时间：2026-10-01 11:07:31 +08:00；完整 clean verify 耗时 37.574 秒。
- 执行证据：[最终构建记录](verification.txt)、[运行环境](runtime.txt)、[各测试类结果](test-results.txt)。
- 源码依据：[SHA-256 清单](source-manifest.sha256)；文本按 LF 规范化后计算 SHA-256，与 Git 文本基线一致；包含本报告的 Git 提交确定交付版本。

| 测试类 | 方法数 | 结果 |
| --- | ---: | --- |
| AdminInitializerTest | 4 | 通过 |
| AccountRulesTest | 2 | 通过 |
| IdentityServiceTest | 3 | 通过 |
| OrderPolicyTest | 3 | 通过 |
| IdentityIntegrationIT | 18 | 通过 |
| OrderIntegrationIT | 15 | 通过 |

## 覆盖与实际观察

| 用例 / 实现测试 | 实际结果 |
| --- | --- |
| TC-ORDER-001 / createSnapshotsExpiryAndOwnerQueriesSurviveCatalogChanges | HTTP 下单 201；一张票、服务端金额和 13 字段快照正确；开场不足 15 分钟时 expiresAt=startsAt；真实后台改文案、下架后快照不变；本人列表/详情可见，他人及不存在均 404；payment/refund 为 null |
| TC-API-002 / validationAndAuthenticationFailBeforeRequestPersistence | 缺失认证、请求键及非法键、ID、数量、未知金额字段和分页被拒绝；小数/字符串 quantity、数值 tierId 被拒绝；400 不写 tf_request；不存在票档保存 404 REJECTED |
| TC-ORDER-002 / twentyDuplicateRequestsReplayOneOrderAndConflictOnChangedPayload | 20 个并发真实 HTTP 请求同键同参，均 201，1 个新结果、19 个重放；一个订单/资格/占用流水/请求，traceId 各不相同；同键异参 409；另一个用户可使用同键；正常期限恰为 createdAt+900 秒 |
| TC-ORDER-002 / concurrentDifferentKeysAcrossTiersEnforceSessionLimit | 同用户、同场次、两个票档、不同键竞争，只出现一笔成功及一笔 PURCHASE_LIMIT；一份资格；请求均提交终态 |
| TC-STOCK-002 / twoUsersCompeteForLastTicketWithoutOversell | 容量 1 的票档，两用户同时请求：一笔 201、一笔 SOLD_OUT；available=0、reserved=1，只有一份订单、资格及 RESERVE 流水 |
| TC-ORDER-005、007 / rejectedSaleResultsPersistAndReplayAfterConditionsChange | 下架、未开售、已停售及售罄分别保存拒绝；测试直接改变条件后原键仍重放原拒绝，新键成功；拒绝不关联撤销订单 |
| TC-TX-001 / realExecutorSavepointRevertsOrderSlotStockAndLogButCommitsRejection | 在真实库存流水插入后通过 test bean 注入 BusinessRejection；实际执行器撤销订单、资格、占用和流水，库存恢复；REJECTED 与 completed_at 提交，order_id=NULL；连接继续可用，新键成功 |
| TC-TX-002 / systemFailuresAtEveryWriteStageRollbackEntireTransactionAndSameKeyRecovers | 分别在订单、资格、库存、流水、最终请求结果 SQL 执行后抛系统异常，共五个子场景；均 500，全部业务变更及请求撤销；原键恢复后成功 |
| TC-TX-002 / unexpectedSqlConstraintFailureRollsBackAndIsNotBusinessRejection | 重复插入 RESERVE 触发真实 MySQL 唯一约束错误；返回 503 / retryWithSameKey=true，整笔回滚；不伪装 REJECTED；同键恢复后成功 |
| TC-TX-003 / lockFailuresRetryWholeTransactionAtMostTwice | 分别注入 MySQL 1205、1213 锁错误，前两次已写入的禁用状态被整笔撤销，第三次成功；确认 READ-COMMITTED；持续错误最多三次尝试后 503，无请求残留。1213 为受控错误注入，未声称制造真实 MySQL 死锁 |
| TC-TX-003 / realLockTimeoutReturnsRetryableErrorWithoutCommittedProcessing | 独立连接持有用户 X 锁，HTTP 下单等待，真实 MySQL 锁超时/查询预算耗尽后 503；无订单和 PROCESSING 提交；释放锁后原键成功 |
| TC-TIME-001 / saleTimeIsReadAfterWaitingForStockLock | 独立连接持库存 X 锁，以 PROCESSLIST 确认真实交易已到达等待 SQL；释放前分别跨过停售与开售边界：停售后拒绝，开售后成功，created_at 不早于新开售时间 |
| TC-ADMIN-005 / offSaleHoldingEventLockWinsAgainstWaitingCreate | 真实后台下架先持活动 X 锁，下单等待 S 锁；下架提交后下单拒绝 NOT_ON_SALE，无订单/库存残留 |
| TC-ADMIN-005 / createHoldingSharedCatalogLockCommitsBeforeWaitingOffSale | 下单先持共享目录锁，后台下架等待活动 X 锁；下单 201 后下架 200；后来新用户下单拒绝，无半生效状态 |
| TC-SEC-003 / userDisabledWhileWaitingIsRecheckedInsideTransaction | HTTP 认证后等待用户锁；持锁连接禁用账号并提交；交易取锁后重新检查 enabled，返回 401，无请求记录 |
| TC-TIME-001 / OrderPolicyTest | 开售与停售 T−1μs/T/T+1μs；开售包含、停售排除；下架优先；期限取 15 分钟与开场较早值；请求键、BIGINT ID 格式边界 |

共同断言直接查询订单、资格、库存和流水；每个 fixture 校验 `capacity=available+reserved+sold`、reserved 与 PENDING 数一致、资格归属及 RESERVE 唯一。未将服务自己的成功返回作为唯一证据。

故障钩子仅通过 MockitoSpyBean 存在于测试上下文，未添加生产开关或可公开操控的 HTTP 字段。目录和时间测试的直接数据库准备明确为测试夹具，不计为管理员接口成功证据；下架竞争的两方均通过真实 HTTP 入口。

## 证据边界与后续

原有 27 项测试继续通过；新增 3 项规则单测和 15 项交易集成测试。首次完整 verify 和随后的 clean verify 均通过；交付前收紧 JSON 类型检查后再次完整回归。构建记录中的故障日志属于预期注入，不表示断言失败。

OpenAPI 已扩展 3 个订单操作，并修复先前目录接口的空请求体 schema 引用、GET 错误要求请求体的问题；全部本地引用可解析，共 22 个已实现操作。

响应恢复场景是忽略已成功返回的首次响应，再使用原键重放，未进行真实网络代理截断。真实进程崩溃、提交未知故障、1000 用户/100 工作线程/100 张票三轮实验、性能和恢复 SLA 未执行。支付、取消、关单和退款留批次 4，到期释放、释放后重新购买及相关竞争不计为本批通过。

测试方法数量不等于用例组数量；有售后子场景的用例组只报告已实现部分，不据此宣称全部 V1 验收完成。历史报告和源码哈希未覆盖修改。
