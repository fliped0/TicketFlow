# 批次 2：活动目录测试报告

- 执行批次：20260930-catalog。
- 环境：Windows、Java 17、Maven 3.9.12、Spring Boot 4.1.1、MySQL 8.0.45。
- 测试库：ticketflow_test / tf_test；应用和 Flyway 启动前校验数据库身份。
- 命令：加载 test 配置后执行 `mvn clean verify`。
- 完成时间：2026-09-30 11:07（Asia/Shanghai）。
- 最终结果：BUILD SUCCESS；27 项测试，0 失败、0 错误、0 跳过。
- 源码依据：[SHA-256 清单](source-manifest.sha256)；Git 提交号由包含本报告的提交确定。

| 测试类 | 测试方法 | 结果 |
| --- | ---: | --- |
| AccountRulesTest | 2 | 全部通过 |
| AdminInitializerTest | 4 | 全部通过 |
| IdentityServiceTest | 3 | 全部通过 |
| IdentityIntegrationIT | 18 | 全部通过 |

本批新增 8 项真实 MySQL / HTTP 集成测试，覆盖目录完整性发布、下架后配置和重新上架、后台权限、版本冲突与重复目标、公开筛选分页及通配字符、冻结边界、库存初始化与容量修改、销售状态、历史订单阻止重置容量、错误输入，以及库存建档与审计失败时的事务回滚。原有 19 项认证与基础回归继续通过。

测试时发现 `DATETIME` 经 JDBC `Timestamp` 在本机时区出现偏移，曾将未来场次误判为过期。目录模块改为按数据库 UTC 日期时间文本读写，再通过完整回归。测试期间早期失败均已修复；最终命令成功。

本批只对已实现接口和数据库行为下结论。下单与下架竞争、已有订单售后、统计、并发抢票及性能测试依赖后续批次，尚未执行。
