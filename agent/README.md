# TicketFlow Agent 查询与确认服务

独立 Python 服务提供六项 Java 查询、本地审核规则与取消/模拟退款的待确认卡。模型为 `qwen3.8-flash`，接口为 `POST https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions`；交易只能通过独立确认入口执行，Java 决定最终业务结果。当前为本机单实例，Java 接口与 Flyway 不变。最新结果见 [批次 C 报告](../docs/assets/04/20261010-agent-c/summary.md)。

## 1. 配置与启动

依赖由 `uv.lock` 固定，已验证 Python 3.12.10。本机已经安装至 `agent/.venv`，无需再安装 Python 或 PowerShell。其他电脑首次在仓库根目录执行：

```powershell
uv sync --project agent --locked --python D:\develop\Python-3.12\python.exe --cache-dir .tools/uv-cache
```

下面的命令可直接在 **CMD** 中逐条执行。先配置新密钥，输入不回显；本机已配置的跳过：

```bat
"D:\develop\TicketFlow\agent\.venv\Scripts\python.exe" "D:\develop\TicketFlow\agent\setup_gateway.py" --replace
```

密钥只保存于忽略的 `config/local/agent.json`，Windows 权限限定当前用户与 SYSTEM。配置记录密钥所属接口，切换接口但未换密钥时阻止模型启动；不会把旧学校密钥自动发给千问。轮换加 `--replace`，保留原有配额和历史用量；不要把密钥发到聊天里。可用 `--url` 和 `--model` 指定接口与模型；新接口为 HTTPS，无需 `--allow-http`。兼容旧学校 HTTP 地址仍须明确启用，其他 HTTP 网关不允许。模型密钥和 Java 登录令牌是不同凭据。

在三个终端分别启动 Java、Agent 和聊天客户端；Java 已在 8080 运行则跳过第一条：

```bat
"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -File "D:\develop\TicketFlow\scripts\run.ps1"
```

```bat
"D:\develop\TicketFlow\agent\.venv\Scripts\python.exe" "D:\develop\TicketFlow\agent\run.py" --mode gateway
```

```bat
"D:\develop\TicketFlow\agent\.venv\Scripts\python.exe" "D:\develop\TicketFlow\agent\demo.py" --chat
```

客户端提示输入当前用户的 Java 登录令牌，输入不回显、不保存；登录见 [现有 API](../docs/api/README.md)。可问“帮我查杭州的公开活动”或“查询活动 1 的场次”，具体 ID 以实际目录为准。卡片显示来源、查询时间和业务字段，金额为整数分；空行退出。去掉 `--chat` 可直接输入工具名和参数 JSON。

PowerShell 可用 `./scripts/run-agent.ps1 -ModelMode gateway` 和 `./scripts/run-agent.ps1 -Client -Chat`。默认 `disabled` 保留直接查询入口；`demo` 只解析固定指令，不能算模型理解能力。

`TF_AGENT_JAVA_URL` 默认 `http://127.0.0.1:8080`；非回环地址须 HTTPS，不能带路径、用户名密码或查询串。`TF_AGENT_CONFIG` 可指定配置路径，`TF_AGENT_API_KEY` 可在进程环境覆盖密钥。所有 HTTP 客户端关闭环境代理和自动重定向。

## 2. 接口与来源

