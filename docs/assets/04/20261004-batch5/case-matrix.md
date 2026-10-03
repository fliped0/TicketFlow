# 46 组计划用例追踪

本表追踪计划组与实际证据，73 个 JUnit 方法另见 test-results.json。继承首批至批次 4 的历史验证，并补充本轮实际实验；不以方法数计算组通过率。当前环境与业务约定的基线通过，环境清空、全新数据库、生产 HTTPS、跨机部署及所有可能参数组合的穷举均不宣称已执行。

方法名来自 backend/src/test/java/com/ticketflow 下相应套件；完整回归全部通过。数据库约束、保存点与 HTTP 入口均使用真实 MySQL。

| 用例组 | 实际验证 | 可定位证据 | 口径与范围 |
| --- | --- | --- | --- |
| TC-ENV-001 | 构建与独立 jar 启动 | verification.txt；final-package-smoke.json；首批 Wrapper 证据 | 本轮沿用已有依赖环境，未清空个人开发环境 |
| TC-DB-001 | 迁移与重复执行 | IdentityIntegrationIT.migrationCreatesTwelveTablesAndIsRepeatable；AdminReportingIT.unchangedMigrationBuildsAnEmptyTableNamespaceAndDoesNotRepeat | 首批空库证据 + 本轮空表命名空间；未重建数据库 |
| TC-DB-002 | 数据库约束负例 | IdentityIntegrationIT.schemaEnforcesUniquenessForeignKeysAndChecks；AdminReportingIT.databaseRejectsDuplicateTransactionsAndIncorrectCompositeOwnership | 原约束及请求/支付/退款唯一、错误复合归属 |
| TC-API-001 | 26 业务 + 健康入口，契约边界 | http-operation-coverage.json；四个 IT 套件的输入及权限方法 | 27 个成功入口；并非全部可能输入组合的穷举 |
| TC-API-002 | 非法输入与未知字段 | IdentityIntegrationIT.unknownFieldsAndInputBoundsAreRejected；catalogRejectsMalformedFieldsAndKeepsPublicQueriesOpen；OrderIntegrationIT.validationAndAuthenticationFailBeforeRequestPersistence；OrderLifecycleIT.unauthorizedOperationsAndNonemptyBodiesHaveNoSideEffects；AdminReportingIT.managementAuthorizationAndValidationAreEnforced | 无客户端金额、角色及成功注入 |
| TC-USER-001 | 注册登录本人资料与重复账号 | IdentityIntegrationIT.registrationLoginAndMeUseCurrentDatabaseRole；duplicateAccountsAndConcurrentRegistrationAreRejected | 通过 |
| TC-USER-002 | 账号与密码边界 | AccountRulesTest；IdentityIntegrationIT.unknownFieldsAndInputBoundsAreRejected | 码点、空格、规范化与长度边界 |
| TC-SEC-001 | 本人边界及管理权限 | OrderIntegrationIT.createSnapshotsExpiryAndOwnerQueriesSurviveCatalogChanges；OrderLifecycleIT.unauthorizedOperationsAndNonemptyBodiesHaveNoSideEffects；AdminReportingIT.managementAuthorizationAndValidationAreEnforced | 他人查询及操作 404，管理拒绝 |
| TC-SEC-002 | JWT 验签、声明及时间 | IdentityIntegrationIT.jwtEnforcesIssuerAudienceExpiryNotBeforeAndSignature；protectedRoutesRejectMissingInvalidOrInsufficientCredentials | 通过 |
| TC-SEC-003 | 禁用、角色与锁内复查 | OrderIntegrationIT.userDisabledWhileWaitingIsRecheckedInsideTransaction；IdentityIntegrationIT.registrationLoginAndMeUseCurrentDatabaseRole；AdminReportingIT.managementAuthorizationAndValidationAreEnforced | 通过 |
| TC-SEC-004 | 哈希、日志、CORS 与管理端点 | IdentityIntegrationIT.passwordIsSaltedAndLoginFailureDoesNotRevealAccountExistence；AdminReportingIT.localSecurityDoesNotExposeManagementEndpointsOrAllowCrossOriginAccess；trace-evidence.txt | 本地回环 HTTP；生产 HTTPS 未执行 |
| TC-EVENT-001 | 目录创建发布与公开可见性 | IdentityIntegrationIT.catalogPublishRequiresCompleteHierarchyAndPublicVisibility | 通过 |
| TC-EVENT-002 | 筛选分页及通配字符 | IdentityIntegrationIT.catalogSearchPaginationAndLiteralWildcards | 通过 |
| TC-ADMIN-001 | 下架后已有订单与明细统计 | AdminReportingIT.offSalePreservesExistingLifecycleAndManagementSnapshots；managementOrdersExposeOwnersSnapshotsAndLifecycleRecordsWithFilters | 通过 |
| TC-ADMIN-002 | 发布完整性与目录合法性 | IdentityIntegrationIT.catalogPublishRequiresCompleteHierarchyAndPublicVisibility；catalogRejectsMalformedFieldsAndKeepsPublicQueriesOpen；catalogStockAndAuditRollbackTogether | 通过 |
| TC-ADMIN-003 | 版本、重放与冻结配置 | IdentityIntegrationIT.catalogPermissionsVersionReplayAndAudit；catalogSaleStatusAndCapacityUpdateFollowDatabaseFacts | 通过 |
| TC-ADMIN-004 | 冻结时间与历史快照 | IdentityIntegrationIT.catalogFrozenTimeCannotMoveForwardAndStockUpdateIsAtomic；OrderIntegrationIT.createSnapshotsExpiryAndOwnerQueriesSurviveCatalogChanges | 通过 |
| TC-ADMIN-005 | 下架竞争两种持锁顺序 | OrderIntegrationIT.offSaleHoldingEventLockWinsAgainstWaitingCreate；createHoldingSharedCatalogLockCommitsBeforeWaitingOffSale | 通过 |
| TC-ADMIN-006 | 三种时间、半开区间与一致快照 | AdminReportingIT.statisticsUseIndependentHalfOpenTimeWindowsAndCanHaveNegativeNet；statisticsKeepOneSnapshotWhenRefundCommitsBetweenAggregates | 通过 |
| TC-ORDER-001 | 下单支付、快照及本人查询 | OrderIntegrationIT.createSnapshotsExpiryAndOwnerQueriesSurviveCatalogChanges；OrderLifecycleIT.repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility | 通过 |
| TC-ORDER-002 | 20 次同键、异参、并发限购 | OrderIntegrationIT.twentyDuplicateRequestsReplayOneOrderAndConflictOnChangedPayload；concurrentDifferentKeysAcrossTiersEnforceSessionLimit | 通过 |
| TC-ORDER-003 | 支付取消两种竞争顺序 | OrderLifecycleIT.paymentAndCancellationCompetitionHonorsBothLockOrders | 通过 |
| TC-ORDER-004 | 支付关单竞争及锁后期限 | OrderLifecycleIT.paymentAndSystemCloseCompetitionHonorsBothLockOrders；paymentAndRefundWaitForStockThenRecheckTheirDeadlines；recovery-final.json | 通过 |
| TC-ORDER-005 | 取消归还后售罄原键不变 | AdminReportingIT.soldOutRejectionStaysImmutableAfterRealCancellationReleasesStock | 通过 |
| TC-ORDER-006 | 取消后历史 CREATE 重放及新键 | OrderLifecycleIT.cancellationRepeatsAndReleasesForAnotherPurchaseIncludingExpiredCancellation | 通过 |
| TC-ORDER-007 | 售票边界拒绝与重放 | OrderIntegrationIT.rejectedSaleResultsPersistAndReplayAfterConditionsChange | 通过 |
| TC-TIME-001 | 微秒边界及锁后时间 | OrderPolicyTest；OrderIntegrationIT.saleTimeIsReadAfterWaitingForStockLock；OrderLifecycleIT.paymentAndRefundWaitForStockThenRecheckTheirDeadlines | 纯规则微秒边界 + 真实锁等待，未改系统时钟 |
| TC-PAY-001 | 重复支付及退款后历史记录 | OrderLifecycleIT.repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility | 通过 |
| TC-PAY-002 | 受控失败原键与新键 | OrderLifecycleIT.simulatedFailureIsImmutableForOriginalKeyAndNewKeyCanRetry | 通过 |
| TC-CANCEL-001 | 重复取消、到期及状态拒绝 | OrderLifecycleIT.cancellationRepeatsAndReleasesForAnotherPurchaseIncludingExpiredCancellation；lifecycleStateMatrixAndIdempotencyScopeAreEnforced | 通过 |
| TC-REFUND-001 | 全额一次退款与非法入口 | OrderLifecycleIT.repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility；unauthorizedOperationsAndNonemptyBodiesHaveNoSideEffects；OrderPolicyTest | 通过 |
| TC-REFUND-002 | 失败重放、重试及重购 | OrderLifecycleIT.simulatedFailureIsImmutableForOriginalKeyAndNewKeyCanRetry；repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility | 通过 |
| TC-STATE-001 | 五状态迁移矩阵 | OrderLifecycleIT.lifecycleStateMatrixAndIdempotencyScopeAreEnforced | 通过 |
| TC-TX-001 | 真实保存点拒绝 | OrderIntegrationIT.realExecutorSavepointRevertsOrderSlotStockAndLogButCommitsRejection；OrderLifecycleIT.knownSimulatorRejectionAfterWritesUsesSavepointAndStillCommitsFailure | 通过 |
| TC-TX-002 | 逐写入异常整体回滚 | OrderIntegrationIT.systemFailuresAtEveryWriteStageRollbackEntireTransactionAndSameKeyRecovers；OrderLifecycleIT.everyLifecycleWriteFailureRollsBackAndOriginalKeyCanRecover | 通过 |
| TC-TX-003 | 有限锁重试及预算耗尽 | OrderIntegrationIT.lockFailuresRetryWholeTransactionAtMostTwice；realLockTimeoutReturnsRetryableErrorWithoutCommittedProcessing | 通过；保留初次机器暂停失败与复测 |
| TC-RECOVERY-001 | 真实成功响应丢失重放 | recovery-final.json：create/pay/refund_http_response_loss | 3 种提交成功后关闭 HTTP 响应 |
| TC-RECOVERY-002 | 提交前后强杀及提交未知 | recovery-final.json：四种操作 × 四种故障 | 16 场景，8 次真实 JVM 强杀；不强杀数据库 |
| TC-RECOVERY-003 | 100 到期、失败继续及禁用回收 | recovery-final.json：startup_100_expired_and_poison_retry；OrderLifecycleIT.scanPagesMoreThanHundredOrdersContinuesAfterOneFailureAndRetriesNextRound | 12.788s；正常运行到期亦复测 |
| TC-STOCK-001 | 三轮 1000 身份/100 线程/100 张票 | contention.json | 三轮均 100 成功/900 售罄、零系统错误 |
| TC-STOCK-002 | 最后一张及归还后重购 | OrderIntegrationIT.twoUsersCompeteForLastTicketWithoutOversell；OrderLifecycleIT.cancellationRepeatsAndReleasesForAnotherPurchaseIncludingExpiredCancellation；repeatedPaymentsRefundsAndHistoricalReplaysKeepIdsAndReleaseEligibility | 通过 |
| TC-STOCK-003 | 重复关单、对账快照及差异 | OrderLifecycleIT.disabledOwnerAndDuplicateSystemClosesReleaseOnlyOnceAndFailuresRollback；AdminReportingIT.readOnlyReconciliationFindsCorruptionAndAcceptsEveryLifecycleState；reconciliationUsesOneSnapshotWhileCancellationCommits | 历史库不宣称全局无差异 |
| TC-OBS-001 | 三类结果与 traceId | trace-evidence.txt；TraceFilter；故障测试返回与日志关联 | SUCCESS/REJECTED/SYSTEM_ERROR 分开记录 |
| TC-PERF-001 | 四项查询 20 线程 60+300s | performance.json；query-samples.csv.gz；sample-verification.json | P95 最多 48.606ms，零系统错误 |
| TC-PERF-002 | 下单支付 20 线程 60+300s | performance.json；trade-samples.csv.gz；sample-verification.json | P95 70.906/69.325ms，零系统错误 |
| TC-DEPLOY-001 | 独立目录复制 jar 及 HTTP 闭环 | final-package-smoke.json；部署运维文档 | 本机专用配置与临时密钥；不是跨机/生产验收 |

历史空库与认证证据见 [首批报告](../20260929-identity/summary.md)，目录见 [批次 2](../20260930-catalog/summary.md)，基础交易见 [批次 3](../20261001-order/summary.md)，生命周期见 [批次 4](../20261003-lifecycle/summary.md)。本轮摘要见 [summary.md](summary.md)。
