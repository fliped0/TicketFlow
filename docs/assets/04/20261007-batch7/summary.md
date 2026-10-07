# 批次7：Redis目录缓存与同步限流

基于主干a455bb5，Java17、JdbcTemplate、统一分层。Spring Boot管理Spring Data Redis/Lettuce依赖，实际连接云Redis8.2.10；Windows MySQL8.0.45与HTTP集成。原27个HTTP操作保留，OpenAPI升级0.6.0，新增429/Retry-After与CATALOG_BUSY说明；异步接口仍未实现。

## 实现与取舍

- 公开活动、场次、票档稳定内容按筛选/分页缓存；缺失活动与空列表短缓存，实时库存和销售状态从DB刷新。
- 新增V2迁移目录版本表，管理写入/audit/版本递增同事务。提交后逻辑切代，回滚不切代；旧查询晚回填只填旧代，Redis写失败不恢复旧代。每次命中仍有版本SQL，全局版本会让无关目录缓存也失效，不宣称零DB访问。
- 健康Redis热点回源由64条本机条带锁合并；查询并发预算涵盖整个公开目录请求。Redis故障有界回源，JSON损坏视作miss；管理员与交易不读缓存。
- Lua同步用户/场次两额度原子判定，拒绝不扣另一额度、不落原键，短窗口同键重试不重复计数；持久原键优先重放。Redis故障SYNC沿用原数据库规则，未来ASYNC须另行失败关闭。
- 新增有限标签缓存/限流/业务结果指标。默认功能关闭，run.ps1 -WithRedis启用，凭据只在内存，独立SSH隧道有认证预检。

设计与参数详见03详细设计第14节、05部署第7.2节与架构规范第8节。

## 验证与证据

最终完整clean verify于2026-10-07 12:17:58（Asia/Shanghai）成功：14单元 + 74真实MySQL/HTTP集成，共88项，失败/错误/跳过全部0，约1分53秒。新增14项Redis集成实际执行；前3轮开发失败与修复分别保留，不删除后声称始终通过。

```powershell
$env:PATH = 'D:\develop\apache-maven-3.9.12\bin;' + $env:PATH
./scripts/test.ps1 -WithRedis -Clean
```

真实Redis使用随机tf:test命名空间，结束删除自己的键，不清空全库；DB专用ticketflow_test/tf_test，既有数据和V1迁移保留。新迁移的现存库升级及新表命名空间V1+V2初次/重复执行均纳入回归。

新增14项集成：筛选/分页键及命中、负缓存到期/发布、库存/受控时钟实时刷新、提交/回滚版本、下架与旧快照回填竞争、6线程热点合并且网络不在事务内、并发额度耗尽、JSON损坏回源、写缓存故障、用户限流/原键/异参/查询/等待后重试、6用户跨票档场次竞争、Lua双额度原子性和同键不重扣、正TTL实验、真实拒绝TCP连接时同步规则仍守恒。时钟测试为受控DB取时替代，不冒充锁等待跨截止实验；后者V1已有历史独立证据。

限流竞争采用2次/2秒测试配置，6用户/6线程应仅2个成功、4个429，库存/资格/流水守恒；热点6线程应仅一次活动详情回源。不是高压力或容量测试。

原始结果分别归档：逐套件[test-results.json](test-results.json)、[开发失败历史](development-failure-history.json)、[OpenAPI静态校验](openapi-validation.json)、[源码清单](source-manifest.sha256)。不保留含环境属性的JUnit XML或配置密码。

## TTL实验与边界

生产默认正TTL30s；独立集成配置缩短至2s（空TTL固定1s），首次详情一次回源、到期前再次请求零详情SQL、等待2150ms后再次一次回源。较短TTL提高自然回源频率、减少闲置缓存保留时间；管理更新可见性由持久版本决定，不靠等待TTL。负缓存1s过期后再次回源，发布切代立即允许新查询。该实验只证明过期语义和SQL调用变化，不推导QPS/P95或缓存收益比例。

14项用例涵盖真实Redis/MySQL/HTTP与有标记的故障注入；Redis连接不可用使用真实本机拒绝TCP，写缓存故障使用Gateway spy。没有执行云主机强杀、盘损坏、多实例并发总额度、万级请求、吞吐或三轮SLA对照；RabbitMQ业务、Lua抢票预占、发件箱、恢复和Agent仍未实现。

参考：[Spring Boot Redis配置](https://docs.spring.io/spring-boot/reference/data/nosql.html)、[Spring Data Redis脚本](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)。没有套用通用缓存注解缓存整份实时库存响应。