Agent 监听 `127.0.0.1:8090`。除健康检查外，每次请求携带当前用户 Java Bearer 令牌，通过 Java `/api/v1/users/me` 验证；每次工具执行前再次验证。成功为 `{code,data,traceId}`，错误为 `{code,message,traceId}`，禁止缓存。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /health` | 存活和模式，不代表依赖已就绪 |
| `GET /agent/v1/tools` | 九项工具及参数 schema（含两项只准备确认卡的工具） |
| `POST /agent/v1/query` | `{"tool":"search_events","arguments":{"city":"杭州"}}` |
| `POST /agent/v1/chat` | `{"message":"查询杭州活动","sessionId":null}` |
| `DELETE /agent/v1/sessions/{id}` | 删除本人空闲会话，不撤销 JWT |
| `POST /agent/v1/confirmations` | 准备本人订单；`{operation:cancel/refund,orderId,sessionId?}` |
| `GET /agent/v1/confirmations/{id}` | 查本人确认及执行结果 |
| `POST /agent/v1/confirmations/{id}/execute` | 独立确认；严格 `{"approved":true}` |
| `POST /agent/v1/confirmations/{id}/recover` | 严格 `{}`；原键恢复已经确认的未知操作 |
| `DELETE /agent/v1/confirmations/{id}` | 只撤销尚未执行的 PENDING |

六项工具为 `search_events`、`get_event`、`list_sessions`、`list_tiers`、`list_my_orders`、`get_my_order`，只绑定固定 GET 路径，不接受 userId、URL、HTTP 方法或请求头。列表最多 20 条，ID 为正 BIGINT。本人的订单仍由 Java 检查归属，404 不区分不存在与无权访问。

第七项 `search_rules` 只检索固定审核资料，参数为 `query`（1～2,000 字符）和 `limit`（默认 3、最多 5）。11 条 v1.0.0 规则覆盖一单一票、同场资格、支付期限、退款边界、回补、凭证、字段冻结、开售、下架、取消及模拟交易。来源限定需求/API 文档，校验版本、回答、原文、章节锚点及文档哈希；资料变化或损坏只停用规则工具，返回 `RULES_UNAVAILABLE`。详见 [资料审核说明](knowledge/README.md)。

聊天可问“解释本项目开场前退款规则并提供出处”；直接查询使用 `search_rules` 和 `{"query":"退款规则"}`，无需真实模型；demo 固定指令为“规则 退款规则”。聊天 `citations` 由服务器生成，含规则编号/版本、路径、真实章节/行号、摘录与哈希。混合查询分别显示 LOCAL 规则与 GET 实时卡片。

| 规则状态 | 行为 |
| --- | --- |
| `MATCHED` | 通用规则及出处 |
| `INSUFFICIENT` | 无资料，不承诺到账时间等未知政策 |
| `NEEDS_ORDER_QUERY` | 说明通用规则，不能认定指定订单可操作 |
| `NEEDS_LIVE_DATA` | 价格、库存与具体开售时间须实时查询 |
| `ACTION_REQUIRED` | 规则工具不执行交易或修改规则 |

检索使用显式词组，不能保证任意自然语言覆盖；聊天使用原问题，防止模型删去未知政策或订单条件。规则结果与摘录不回传模型。

工具响应经过 Java schema 校验和字段投影，裁去支付/退款明细及内部版本。卡片 `source` 含路径、参数、UTC 查询时间和 Java traceId。模型只接收系统约束、用户问题和工具定义，不接收 Java 查询结果、JWT 或用户身份。

网关只进行一次结构化解析，使用工具调用或严格 JSON 选择一批独立查询；Java 返回后服务器直接展示卡片。跨查询结果继续规划的复杂任务尚不支持。缺少条件通过固定字段枚举追问。两种输出格式共用工具白名单与参数校验；普通模型文本、截断输出、未知工具和虚构引用不作为业务结论。Java 活动文案不发给模型。

## 3. 隐私、会话和额度

**默认向模型开放四项目录查询、`search_rules`、`prepare_cancel`、`prepare_refund` 与 `finish`。** 七项直接查询和两项确认卡准备均开放给认证用户。只有配置时显式加 `--share-order-data`（已有配置同时加 `--replace`）才开启模型的本人订单查询。该兼容选项在当前一次解析模式只开放工具，订单结果仍在本地展示，不回传模型；用户问题本身可能含用户提供的订单编号。订单开关仅在模拟网关测试中验证，真实订单自然语言质量未验收。

Qwen3.8-Flash 请求显式关闭思考（`enable_thinking:false`），继续非流式一次解析。C 扩展评估后设置 `temperature:0.1` 降低路由输出随机性，依据 [千问参数说明](https://www.qianwenai.com/hub/skills/qianwen-text)；不保证完全确定性。接口、JSON 与扩展参数参考 [千问官方兼容文档](https://platform.qianwenai.com/docs/api-reference/toolkitframework/openai-compatible/overview)，2026-10-10 核对；当前本机配置的账户访问权限与本批响应已通过真实联调。

会话不保存历史聊天或工具结果，“刚才那个订单”没有多轮记忆。最多 256 个会话，闲置 30 分钟失效，重启清空；同会话串行，全局最多 8 个活动请求。

| 限制 | 默认值 |
| --- | --- |
| 每轮 | 编排上限 4 次适配器决策、6 次查询，总时限 45 秒；网关实际最多 1 次模型 HTTP 请求 |
| 网络 | Java 单次 5 秒，模型 HTTP 单次 30 秒，无自动重试 |
| 模型请求 | 完整载荷最多 8,000 UTF-8 字节，输出 `max_tokens=1024` |
| 响应 | Java 256 KiB，模型 128 KiB，Agent 请求体 16 KiB |
| 本地模型请求配额 | 每 UTC 日共 300 次、每用户 100 次、每分钟 30 次（旧学校接口最高 20 RPM） |
| 本地用量预算 | 每 UTC 日 2,000,000 计量单位 |

用量保存在忽略的 `agent/.runtime/model-usage.sqlite3`，发送前原子预留，重启和接口切换均不重置。按网关报告的 prompt/completion tokens 结算；失败、超时或缺 usage 保留 9,024 单位预留。**字节不是精确 tokenizer 计数**，9,024 是保守本地记账值，不是精确 token 或货币成本。不显示猜测费用；实际计费、套餐、余额和平台额度以新接口控制台为准，当前未实现持久货币费用上限。损坏账本不自动删除重建。

旧学校月度免费额度只属于 [历史学校联调](../docs/assets/04/20261009-agent-gateway/summary.md)，不适用于当前千问接口。本地额度不包含同账号其他客户端的消耗。

## 4. 错误、验证与范围

401 重新登录；404 不存在或无权访问；409 会话忙；429 额度/轮次/网关限流；502 依赖或模型协议错误；503 模型关闭；504 整轮超时。错误不显示密钥或上游原文。日志只含 traceId、状态/错误码、耗时、工具名和用量，不保存完整聊天或私有响应；Uvicorn 访问日志关闭。

```powershell
# Ruff + 确定性测试，不访问真实模型
./scripts/test-agent.ps1
# 实际 Java/Agent/MySQL，固定 demo
./scripts/test-agent.ps1 -Live
# 另调用已配置模型，消耗本地额度并可能产生平台费用
./scripts/test-agent.ps1 -Live -Gateway
```

真实联调严格用 `ticketflow_test/tf_test`、已有 jar、JWT 和本机 MySQL。创建随机管理员、两名用户、活动/场次/票档及本人订单；取消自己的夹具订单，保留历史，关闭测试自己的进程，不清空库。该 Java 进程关闭 Redis/MQ 与定时任务。

批次 B 的 203 项确定性测试通过，包含首轮前冻结的 40 问本地检索/引用评估，以及工具、身份、协议、配额、来源漂移和接口凭据隔离。另有 19 组真实联调通过，含 8 个千问模型场景：目录查询、缺编号追问、写请求拒绝、退款规则、支付期限、未知政策、具体订单只解释通用政策及混合规则/实时卡片。历史结果见 [批次 B 与接口切换报告](../docs/assets/04/20261010-agent-b/summary.md)，历史见 [学校网关报告](../docs/assets/04/20261009-agent-gateway/summary.md) 和 [基础报告](../docs/assets/04/20261009-agent-a/summary.md)。本地 40 问不是模型问答准确率，小规模烟测不代表广泛问答质量。锁定 Starlette TestClient 的一条 HTTPX 弃用提示未屏蔽。

当前为本机单实例查询、规则与独立确认入口。批次 C 的结果另见最新报告；生产部署、货币预算、多轮记忆和复杂查询链仍待后续。

## 5. 取消、模拟退款与未知结果

聊天可问“取消订单 123”或“退款订单 123”，实际编号以本人订单为准。模型工具只准备确认卡，尚未执行。`demo.py --chat` 显示订单、金额、影响、期限和模拟标识后，另行提示输入完整的“确认执行”；空行保留待确认，普通聊天里的“确认”不会执行交易。取消金额显示 0，退款显示整数分全额，均为本项目模拟操作。

确认首次有效期 5 分钟，默认执行租约 60 秒。执行前重查认证和订单快照，变化时拒绝本卡；Java 再次检查事务内规则。重复点击返回持久结果，执行中返回忙状态。令牌失效先重新登录。

结果为 `UNKNOWN` 或进程中断后执行租约到期时，使用 `/结果 确认ID` 查询、`/恢复 确认ID` 按原键重放。这可能完成此前已明确确认但尚未提交的操作，因此只用于原确认，不是新交易入口。确认到期不阻止已开始操作的结果恢复；未确认的 PENDING 不能恢复。同一订单有未决记录时不能另建操作或换键。

确认记录保存于忽略的 `agent/.runtime/confirmations.sqlite3`，以 SQLite FULL 同步事务提交状态、原键和快照；不保存 JWT、完整聊天或模型密钥。记录绑定 Java 服务地址，重启后仍可查；会话失效不会删除确认。数据库损坏、丢失或地址改变时停用写入口，先核查业务历史，不自动重建。目前没有自动清理历史记录，多节点、留存和备份恢复需后续设计。

```powershell
# 确认主流程 + 真实 TCP 丢失、进程强杀/崩溃的 8 个窗口；不调用模型
./scripts/test-agent.ps1 -Live -Confirmations -Faults
# 60 个冻结真实模型用例 + 8 个基础模型烟测 + 独立确认主流程
./scripts/test-agent.ps1 -Live -Gateway -Evaluation
```

扩展评估固定预期后才调用模型，任何格式错误、工具不符、错误引用或身份行为不符都记失败；不因安全拦截就记功能通过。原始失败、修复后完整复测分别保存，不合并本地检索、真实模型和故障恢复的分母。预算提高只调整本机限制，平台实际额度仍以服务商为准。
