# TicketFlow 前端

独立 Vue 3 + TypeScript 工程，与 Java 后端通过 HTTP API 通信。当前已实现注册、登录、活动筛选/分页、活动详情与场次/票档选择。订单、支付、取消/退款和未知交易恢复留下一批实现，页面明确禁用下单。

## 环境与安装

使用 Node.js 24.14.1（24.x）和 npm 11.x。依赖精确版本及传递依赖记录在 `package.json` / `package-lock.json`，无需全局安装 Vue CLI。

```powershell
cd frontend
npm.cmd ci
npm.cmd run browser:install
```

Chromium 由 Playwright 下载到当前用户缓存，供自动化测试使用；不替换日常浏览器。锁定 jsdom 26.1.0 和 Vue Test Utils 2.4.6 以兼容当前 Node 版本；后者新版间接依赖 nopt 要求 Node 24.15.0 或更高的 24.x，安装时保留引擎检查而非强制忽略。安装会显示 whatwg-encoding、glob 的上游弃用提示；本次 npm 审计未报告已知漏洞，详见 [环境验证记录](../docs/assets/04/20261012-frontend-env/summary.md)。

## 开发与构建

```powershell
npm.cmd run dev
```

打开 <http://127.0.0.1:5173>。仅监听本机，端口被占用时直接失败，不自动切换端口。`/api` 保持原路径转发到 `http://127.0.0.1:8080`，不新增后端 CORS 权限。活动和登录功能要求 Java 后端运行，无法连接时页面提供错误提示与重试。

已有目录数据时，在另一个终端从仓库根目录执行 `pwsh -File scripts/run.ps1` 启动 Java jar。当前本机已准备三组同步演示活动；如需首次初始化或重启演示环境，使用下述专用入口。不要重新执行 `setup-local.ps1`。

## 本机演示数据

在仓库根目录的终端 A 启动开发库后端：

```powershell
pwsh -File scripts/run-frontend-demo.ps1
```

该 Windows 本机入口加载 `ticketflow_dev/tf_dev`，仅监听 127.0.0.1:8080，并关闭 Redis/异步开关。首次生成随机管理员凭据，通过既有 AdminInitializer 创建新账号；已有账号不提权、不重置密码。凭据以当前 Windows 用户绑定的加密形式保存到 Git 忽略的 `config/local/frontend-demo-admin.xml`，普通用户通过页面自行注册。

待后端启动后，在终端 B 创建或复用演示目录：

```powershell
$credential = Import-Clixml -LiteralPath config/local/frontend-demo-admin.xml
./scripts/setup-frontend-demo.ps1 -Credential $credential
$credential = $null
```

已有管理员也可直接执行 `pwsh -File scripts/setup-frontend-demo.ps1`，脚本安全提示输入账号密码，不自动初始化管理员。只通过本机 HTTP API 建立音乐、戏剧、展览三组目录；每组一个七天后开场的场次、99/199 元两个票档，每档 20 张。记录保存在 `.tools/frontend-demo/`，不含凭据/token。正常重复运行复用在售资源；过期/售罄时新建一批并保留历史，`-NewBatch` 可显式新建完整批次。

创建 API 没有幂等键，脚本先原子保存阶段，再发送 POST。结果未知时按唯一标记核对，不能唯一确认则停止，不盲重发。清单以操作系统文件锁防止两个准备进程并发写入，进程退出释放锁。数据损坏、API origin 不匹配或未完成批次需要先核查；不要直接删除清单重跑。

## 页面行为

- 公共活动无需登录，支持关键词、城市和分类筛选，查询条件与页码保存于 URL。
- 登录令牌只保存在当前标签页 `sessionStorage`；刷新时重新向后端验证身份，退出/401 清理状态。不保存密码，没有刷新令牌接口。
- 缺少有效会话时，选票页提供登录入口，登录后返回原活动并恢复票档选择（包括分页位置）。场次和票档请求有取消及旧响应隔离。
- 价格按整数分显示，时间为北京时间；已售罄/未开售/已停售票档不能选择，点击刷新重新获取库存。活动文案只作文本显示。
- 下单功能暂未开放。选择摘要不构成库存占用或购票承诺。

```powershell
npm.cmd run build
npm.cmd run preview
```

生产构建输出 `dist/`；本机预览使用 `http://127.0.0.1:4173`，同样代理 `/api`。Vite preview 仅用于本机检查，不是生产部署方案。

## 验证

```powershell
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e
```

上述浏览器测试使用明确模拟的 API 验证界面行为，不等于真实后端通过。完整检查与专用测试库联调从仓库根目录执行：

```powershell
pwsh -File scripts/test-frontend.ps1
pwsh -File scripts/test-frontend.ps1 -Live
```

`-Live` 严格使用 `ticketflow_test/tf_test`，独立随机端口的 Java 实例及测试 RSA 密钥，禁用关单和异步任务。先通过真实管理员 API 准备目录并验证重复运行复用，再由 Chromium 桌面/手机浏览器完成注册、重复账号拒绝、错误密码、登录返回选票页、刷新/退出和无效令牌验证。收尾下架本轮活动、关闭自身进程并删除临时密钥，不清库、不删除历史账号/记录。

真实测试日志与报告保存到忽略的 `.tools/frontend-live/<runId>/`，关闭真实测试的 trace 录制，凭据不写报告。真实测试预览使用随机端口，通过仅允许显式 127.0.0.1 origin 的 `TF_FRONTEND_API_TARGET` 代理到测试 Java，不改变默认开发目标。`tests/live-runner.mjs` 需由测试脚本加载专用配置后执行，不能直接指向开发库。

本批实际结果、历史失败和截图见 [查询与登录报告](../docs/assets/04/20261012-frontend-a/summary.md)。测试未调用交易或模型；完整购票闭环验收仍遵循 [前端开发计划](../docs/11_最小前端闭环开发计划.md)。
