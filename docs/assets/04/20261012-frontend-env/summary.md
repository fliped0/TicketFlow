# 前端开发环境验证（2026-10-12）

## 范围与结果

新增独立 `frontend/` 工程与浅色中文启动页，安装并精确锁定前端及测试依赖。未安装全局 Vue CLI，未升级 Node、Java 或数据库，未更改后端接口、CORS 或数据库。Chromium 及其配套运行工具由 Playwright 安装到当前用户缓存。

| 检查 | 实际结果 |
| --- | --- |
| Node / npm | 24.14.1 / 11.11.0 |
| 依赖安装 | 197 个包安装成功；npm 审计 198 个包，0 个已知漏洞 |
| 类型检查与生产构建 | `npm run build` 通过，包含 `vue-tsc --noEmit` |
| 组件工具链 | `npm test`，1/1 通过，实际加载 Vue SFC、Router、Pinia 和 Element Plus |
| 生产构建浏览器烟测 | `npm run test:e2e`，Chromium 桌面与手机视口 2/2 通过，0 重试 |
| 开发服务 | `npm run dev` 在 127.0.0.1:5173 启动，HTTP 200 |
| 视觉检查 | 实际查看 1365×900 桌面与 390×844 手机视口截图，布局无横向溢出 |

浏览器检查包括首页渲染、未知路径直接访问、路由返回首页和无运行时异常。自动化使用 4173 上的生产构建预览，测试自行启停该预览，不复用已有服务。截图保存在忽略目录 `.tools/frontend-environment/`，不作为业务数据。

## 版本与问题记录

- Vue 3.5.43、Router 4.6.4、Pinia 3.0.4、Element Plus 2.14.7。
- TypeScript 5.9.3、Vite 8.3.4、Vue 插件 6.0.10、vue-tsc 3.3.13。
- Vitest 5.0.4、Vue Test Utils 2.4.6、jsdom 26.1.0、Playwright 1.64.0、Chromium 156.0.8078.4。
- 版本与 engines/peerDependencies 从 npm 官方 registry 读取；直接版本和完整解析结果分别保存于 package.json 和 package-lock.json，安装保留 engine-strict。
- 首次安装 Vue Test Utils 2.5.1 时，其 js-beautify 2.x → nopt 10.0.1 间接依赖要求 Node 24.15.0，触发 EBADENGINE。改用 Vue Test Utils 2.4.6 后安装成功，没有强行忽略引擎限制。
- 首次组件烟测因 Element Plus 样式 JS 入口将 CSS 交给 Node 加载而失败。改为直接引入 base.css 与 el-tag.css 后重新执行构建及测试并通过；没有跳过断言。
- 安装保留 whatwg-encoding 3.1.1 和 glob 10.5.0 上游弃用提示；审计结论只代表本次 npm 返回结果。浏览器测试保留执行环境同时设置 NO_COLOR/FORCE_COLOR 的提示，不影响断言。

## 尚未完成

仅验证开发工具链，不包含真实后端代理联调、注册登录、目录查询、购票、取消、模拟支付/退款及未知结果恢复。开发库管理员未初始化，演示数据脚本未编写，未调用模型或启动云中间件。已有后端/Agent 历史结果未重跑或累加。

复跑步骤见 [前端运行说明](../../../../frontend/README.md)；业务范围继续遵循 [前端开发计划](../../../11_最小前端闭环开发计划.md)。
