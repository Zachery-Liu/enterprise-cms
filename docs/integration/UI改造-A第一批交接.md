# UI 改造 A：第一批基础对接

日期：2026-11-06。基于远端 main@1897425，工作分支 `feat/ui-a`。这里的 A 是 UI 改造负责人，不是历史内容/身份模块分工。

后续进度见 [第二批布局、路由与会话交接](UI改造-A第二批布局与会话.md)。下文保留第一批交付时的边界，App 装配和路由守卫以后续记录为准。

依据用户提供的 PLAN.md 与 UI改造-B1组件与接口契约.md。没有修改 B 所有的 ArticleEditor、PublicReader、SafePreview 或正文 CSS。B 的 B1/B2 实现尚未出现在当前远端 main，待提供分支或文件位置后接入。

## 本批可交给 B 使用

| 项目 | 实际交付 |
| --- | --- |
| Quill | `frontend/package.json` 精确固定 2.0.3，锁文件同步；不安装 Vue 包装库或收费扩展。官方安装依据：https://quilljs.com/docs/installation |
| 许可证 | Quill BSD-3-Clause 原文保存在 `frontend/public/licenses/Quill-LICENSE.txt`，生产构建复制到 content/licenses；已核对源文件与产物 SHA-256 相同 |
| 类型 | PublicArticle 增加后端已有的 categoryId；PublicDetail 继承该字段；新增 PublicCategory、AuthorOption、ImageUploadResult、LeaveGuard |
| 上传封装 | `uploadImage(file, { signal? })` 使用字段 file 的 FormData，自动 CSRF、同源 Cookie；不手动设置 multipart Content-Type |
| 取消与会话 | 取消保留 AbortError；读取响应体期间会话变更返回 StaleResponse；上传与 JSON 写请求均不自动重试 |
| 响应保护 | 校验返回的媒体标识、同源上下文媒体路径、尺寸和大小；非 JSON 的 413 给出大小限制提示并保留响应头请求编号 |
| 组件转发 | ArticleManager 暴露 beforeLeave；没有编辑器返回 true，有 B6 方法则 await 转发，方法未就绪则保守确认是否丢弃输入 |
| 路由辅助 | navigation.ts 提供站点名称、后台返回目标白名单、公开正整数 ID 校验，以及保留 title/categoryId/page 的旧链接转换 |
| 开发代理 | 新增媒体转发；VITE_CMS_TARGET、VITE_CMS_CONTEXT 可配置，默认本机 8080 与 /cms；生产不依赖这些代理 |

## 契约差异与边界

- 交接文档写 `cms:article:update`，现有 Controller、种子与前端均使用 `cms:article:edit`。后续图片权限应沿用 create 或 edit，不能要求尚不存在的 update，也不扩大用户管理权限。
- 本批仅准备上传客户端，`POST /api/media/images` 尚未实现，不能声称图片已经可上传；服务端仍须独立做文件真实性、安全及权限检查。
- 路由辅助与 ArticleManager 转发已准备，但 App 的路由切换、退出、浏览器返回及会话过期草稿保留尚未整体接入，不能声称未保存保护已覆盖完整页面。
- Quill 包已可供 B 导入，但当前 ArticleEditor 仍是原组件，尚未变成富文本编辑器。
- 媒体 URL 在开发模式仍保留后端实际上下文（默认 /cms/media/images/...），代理支持该路径；B 使用响应 url，不自行拼接。

## 实际检查

- Node 24.15.0：`npm test`，11 项通过。覆盖 multipart/CSRF/JSON 兼容、取消、过期响应、容器 413、非法媒体 URL、内部路由白名单、公开 ID 与旧链接参数保留。
- `npm run build`：vue-tsc 类型检查和 Vite 生产构建通过。
- 首次构建缺少 Windows Rolldown 原生可选依赖，按现有锁文件补装 optional 依赖后构建成功；没有删除锁文件或强制添加平台专用生产依赖。
- 本批没有后端代码变更，未运行 Maven、部署 WAR 或写入业务数据库；未做新布局视觉验收。

## 下一批

1. App 三套布局、根入口跳转、完整路由守卫和同账户内存草稿保留。
2. 公开分类与作者选项接口；后台树形分类/远程作者筛选和响应式表格。
3. 媒体表、外置目录、multipart、图片真实性/WebP/权限验证及图片读取。
4. 按 B 的真实 Quill 输出扩展清洗，完成保存往返及桌面/手机视觉验收。

上述为未完成项，本文件不把 A-1 至 A-4 标为全部完成。
