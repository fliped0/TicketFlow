# 批次 4：订单生命周期测试报告

- 执行批次：20261003-lifecycle；日期：2026-10-03（Asia/Shanghai）。
- 环境：Windows、Java 17、Maven 3.9.12、Spring Boot 4.1.1、MySQL 8.0.45。
- 测试库：ticketflow_test / tf_test；应用和 Flyway 前校验身份，使用随机账号和独立场次，未重置数据库。
- 入口：加载 scripts/common.ps1 的 test 配置，执行 `Invoke-TicketFlowMaven -Goals @('clean','verify')`。
- 交付结果：BUILD SUCCESS；13 项单元测试 + 48 项真实 MySQL / HTTP 集成测试，共 61 项，0 失败、0 错误、0 跳过。
- 完成时间：2026-10-03 11:09:57 +08:00；交付 clean verify 耗时 1 分 45 秒。
- 实际构建结果及时间：[构建摘要](verification.txt)、[测试类结果](test-results.txt)、[运行环境](runtime.txt)。
- 源码依据：[SHA-256 清单](source-manifest.sha256)，文本按 LF 规范化；交付 Git 提交由包含本报告的提交确定。
- 接口验证：[OpenAPI 校验记录](openapi-validation.txt)，0.4.0，共 25 个已实现 HTTP 操作。

| 测试类 | 测试方法 | 结果 |
| --- | ---: | --- |
| AdminInitializerTest | 4 | 通过 |
| AccountRulesTest | 2 | 通过 |
| IdentityServiceTest | 3 | 通过 |
| OrderPolicyTest | 4 | 通过 |
| IdentityIntegrationIT | 18 | 通过 |
| OrderIntegrationIT | 15 | 通过 |
| OrderLifecycleIT | 15 | 通过 |

## 新增验证及观察

| 用例 / 测试 | 实际观察 |
| --- | --- |
| TC-CANCEL-001 / cancellationRepeatsAndReleasesForAnotherPurchaseIncludingExpiredCancellation | 20 次同键取消及新键重复取消只释放一次；CREATE 原键保留 PENDING 操作及当前 CANCELLED；取消后可重新购买；过期取消统一 CLOSED，再取消或系统关单不重复释放 |
| TC-PAY-001、TC-REFUND-001 / repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility | 20 次同键支付、20 次同键退款及新键重复操作保留唯一 ID；退款后 PAY 原键/新键均返回历史支付及当前 REFUNDED；详情返回全额支付/退款；退款后资格释放可再次购买；批次 3 无新增字段的历史请求仍可反序列化重放 |
| TC-PAY-002、TC-REFUND-002 / simulatedFailureIsImmutableForOriginalKeyAndNewKeyCanRetry | test bean 注入模拟失败：支付为 422/PAYMENT_SIMULATED_FAILURE 且保持 PENDING，退款为 422/REFUND_SIMULATED_FAILURE 且保持 PAID；恢复模拟器后原键仍重放失败，新键成功 |
| TC-SEC-001、TC-API-002 / unauthorizedOperationsAndNonemptyBodiesHaveNoSideEffects | 他人订单和不存在均 404；未登录 401；未知金额、成功、状态字段及数组拒绝，缺少请求键拒绝，400 不写请求记录；本人原订单和库存不变 |
| TC-STATE-001 / lifecycleStateMatrixAndIdempotencyScopeAreEnforced | 五状态×支付/取消/退款的 15 个新请求组合与矩阵一致，逐项核对关单跳过；同操作同键换订单冲突，PAY/REFUND 操作键空间独立 |
| TC-ORDER-003 / paymentAndCancellationCompetitionHonorsBothLockOrders | 测试钩子确认首方持用户/订单锁，再由真实 HTTP 对手等待；支付先获锁则 PAID，取消冲突；取消先获锁则 CANCELLED，支付冲突 |
| TC-ORDER-004 / paymentAndSystemCloseCompetitionHonorsBothLockOrders | 支付先获锁可成交，随后关单跳过；到期系统关单先获锁则 CLOSED，随后支付拒绝；只有一条状态迁移和相应库存变化 |
| TC-TIME-001 / paymentAndRefundWaitForStockThenRecheckTheirDeadlines | 独立连接持库存锁，PROCESSLIST 确认支付/退款 SQL 等待；释放前跨过支付期限/开场时间，随后分别 ORDER_EXPIRED / REFUND_CLOSED；无支付/退款副作用 |
| TC-TX-002 / everyLifecycleWriteFailureRollsBackAndOriginalKeyCanRecover | 取消 5 个、支付 5 个、退款 6 个写入后故障点，共 16 个子场景；订单状态、库存、流水、资格、交易记录和请求结果整笔回滚；移除故障后原键可成功 |
| TC-TX-001 / knownSimulatorRejectionAfterWritesUsesSavepointAndStillCommitsFailure | 在支付记录插入后注入已知拒绝，保存点撤销状态、库存、流水及支付；REJECTED 仍提交；新键可支付 |
| TC-SEC-003、TC-STOCK-003 / disabledOwnerAndDuplicateSystemClosesReleaseOnlyOnceAndFailuresRollback | 禁用用户不阻碍内部关单；资格删除后系统异常全回滚；两条独立内部关单事务竞争，只一条成功，RELEASE 唯一；不新增客户端请求 |
| TC-RECOVERY-003 / scanPagesMoreThanHundredOrdersContinuesAfterOneFailureAndRetriesNextRound | 105 个独立场次的过期订单跨批扫描；首单持续注入失败仍处理其余 104 单；恢复后下一轮回收失败订单，再扫描无重复释放 |
| TC-ORDER-004、TC-RECOVERY-003 / applicationRestartRunsStartupRecoveryAndSchedulingClosesLaterExpiries | 正常关闭一个完整 Spring 应用上下文及其服务器/连接池，停机阶段使订单到期并禁用用户；重新启动独立上下文，ApplicationReady 补扫将其 CLOSED；再创建订单到期后由真实 10 秒调度关闭 |
| TC-RECOVERY-003 / slowRoundStopsAtBudgetAndDoesNotOverlapOrLoseNextRoundCandidates | 受控单候选延迟 9.2 秒，剩余不足 1 秒退出，10.5 秒内收敛；同时扫描不另开轮次；下一轮仍找回并关闭保留候选 |
| TC-TX-002 / inconsistentSuccessfulRecordsAreSystemErrorsAndDoNotPersistRejection | 测试库受控移除成功支付/退款记录或篡改支付金额，返回 500，无 REJECTED 或请求残留；夹具 finally 恢复原记录；不以业务状态冲突掩盖损坏 |
| TC-TIME-001 / OrderPolicyTest.paymentAndRefundExcludeTheirMicrosecondDeadline | 支付及退款 T−1μs 允许，T/T+1μs 拒绝；原开售/停售边界与期限上限单测继续通过 |

