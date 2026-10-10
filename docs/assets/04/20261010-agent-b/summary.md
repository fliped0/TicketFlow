# Agent 批次 B 本地规则验收与接口切换

2026-10-10，基于 `995a265`。11 条审核规则、本地回答与 citations、资料不足和订单/实时数据边界已完成。代码与用例先提交为 `e2bd344`，用户在本机填写千问密钥后完成 **19/19 组真实联调，含 8/8 个 `qwen3.8-flash` 场景**；B 的规则与受限真实模型出口通过。

## 已执行结果

| 检查 | 实际结果 | 范围 |
| --- | --- | --- |
| Ruff | 通过 | Agent 代码与测试 |
| 完整确定性测试 | 203/203，0 失败/错误/跳过 | 含原 135 项和规则/接口切换检查；保留 1 条 Starlette/HTTPX 弃用提示 |
| 冻结本地规则评估 | 40/40 | 包含在 203 项中，不重复相加；29 MATCHED、6 INSUFFICIENT、2 NEEDS_ORDER_QUERY、2 ACTION_REQUIRED、1 NEEDS_LIVE_DATA |
| 规则引用 | 31/31 有预期规则的问题含有效出处；9/9 无规则问题无引用 | 真实章节/行号、摘录/文档哈希；不是模型引用准确率 |
| Java/Agent/MySQL + demo | 11/11 组 | 六项 Java 查询、他人订单拒绝、规则出处、未知规则拒答、会话隔离、写工具与无效 JWT 拒绝 |
| Java/Agent/MySQL + 千问 | 19/19 组 | 含直接查询、规则、归属及模型场景；8 次真实模型请求 |
| 千问真实模型场景 | 8/8 | 公开查询、缺编号追问、写请求拒绝、退款、支付期限、未知政策、具体订单通用说明及混合来源 |
| 118.178.253.75 云中间件 | Redis/RabbitMQ 均 healthy | 只读 SSH 查询，保留主机密钥检查，未变更云端资源 |

完整测试：`agent/.venv/Scripts/python.exe -m pytest agent/tests -q --junitxml=.tools/agent-results.xml`。独立联调加载专用测试配置后执行 `agent/.venv/Scripts/python.exe agent/tests/live_smoke.py`，2026-10-10T14:04:28～14:04:44 UTC。使用随机夹具，取消自己的订单、关闭子进程，保留数据历史，不清空库。测试 Java 关闭 Redis/MQ 与定时任务。Java/OpenAPI/Flyway 未改，历史 159 项 Java 测试未重跑或合并。

千问实际执行 `agent/.venv/Scripts/python.exe agent/tests/live_smoke.py --gateway`，北京时间 **22:36:10～22:36:32 +08:00**，runId `6bd35ff88c94`；JSON 时间保留 UTC。19 组均通过，夹具订单取消、子进程停止。实现和固定用例未作修改，因此未重复执行已通过的 203 项单元测试或修改断言。见 [完整联调](live-qwen.json) 与 [8 个真实模型场景](qwen-model-evaluation.json)。

本地 40 问在 13:48:05 UTC 首轮前冻结，[rule-evaluation.v1.json](../../../../agent/tests/rule-evaluation.v1.json) 保留原问题与预期。实现修复不修改预期迁就失败。显式词组检索不能保证任意自然语言覆盖，本地结果不代表模型问答准确率。

## 失败与未完成项

学校 B 首轮在公开查询发生 30 秒 ReadTimeout，9 组前置 HTTP 检查通过，整轮失败；第二轮通过 12 组前置/基础模型检查，在首个退款规则问题超时，后续规则模型用例未执行。两轮均清理自己的订单和进程，分别见 [首轮失败](school-first-failed.json) 与 [次轮失败](school-second-failed.json)，不能将部分通过计作完整通过。历史学校协议失败保留在 [10 月 9 日报告](../20261009-agent-gateway/summary.md)。

切换期间直接执行联调脚本时，新增配置导入缺少 Agent 模块路径，启动前报 `ModuleNotFoundError`，未产生模型请求或夹具。修正后 11 组联调通过。开发 Ruff 长行提示已修正，最终检查通过。

两次学校超时保留未知用量预留，账本未清零。千问联调前共 5 次学校请求、2 次未知，记账 22,682 单位，保留 [切换前快照](usage-before-qwen.json)。本次新增 8 次千问请求，均报告 usage，共 12,864 token；当天合计 13 次请求、35,546 本地单位，仍含 18,048 未知预留，见 [最终用量](usage.json)。这是 UTC 日跨接口汇总，不是平台账单或货币费用。继续沿用每日 20 次、每用户每日 10 次、10 RPM 与每日 200,000 计量单位限制。

## 当前接入状态

默认 base 为 `https://maas.qianwenaiapi.com/compatible-mode/v1`，请求追加 `/chat/completions`，模型 `qwen3.8-flash`。显式 `enable_thinking:false`、非流式、单次解析。路径、参数、凭据绑定及旧配置迁移通过模拟测试；该账号的本批模型访问权限和响应已通过真实联调。协议参考 [千问官方兼容文档](https://platform.qianwenai.com/docs/api-reference/toolkitframework/openai-compatible/overview)，2026-10-10 核对。

接口切换时旧密钥绑定学校接口并阻止错用；用户在本机执行 `setup_gateway.py --replace` 填写千问密钥后才进行实际调用，不自动复用旧凭据。[待密钥阶段快照](provider-pending-key.json) 与最终状态分别保留。密钥、JWT、配置、私有账本与原始 HTTP 日志均不提交；Windows 配置权限限定当前用户与 SYSTEM。具体计费、套餐和余额仍以该账号控制台为准，学校免费额度不适用，货币预算尚未交付。

本批 8 个千问场景全部通过；40 问本地检索与 8 个真实模型问题分别保留分母，小规模烟测不代表广泛模型问答准确率或性能 SLA。回答、引用、Java 与规则结果均在本地生成，不回传模型。模型只见用户问题、约束和工具定义；用户输入自身可能含订单编号。真实订单自然语言质量、C 写确认/恢复、多轮记忆、复杂查询链和生产部署另行验收。

## 证据

- [203 项确定性测试](tests.json)，[40 问本地评估](evaluation.json)，[demo/HTTP 联调](live-demo.json)，[千问真实联调](live-qwen.json)，[模型逐场景记录](qwen-model-evaluation.json)。
- [接口状态](provider.json)，[用量](usage.json)，[环境与 Java 包哈希](environment.json)，[云端只读状态](cloud.json)。
- [源码与资料哈希](source-manifest.sha256)：实际文件字节哈希；运行时资料哈希按归一化 UTF-8 换行校验。
- [资料审核说明](../../../../agent/knowledge/README.md)，[使用方法](../../../../agent/README.md)，[设计 v0.5](../../../07_Agent扩展设计.md)。
