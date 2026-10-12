# TicketFlow 前端

独立 Vue 3 + TypeScript 工程，与 Java 后端通过 HTTP API 通信。当前仅完成工程环境和启动页，登录、目录及交易页面尚未实现，不代表购票闭环验收通过。

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

打开 <http://127.0.0.1:5173>。仅监听本机，端口被占用时直接失败，不自动切换端口。`/api` 保持原路径转发到 `http://127.0.0.1:8080`，不新增后端 CORS 权限。启动页本身无需后端。

后续联调在另一个终端从仓库根目录执行 `pwsh -File scripts/run.ps1` 启动已有 Java jar。开发库管理员和演示数据尚未准备；不要重新执行 `setup-local.ps1`。

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

Vitest 用 jsdom 验证 Vue 单文件组件、Router、Pinia 和 Element Plus 的组合加载。Playwright 自动启动生产构建预览，覆盖 Chromium 桌面及手机视口、页面渲染、未知路由、返回首页和溢出检查；结束后关闭其预览服务，不复用不明来源的已有进程。

以上均为工具链烟测，不调用真实交易或模型，不作为业务测试计数。业务范围及验收见 [前端开发计划](../docs/11_最小前端闭环开发计划.md)。
