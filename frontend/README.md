# CMS 内容前端

Vue 3 + TypeScript + Element Plus，构建为静态资源后随传统 Spring MVC WAR 部署。运行时没有独立 Node 服务，也不依赖 CDN。

界面优先使用 Element Plus 的 Tree、Table、Form、Input、Select、DatePicker、Pagination、Dialog、Descriptions、Alert、Empty 等组件及官方图标。自定义 CSS 仅负责应用布局和窄屏适配，不手写另一套表格、弹窗或表单控件。组件采用本地导入，页面按分类、文章和公开阅读拆分异步资源。

## 构建

本机验证使用 Node 22.16.0；Vite 所需 Node 版本见[官方环境要求](https://vite.dev/guide/)。依赖版本固定于 package.json，package-lock.json 锁定完整依赖；Element Plus 的安装方式见[官方文档](https://element-plus.org/en-US/guide/installation)。

```powershell
cd frontend
npm ci
npm run build
cd ..
mvn clean package
```

或从工程根目录运行 `./scripts/build.ps1`，该脚本依次安装锁定前端依赖、类型检查、构建页面和打包 WAR。

`npm run build` 先执行 vue-tsc，再将静态资源输出到 `src/main/webapp/content/`。该目录为生成物，会被清空重建，不应手动编辑；Vite 配置验证输出位置。构建时同时生成 `THIRD_PARTY_LICENSES.md`，保留实际打包依赖的开源许可。

部署 target/enterprise-cms.war 后入口为 `/enterprise-cms/content/index.html`，公开入口为 `/enterprise-cms/content/public.html`；上下文名可改变，资源与 API 地址均相对解析。权限、业务和日志最终仍由服务端执行。当前隔离分支已接入 B 的真实内容 Controller/权限/日志；本地 H2 验证不代替目标 MySQL 和独立外部部署。

## 开发与验证

`npm run dev` 仅监听本机。默认代理目标为 `http://127.0.0.1:8080`，上下文为 `/cms`，支持 `/api`、`/media` 和后端返回的 `/cms/media` 地址；Cookie Path 改为开发根路径。启动前可通过环境变量 `VITE_CMS_TARGET` 和 `VITE_CMS_CONTEXT` 指定实际 Tomcat 地址及上下文，不必修改源码；变量只配置开发代理，生产地址仍相对部署上下文解析。

```powershell
npm run typecheck
```

2026-11-06 UI A 第一批新增 `npm test`，用 Node 的 TypeScript 转换运行路由/API 契约测试；本轮实测 Node 24.15.0。该测试入口需要支持 `--experimental-transform-types` 的 Node（22.7+），会显示实验特性提示；不影响浏览器运行。Quill 固定为 2.0.3，仅准备依赖，编辑器组件由 B 后续接入。许可原文在 `public/licenses/Quill-LICENSE.txt`，构建会复制到 `content/licenses/`；实际进入打包的依赖仍由 Vite 自动收集许可。

工程根目录的 `src/test/browser/content-pages.cjs` 用真实浏览器操作已构建页面，HTTP 数据来自仅监听回环地址的测试专用响应。安装了 Playwright 的环境可运行 `node src/test/browser/content-pages.cjs`；若未在模块搜索路径中，用 `CMS_PLAYWRIGHT_MODULE` 指向已有 Playwright 模块目录，`CMS_CHROMIUM_EXECUTABLE` 可指定本机 Chromium 可执行文件。脚本不安装依赖、不连接用户数据库，测试响应不会进入 WAR。

浏览器结果与截图输出到 output/playwright，保留在本机。测试响应脚本为 src/test/browser/content-pages.cjs；真实后端脚本为 src/test/browser/content-live.cjs，需显式设置回环地址和专用测试凭据。
