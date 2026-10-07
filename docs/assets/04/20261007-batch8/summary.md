# 批次8：RabbitMQ可靠性学习实验

基于主干`20bee68`，Java17、Maven3.9.12、JdbcTemplate、统一分层。本机MySQL8.0.45与云端RabbitMQ4.3.6，经SSH隧道连接；AMQP Java客户端5.30.0为test scope。本批新增独立实验入口，没有接入票务异步HTTP业务或执行设计DDL。

## 实现与关键结论

每次运行创建随机临时vhost及无管理标签的专用账号，仅允许该vhost的lab.*资源；工作、三条TTL重试、死信队列均durable quorum，消息deliveryMode=2。死信采用at-least-once/reject-publish策略，队列上限1000条；单节点没有高可用能力。两消费者并发实验使用2个线程，未进行压力容量测试。

稳定eventId与业务hash用于幂等，deliveryTag只用于当前channel的ACK。合成account/inbox/effect三表同一READ COMMITTED事务提交；失败一起回滚，重复消息只查原hash，异参隔离。发布ACK且无mandatory return才算确认成功；NACK、return、UNKNOWN分开。MQ操作在DB事务外，消费ACK在Service返回之后。

| 实验 | 实际检查 |
| --- | --- |
| confirm/persistence/manual ACK | 持久消息、quorum类型、有效死信策略；提交后ACK，队列排空 |
| mandatory return | 错误路由返回RETURNED，无副作用，不当作确认成功 |
| publisher NACK | 有限quorum队列overflow实际触发NACK；阈值允许暂时超出，不假设第二条必失败 |
| 发布确认丢失 | 真实TCP代理丢一帧Basic.Ack，第一次UNKNOWN；原eventId重发，两投递只一份副作用 |
| 两消费者重复竞争 | 两条相同eventId同时处理，仅一次inbox、流水及计数变化 |
| 同ID异参 | 原成功保留，冲突消息进入死信，无第二份副作用 |
| DB事务故障 | inbox、计数、流水全回滚；未ACK断开channel，重投成功 |
| 提交前强杀 | 真实独立Java子进程到达标记后强杀；未提交三份写入均消失，重投执行一次 |
| 提交后ACK前强杀 | 子进程提交完成后暂停并强杀；消息重投但副作用仍一份 |
| 有限退避成功 | 三次事务失败回滚，400/800/1600ms TTL重试；第四次成功，无即时无限requeue |
| 毒消息与死信重放 | 初次加三次重试仍失败后DLQ，保留x-death；修复原因后原ID重发确认，再ACK死信，最终一次副作用 |
| 错误信封 | 无DB副作用，原消息体隔离到死信；测试核查后ACK清理 |
| broker正常重启 | 已确认未消费消息保留；重启后passive检查原队列，无重新建队列，消费成功 |

2026-10-07 13:16:36完整clean verify通过，耗时3分18秒：14单元+87真实集成=101项，0失败/错误/跳过，包含原88项与新增13项MQ实验。首轮通过的13项实验与退避200ms单用例对照均另存原始观测，不覆盖最终回归。完整回归逐套件结果见[test-results.json](test-results.json)，最终13项MQ观测见[observations.json](observations.json)。

```powershell
$env:PATH = 'D:\develop\apache-maven-3.9.12\bin;' + $env:PATH
./scripts/mq-lab.ps1
./scripts/mq-lab.ps1 -RetryBaseMillis 200 -TestCase boundedBackoffThenSuccessfulRetry
./scripts/test.ps1 -WithRedis -WithRabbitMQ -Clean
```

## 退避对照与失败历史

首次通过实验默认400ms的三次实测等待为478/949/1809ms；仅将base缩至200ms后为358/619/945ms，对应TTL200/400/800ms。两组均为每点一次的小样本，调度和SSH网络造成额外时间；只验证退避语义，不能推出吞吐、P95或最优生产参数。最终全量默认400ms观测独立存放。

首轮管理建环境遇Java默认h2c升级EOF，固定HTTP/1.1后独立实验通过。首轮完整101项中一项读取有效死信策略为空而失败，保留[失败逐项结果](failed-full-test-results.json)及[失败轮观测](failed-full-observations.json)；依据[管理统计采样机制](https://www.rabbitmq.com/docs/management#statistics-interval)和时序，判断为观测窗口，改为10秒有界轮询并保留准确策略断言。第二轮隧道Connection reset，MQ建环境和13个Redis准备步骤报连接错误；保留[网络失败结果](network-failed-test-results.json)，直接SSH确认容器健康后恢复隧道、认证并再次完整回归。详细时间及处理见[开发失败历史](development-failure-history.json)。不删失败后宣称每轮通过。

## 隔离、证据与范围

每次运行结束删除自己的vhost和专用用户；最终清理及云端健康核查见[environment.json](environment.json)。MySQL专用ticketflow_test/tf_test，三表以随机b8前缀创建；账号无DROP，因此保留实验表，不删除原数据、不扩大权限。完整实验短暂重启RabbitMQ，Redis保持运行；未购新资源或安装本机Docker/WSL。

证据仅保留白名单结果、合成副作用计数、错误摘要和源码/包哈希；不提交含环境属性的JUnit XML、密码或私钥。[源码清单](source-manifest.sha256)记录本机实际文件字节（含行结束符），[包与API检查](package-api-validation.json)记录运行包及契约。迁移与Git基线按CRLF/LF规范化比较；最初原字节断言因行结束符差异失败，校核规则修正后通过，没有修改SQL。OpenAPI仍0.6.0、27个HTTP操作，实验类及AMQP测试依赖不进入应用包。

TTL副本确认后ACK仅用于实验。正式异步订单仍必须在DB内提交RETRY_WAIT/next_attempt_at及新outbox再ACK，依靠请求终态/work_version/epoch恢复。没有验证订单SENT标记未知、持久202、Lua抢票预占、库存/资格投影、Redis重建、100在途收敛、宿主机断电、永久盘丢失或多节点多数派故障；这些不计作已实现。下一批9实现异步抢票主流程，批次10恢复闭环，Agent之后独立设计。

## 复盘

发布confirm回答broker是否接收；消费ACK回答消费者是否处理，两者互不替代。即使DB已提交，ACK前退出仍会重投，因此需要稳定业务标识和DB原子去重。超时UNKNOWN只能按原ID确认/重发，不能直接推断失败或释放库存。重试是延后再执行，死信是隔离诊断；两者都不替代持久业务终态和恢复扫描。

组件语义依据：[RabbitMQ确认机制](https://www.rabbitmq.com/docs/confirms)、[Quorum与安全死信](https://www.rabbitmq.com/docs/quorum-queues)、[死信配置](https://www.rabbitmq.com/docs/dlx)。项目协议边界见[异步协议第11节](../../../03_V2异步抢票协议.md)。200ms对照由工具执行；用户独立修改与去重练习未代填为已完成。
