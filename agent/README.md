# TicketFlow Agent 查询基础

当前为 V3 批次 A 的确定性基础实现：六项只读工具、真实 Java 身份验证、模型适配协议、受限编排与本机演示客户端。**没有接入真实模型**；尚未验收自然语言理解、RAG、确认写入或付费预算。Java 后端保持独立运行，接口与数据库迁移未变。

## 1. 环境与启动

Python 3.12/3.13；本机已验证 Python 3.12.10。依赖由 `uv.lock` 固定，项目虚拟环境为 `agent/.venv`。在仓库根目录执行：

```powershell
uv sync --project agent --locked --python D:\develop\Python-3.12\python.exe --cache-dir .tools/uv-cache
```

其他电脑将 `--python` 改为实际 Python 路径。不要提交虚拟环境、令牌或本地配置。按现有步骤启动 Java，再分别在两个终端运行：

```powershell
# 终端一：默认关闭模型，开放认证查询入口，监听 127.0.0.1:8090
./scripts/run-agent.ps1

# 终端二：交互式工具客户端，令牌输入不回显，不写入文件
./scripts/run-agent.ps1 -Client
```

先通过 Java 的既有登录接口取得当前用户令牌；客户端不会代用户注册、保存密码或执行交易。客户端输入工具名和参数 JSON，例如 `search_events` 与 `{"city":"杭州"}`，或 `get_my_order` 与 `{"orderId":"123"}`。输出是带来源和查询时间的 JSON 数据卡片；金额单位为整数分。

`TF_AGENT_JAVA_URL` 默认 `http://127.0.0.1:8080`。可指向其他固定 Java origin；非回环地址必须使用 HTTPS，不能包含路径、查询串或 URL 用户密码。客户端固定访问本机 8090；HTTP 代理环境变量不参与业务请求，不自动跟随重定向。

需要检查聊天编排时运行 `./scripts/run-agent.ps1 -ModelMode demo`。demo 是固定指令演示，支持 `查活动`、`我的订单`、`活动 1`、`场次 1`（活动 ID）、`票档 1`（场次 ID）、`订单 1`；其他自然语言会明确拒绝。不能将它作为模型能力或问答准确率演示。真实服务商适配和调用费用控制尚未实现，其他 model mode 会在启动时被拒绝。

## 2. Agent 自身接口

除 `/health` 外，所有接口要求 `Authorization: Bearer <当前用户令牌>`，每次通过 Java `/api/v1/users/me` 验证；聊天每次工具执行前再次验证。成功响应为 `{code,data,traceId}`，错误为 `{code,message,traceId}`；响应禁止缓存，traceId 由服务端生成。Java 本人的接口继续检查订单归属。

| 方法与路径 | 请求 / 行为 |
| --- | --- |
| `GET /health` | 进程存活及模型模式；不代表 Java 或模型已就绪 |
| `GET /agent/v1/tools` | 六项查询工具的名称、用途与参数 schema |
| `POST /agent/v1/query` | `{"tool":"search_events","arguments":{"city":"杭州"}}`；直接查询 |
| `POST /agent/v1/chat` | `{"message":"我的订单","sessionId":null}`；disabled 返回 503，demo 使用固定指令 |
| `DELETE /agent/v1/sessions/{session_id}` | 仅删除当前用户的空闲会话，不撤销 Java JWT |

工具为 `search_events`、`get_event`、`list_sessions`、`list_tiers`、`list_my_orders`、`get_my_order`。参数不接受 userId、URL、请求头或 HTTP 方法。列表默认 10 条、最大 20 条，page 为 1～10000，状态使用现有 Java 枚举，ID 为有符号 BIGINT 范围内的正整数字符串。

工具结果包含 `tool`、`data`、`source`。source 保存固定 GET 路径、实际查询参数、UTC 查询时间及 Java traceId。响应以版本化的 Java schema 校验，按字段白名单投影，去除支付/退款明细及内部版本，不按模型文本改写关键事实。Java 状态码、无结果、网络失败分别处理，失败不会返回伪造的空订单或售罄结果。

本轮聊天不保存历史文本或工具结果，只保存会话 ID、用户归属、最后使用时间及忙状态，后续轮次不会理解“刚才那个订单”。会话最多 256 个，空闲 30 分钟失效，重启清空；同会话串行，全局最多 8 个活动请求。失败的新会话会回收。需要缩小结果时使用分页和筛选。

## 3. 边界与错误

每轮最多 4 次模型适配器调用、6 次工具查询，总时限 45 秒；Java 单次网络超时 5 秒。HTTP 请求体最大 16 KiB，Java 响应最大 256 KiB；适配器上下文暂以 32,000 字符限制。该字符限制不是设计中的 8,000 token 预算；真实模型 tokenizer、输出 token 限制和持久费用预留待服务商接入时落实。

401 要求重新登录；404 表示不可访问或不存在，不区分他人订单；409 为会话忙；429 表示本地容量/轮次上限或 Java 限流；502 为依赖或契约错误；503 为未配置模型；504 为整轮超时。无自动无限重试。默认日志仅记录 Agent traceId、HTTP 状态和耗时；不记录令牌、搜索内容或私有响应。命令关闭 Uvicorn 访问日志以避免 URL 参数进入日志。

这是一套本机单实例基础，不提供公网生产部署承诺。规则语料、规则引用、写确认、SQLite 未决交易恢复、多轮记忆和真实模型费用控制不在本次已实现范围内。

## 4. 验证

```powershell
# Ruff + 确定性测试，不访问真实模型、数据库或 Java
./scripts/test-agent.ps1

# 再启动已有 Java jar 与 Agent，使用真实专用测试库验收
./scripts/test-agent.ps1 -Live
```

`-Live` 需要现有 `config/local/test.json`、JWT 文件、Java jar 和本机 MySQL；严格限定 `ticketflow_test/tf_test`。测试会创建随机管理员/用户、活动、场次、票档及一笔订单，测试后取消自己的订单；保留测试事实，不清空库。只关闭测试自身启动的进程，Redis/MQ 与定时任务在该 Java 进程关闭。凭据只通过进程环境或内存传递，报告不包含令牌和密码。

确定性测试用 HTTPX MockTransport 模拟 Java 故障响应；真实联调使用独立 Java 和 Uvicorn 进程以及实际 HTTP/MySQL。两类结果分别记录，不能将模拟响应当成真实后端测试。证据见 [本批报告](../docs/assets/04/20261009-agent-a/summary.md)。

当前锁定依赖的 Starlette TestClient 会发出使用 HTTPX 的弃用提示；测试未屏蔽该提示，当前组合已验证通过。升级测试客户端时重新核对生命周期与异常行为。

实现参考：[FastAPI 生命周期](https://fastapi.tiangolo.com/advanced/events/)、[HTTPX 测试传输](https://www.python-httpx.org/advanced/transports/)、[Pydantic 模型](https://docs.pydantic.dev/latest/concepts/models/)。Java schema 副本由当前 OpenAPI 提取，测试逐项核对源文件以发现契约漂移。
