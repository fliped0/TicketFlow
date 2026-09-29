# 统一分层重构回归报告

- 批次：20260929-layered
- 环境：Windows / Java 17 / Maven 3.9.12 / Spring Boot 4.1.1 / MyBatis-Plus 3.5.17 / MySQL 8.0.45。
- 数据库：ticketflow_test，专用 tf_test；测试启动前校验隔离配置，未使用 H2。
- 命令：通过 `scripts/common.ps1` 导入 test 配置后执行 `mvn clean verify`。
- 完成时间：2026-09-29 16:22（Asia/Shanghai）。
- 最终结果：BUILD SUCCESS，19 个测试方法，0 失败、0 错误、0 跳过。
- 源码版本依据：[SHA-256 清单](source-manifest.sha256)；提交号由包含本报告的 Git 提交确定。

## 测试结果

| 测试类 | 测试数 | 通过 | 失败 / 错误 / 跳过 |
| --- | ---: | ---: | --- |
| AccountRulesTest | 2 | 2 | 0 / 0 / 0 |
| AdminInitializerTest | 4 | 4 | 0 / 0 / 0 |
| IdentityServiceTest | 3 | 3 | 0 / 0 / 0 |
| IdentityIntegrationIT | 10 | 10 | 0 / 0 / 0 |

## 本批验证范围

- Java 包已统一为 controller、service、mapper、model，以及 config、security、common。
- Controller 不再直接依赖 Mapper；`GET /api/v1/users/me` 经 IdentityService 查询并校验当前账号状态。
- DTO、VO、entity 已拆为独立类型，持久化实体不直接作为 HTTP 响应。
- JWT 数据库身份转换已从配置类移入 security 组件。
- 干净构建验证了 Spring 组件扫描、MyBatis Mapper 注册、安全过滤链和既有 HTTP 契约。
- 新增 3 项 Service 单元测试，覆盖正常查询、账号不存在和账号被禁用。

## 验收边界

本批只调整工程结构并加强当前用户查询边界，未新增业务接口。活动目录、交易、支付退款、恢复、并发及性能测试仍待对应模块实现后执行，不据此宣称完整 V1 验收通过。
