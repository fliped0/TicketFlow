# TicketFlow

迭代开发的票务后端。当前完成用户认证、活动目录管理及下单、取消、模拟支付、到期关闭、模拟退款的订单生命周期，以及管理员订单查询、统计和只读对账。

## 当前进度

- Java 17、Spring Boot 4.1.1、MyBatis-Plus 3.5.17。
- MySQL 8.0.45，Flyway V1～V3 共 18 张 tf_ 表（含目录版本及异步增量）。
- 注册、登录、当前用户、RS256 JWT、角色权限与健康检查。
- 活动、场次、票档管理，库存建档、上下架、冻结和公开目录查询。
- 单元测试和真实 MySQL / HTTP 集成测试。
- JdbcTemplate 交易执行器、保存点拒绝、幂等重放、场次限购与库存占用。
- 主动取消、模拟支付/退款、到期关单及启动补扫；详情展示唯一支付和退款记录。
- 管理订单与区间统计、只读对账、强杀/断连恢复及三轮争票验证。
- 当前完整 clean verify 131 项通过；30 项真实异步测试，适度竞争验证；历史本机性能另存。
- Redis 目录缓存/限流与 RabbitMQ 可靠性实验已完成；批次 9 异步主流程已验收，完整恢复/对账留批次 10。
- 真实支付与生产高可用留后续批次。

仓库：[fliped0/TicketFlow](https://github.com/fliped0/TicketFlow)。

## 环境与首次初始化

当前验证环境：Windows、Java 17、Maven 3.9.12、MySQL 8.0.45。脚本需要 PowerShell 7.4+；Docker 非当前前置条件。

将 Java、Maven、MySQL 客户端加入 PATH，配置 JAVA_HOME，确保 MySQL 位于 127.0.0.1:3306，然后首次执行：

```powershell
pwsh -File scripts/setup-local.ps1
```

脚本提示输入数据库管理员密码，创建 ticketflow_dev、ticketflow_test 和 tf_dev、tf_test 专用账号，生成随机密码和 RSA 密钥。不会修改其他项目数据库，不保存管理员密码。本机已初始化，勿再次执行。

config/local/ 保存本机配置并由 Git 忽略。脚本拒绝覆盖已有凭据；DDL 不具备整体原子性，中断后须检查已创建资源再处理。

## 构建、测试与启动

```powershell
pwsh -File scripts/test.ps1 -UnitOnly
pwsh -File scripts/test.ps1
pwsh -File scripts/run.ps1
```

依次为：仅单元测试、完整 verify（含打包和集成测试）、启动 jar（默认 8080）。

集成测试在应用及 Flyway 启动前限定 ticketflow_test / tf_test；测试密钥每次生成并清理。测试使用随机账号，不清空数据库，可重复执行。

未安装 Maven 时可用 backend/mvnw.cmd 或 backend/mvnw 下载锁定的 Maven 3.9.12：

```powershell
$env:MAVEN_USER_HOME = "$PWD/.tools/maven"
Push-Location backend
./mvnw.cmd test
Pop-Location
```

直接运行 verify 需设置 TF_DB_HOST、TF_DB_PORT、TF_DB_NAME、TF_DB_USER、TF_DB_PASSWORD，推荐用脚本加载本机配置。

应用使用 TF_JWT_PRIVATE_KEY / TF_JWT_PUBLIC_KEY 指向 PKCS#8 私钥 / X.509 公钥 PEM。可通过 TF_ADMIN_USERNAME / TF_ADMIN_PASSWORD 初始化管理员，已有账号不提权、不重置密码；初始化后移除这两个变量。

## 文档与交付

- [工程文档索引](docs/README.md)
- [架构与编码规范](docs/架构与编码规范.md)
- [已实现接口](docs/api/README.md) / [OpenAPI](docs/api/openapi.json)
- [测试报告](docs/04_测试计划与测试报告.md)
- [开发进度](docs/开发进度.md)
- [部署与运维](docs/05_部署与运维.md) / [性能实测](docs/06_性能测试与优化.md)

每批功能：实现 → 测试 → 更新接口文档和报告 → Git 提交 → 推送 GitHub。密码、私钥、构建缓存不提交。


异步开发环境：先执行 `./scripts/setup-async.ps1` 准备独立应用 MQ 权限，再运行 `./scripts/run.ps1 -WithAsync`。完整中间件回归使用 `./scripts/test.ps1 -WithAsync -Clean`，会包含 MQ 实验的一次 Rabbit 重启；云中间件须已运行。默认开关关闭，SYNC 场次仍走原同步下单。具体权限、状态及未完成恢复能力见 [部署说明](docs/05_部署与运维.md)和[正式 API](docs/api/README.md)。
