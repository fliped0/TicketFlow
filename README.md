# TicketFlow

迭代开发的票务后端。当前完成用户认证与活动目录管理；抢票交易链路按后续批次开发。

## 当前进度

- Java 17、Spring Boot 4.1.1、MyBatis-Plus 3.5.17。
- MySQL 8.0.45，Flyway V1 包含 12 张业务表。
- 注册、登录、当前用户、RS256 JWT、角色权限与健康检查。
- 活动、场次、票档管理，库存建档、上下架、冻结和公开目录查询。
- 单元测试和真实 MySQL / HTTP 集成测试。
- 交易、模拟支付退款与恢复任务待后续实现。

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

每批功能：实现 → 测试 → 更新接口文档和报告 → Git 提交 → 推送 GitHub。密码、私钥、构建缓存不提交。
