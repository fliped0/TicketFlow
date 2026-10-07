# 云中间件环境补验收（2026-10-07）

基于主干 `23aacb8`，新增 `scripts/check-middleware.py`。Python/Pika 是隔离的运维验证客户端，业务后端继续采用 Java17/JdbcTemplate。结果见 [probe.json](probe.json)。

## 实际执行

Windows 应用/MySQL + 杭州 ECS 2vCPU/4GiB Ubuntu22.04 云中间件，SSH 回环转发。镜像及资源限额见部署文档第7节。项目 `.tools/middleware-venv` 安装固定 `pika==1.3.2`，不安装本机 Docker/WSL。

```powershell
python -m venv .tools/middleware-venv
.tools/middleware-venv/Scripts/python.exe -m pip install pika==1.3.2
.tools/middleware-venv/Scripts/python.exe scripts/check-middleware.py --host 118.178.253.75 --key "$env:USERPROFILE/.ssh/ticketflow_ecs" --output docs/assets/04/20261007-middleware/probe.json --restart
```

已有 SSH 隧道必须提供 16379/15673/15672。凭据通过 SSH 在内存中读取，报告不含密码。脚本只创建随机测试 vhost、quorum 队列和带600秒 TTL的探针键，并在 finally 中删除自己的 vhost/键；实际清理零错误。`--restart` 会重启开发实例中的两个容器，省略该选项不会执行重启。

七项检查通过：Redis认证写入、AMQP认证及persistent/mandatory发布确认、不可路由消息退回识别、未ACK断连后redelivered、手动ACK后队列为空、Redis正常重启保留键、RabbitMQ正常重启保留已确认消息。使用单节点quorum队列，没有高可用副本。

重启后容器均healthy；单次闲置观测 Redis约4.418MiB/512MiB、RabbitMQ约102.6MiB/1GiB，主机可用内存2.9GiB，磁盘余量34GiB。该采样不代表运行负载峰值，也不用于证明吞吐能力。

## 证据边界

完整 Maven `clean verify` 于2026-10-07 11:38:09（Asia/Shanghai）成功：14单元 + 60真实MySQL/HTTP集成，共74项，失败/错误/跳过均为0。运行专用ticketflow_test配置，未改变历史迁移或HTTP接口。逐套件结果及脚本/产物哈希见[test-results.json](test-results.json)。中间件七项烟测单独统计，不充作Java新增测试或端到端订单用例。

仅验证中间件协议与正常容器重启。Java应用接入、数据库发件箱、消费业务幂等、Lua抢票、重试死信、崩溃/未知提交补偿和epoch恢复仍待后续实现。未测试断电/AOF最后一秒、数据卷丢失、多节点高可用；未复测性能，更未生成优化收益数字。

发布确认、mandatory返回和消费ACK是三种不同信号；业务代码必须分别处理。参考 [RabbitMQ确认机制](https://www.rabbitmq.com/docs/confirms)、[Pika同步发布确认](https://pika.readthedocs.io/en/stable/examples/blocking_delivery_confirmations.html)。
