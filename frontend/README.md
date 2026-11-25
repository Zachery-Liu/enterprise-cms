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

`npm test` 用 Node 的 TypeScript 转换运行路由和 API 契约测试，需要支持 `--experimental-transform-types` 的 Node。Quill 2.0.3 的许可原文位于 `public/licenses/Quill-LICENSE.txt`，会随构建产物复制到 `content/licenses/`。Vite 也会收集实际进入构建产物的依赖许可。

从工程根目录运行 `node src/test/browser/ui-shell.cjs`，可以用真实浏览器检查已构建的门户、移动端导航、Quill 离开确认和草稿恢复。脚本使用回环 HTTP 夹具。若 Playwright 未在模块搜索路径中，可用 `CMS_PLAYWRIGHT_MODULE` 指向模块目录，用 `CMS_CHROMIUM_EXECUTABLE` 指定本机 Chromium 可执行文件。

浏览器结果和截图输出到 `output/playwright`。原内容页回归 `content-pages.cjs` 与真实后端脚本 `content-live.cjs` 的编辑器及公开页面选择器仍基于旧版页面。

## UI 改造：公开阅读与富文本

公开入口采用独立阅读布局；正文共享 `src/styles/article-content.css`。文章编辑接入固定版本 Quill 2.0.3，保存仍为语义 HTML，上传使用同源 FormData 与既有 CSRF。

图片交互已经接入约定接口，但真实后端的媒体上传、存储、访问及图片清洗仍待 A 提供。公开分类接口不可用时，页面保留搜索与阅读并提供分类重试。勿把隔离预览当作真实接口完成。

组件/接口与 A 接入差异见 [交接契约](../docs/integration/UI改造-B1组件与接口契约.md)，实际验证与限制见 [B 前端验证记录](../docs/verification/UI改造-B前端验证记录.md)。可用 `node src/test/browser/content-b-fixture.cjs` 从工程根目录启动隔离人工验收服务；它不访问数据库，也不进入 WAR。