每个生命周期场景独立查询库存与订单数、限购资格、成功支付/退款金额及流水。核对 capacity=available+reserved+sold，reserved=PENDING 数、sold=PAID 数，资格仅对应 PENDING/PAID，终止订单无资格，成功支付/退款及每类流水唯一。

正常集成上下文禁用自动扫描，以隔离截止时间与故障夹具；扫描实验显式调用真实 job，重启实验启用真实启动事件及调度。故障由 MockitoSpyBean 替换注入，未添加生产模拟失败参数。时间缩短、历史 JSON 和记录损坏准备均明确为测试库夹具，不计为公开接口功能。

## 范围与限制

历史 45 项测试全部保留；新增 1 项微秒边界单测和 15 项生命周期集成测试。原订单测试的共享夹具提取为 OrderTestSupport，原 15 个测试仍执行。历史报告与迁移 V1 未修改。

首轮 59 项、补充轮次预算后的 60 项及最终交付 61 项全量回归均通过。首轮重启实验使用了默认 8080 端口（Spring 默认属性优先级低于 application.yml），之后改用测试命令行配置明确随机端口及临时测试密钥；最终重启实验在独立随机端口运行。未发生产品测试断言失败；一致性负例和故障注入的 500 日志为预期结果。

恢复验证为同一测试 JVM 中两个真实应用上下文的正常关闭和重新启动，确实关闭/重建 HTTP 服务器、连接池、调度及业务组件；未强杀 JVM，未注入真实网络断连或提交未知。扫描预算验证含受控延迟，不是性能 SLA 实验。遗留测试库包含早期人为构造的不一致订单，扫描会报告并跳过，未自动修复或删除。

真实支付、Redis、MQ、管理统计、大规模争票三轮实验、强杀进程恢复及完整性能验收未实施；不宣称完整 V1 验收或生产支付能力。无新增第三方支付副作用。
