# TicketFlow Agent 查询服务

V3 批次 A 提供独立 Python 服务、六项认证查询工具、受限编排与本机客户端。学校网关适配器使用 `DeepSeek-V4-Flash-0731-W8A8`，调用 `POST http://aigw.dlut.edu.cn/v1/chat/completions`。查询事实来自真实 Java API 的来源卡片。规则检索、写确认和多轮记忆留后续批次；Java 接口与 Flyway 不变。

## 1. 配置与启动

依赖由 `uv.lock` 固定，已验证 Python 3.12.10。本机已经安装至 `agent/.venv`，无需再安装 Python 或 PowerShell。其他电脑首次在仓库根目录执行：

```powershell
uv sync --project agent --locked --python D:\develop\Python-3.12\python.exe --cache-dir .tools/uv-cache
```

下面的命令可直接在 **CMD** 中逐条执行。先配置新密钥，输入不回显；本机已配置的跳过：

```bat
"D:\develop\TicketFlow\agent\.venv\Scripts\python.exe" "D:\develop\TicketFlow\agent\setup_gateway.py" --allow-http
```

密钥只保存于忽略的 `config/local/agent.json`，Windows 权限限定当前用户与 SYSTEM。轮换加 `--replace`，不会重置用量；不要把密钥发到聊天里。学校地址当前为 HTTP，`--allow-http` 明确启用该地址，应在可信网络使用；其他 HTTP 网关不允许。学校密钥和 Java 登录令牌是不同凭据。

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
| `GET /agent/v1/tools` | 六项直接查询工具与参数 schema |
| `POST /agent/v1/query` | `{"tool":"search_events","arguments":{"city":"杭州"}}` |
| `POST /agent/v1/chat` | `{"message":"查询杭州活动","sessionId":null}` |
| `DELETE /agent/v1/sessions/{id}` | 删除本人空闲会话，不撤销 JWT |

六项工具为 `search_events`、`get_event`、`list_sessions`、`list_tiers`、`list_my_orders`、`get_my_order`，只绑定固定 GET 路径，不接受 userId、URL、HTTP 方法或请求头。列表最多 20 条，ID 为正 BIGINT。本人的订单仍由 Java 检查归属，404 不区分不存在与无权访问。

工具响应经过 Java schema 校验和字段投影，裁去支付/退款明细及内部版本。卡片 `source` 含路径、参数、UTC 查询时间和 Java traceId。模型只接收系统约束、用户问题和工具定义，不接收 Java 查询结果、JWT 或用户身份。

网关只进行一次结构化解析，使用工具调用或严格 JSON 选择一批独立查询；Java 返回后服务器直接展示卡片。跨查询结果继续规划的复杂任务尚不支持。缺少条件通过固定字段枚举追问。两种输出格式共用工具白名单与参数校验；普通模型文本、截断输出、未知工具和虚构引用不作为业务结论。Java 活动文案不发给模型。

## 3. 隐私、会话和额度

**默认只向模型开放目录查询。** 模型只见四项公开目录工具与 `finish`；六项直接查询仍开放给认证用户。只有配置时显式加 `--share-order-data`（已有配置同时加 `--replace`）才开启模型的本人订单查询。该兼容选项在当前一次解析模式只开放工具，订单结果仍在本地展示，不回传模型；用户问题本身可能含用户提供的订单编号。本批真实模型仅验证公开查询；订单开关仅在模拟网关测试中验证。

会话不保存历史聊天或工具结果，“刚才那个订单”没有多轮记忆。最多 256 个会话，闲置 30 分钟失效，重启清空；同会话串行，全局最多 8 个活动请求。

| 限制 | 默认值 |
| --- | --- |
| 每轮 | 编排上限 4 次适配器决策、6 次查询，总时限 45 秒；网关实际最多 1 次模型 HTTP 请求 |
| 网络 | Java 单次 5 秒，模型 HTTP 单次 30 秒，无自动重试 |
| 模型请求 | 完整载荷最多 8,000 UTF-8 字节，输出 `max_tokens=1024` |
| 响应 | Java 256 KiB，模型 128 KiB，Agent 请求体 16 KiB |
| 本地模型请求配额 | 每 UTC 日共 20 次、每用户 10 次、每分钟 10 次 |
| 本地用量预算 | 每 UTC 日 200,000 计量单位 |

用量保存在忽略的 `agent/.runtime/model-usage.sqlite3`，发送前原子预留，重启不重置。按网关报告的 prompt/completion tokens 结算；失败、超时或缺 usage 保留 9,024 单位预留。**字节不是精确 tokenizer 计数**，9,024 是保守本地记账值，不是精确 token 或货币成本。不显示猜测费用；实际用量以学校控制台为准。损坏账本不自动删除重建。

学校指南公布每月 1 亿 token、每月 1 日重置不结转、20 RPM，供个人学习科研使用。本地更小的额度不包含同账号其他客户端的消耗。来源：[学校说明](https://its.dlut.edu.cn/info/2453/337368.htm)，2026-10-09 核对；工具协议参考 [New API 文档](https://doc.newapi.pro/en/api/openai-chat/)，具体模型兼容性以真实测试为依据。

## 4. 错误、验证与范围

401 重新登录；404 不存在或无权访问；409 会话忙；429 额度/轮次/网关限流；502 依赖或模型协议错误；503 模型关闭；504 整轮超时。错误不显示密钥或上游原文。日志只含 traceId、状态/错误码、耗时、工具名和用量，不保存完整聊天或私有响应；Uvicorn 访问日志关闭。

```powershell
# Ruff + 确定性测试，不访问真实模型
./scripts/test-agent.ps1
# 实际 Java/Agent/MySQL，固定 demo
./scripts/test-agent.ps1 -Live
# 另调用已配置学校模型，消耗本地和学校额度
./scripts/test-agent.ps1 -Live -Gateway
```

真实联调严格用 `ticketflow_test/tf_test`、已有 jar、JWT 和本机 MySQL。创建随机管理员、两名用户、活动/场次/票档及本人订单；取消自己的夹具订单，保留历史，关闭测试自己的进程，不清空库。该 Java 进程关闭 Redis/MQ 与定时任务。

135 项确定性测试覆盖工具、身份/会话、网关协议、私有开关、并发用量/重启/未知结果和错误脱敏。真实结果与失败记录分别见 [网关报告](../docs/assets/04/20261009-agent-gateway/summary.md)，历史见 [基础报告](../docs/assets/04/20261009-agent-a/summary.md)。模拟测试不能算真实模型评估；小规模烟测不代表广泛问答准确率。锁定 Starlette TestClient 的一条 HTTPX 弃用提示未屏蔽。

当前为本机单实例查询入口；规则引用、写确认/恢复、生产部署、货币计费和多轮记忆尚未交付。下一步为经过审核的规则资料与来源引用。
