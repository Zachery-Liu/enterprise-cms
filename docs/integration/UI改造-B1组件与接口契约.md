# UI 改造：B1 组件与接口契约

状态：B 方交接基线已整理，待 A 方对接确认；本文件不表示新增接口已实现。

本轮 B1/B2 只提交契约、共享正文样式及预览接入，不安装 Quill、不修改后端、不提前实现整站页面改造。这里的 A/B 是本次 UI 改造分工，与历史文档中的内容模块/身份模块 A/B 命名无关。

## 给 A 的阅读入口

本文件是本次 B → A 的完整交接入口，无需另外寻找聊天记录。先阅读第 8 节的 A 执行清单，再按第 2–5 节实现契约，最后按第 9 节回交结果。文件中的“待实现”是计划，不代表代码已经可调用。

B 已完成：本文档、共享正文 CSS、SafePreview 引用共享 CSS。B 尚未完成：公开页面改版、Quill、图片交互、beforeLeave 暴露方法。A 应保留当前组件接口并按功能就绪程度接入，不能提前假定后续能力存在。

## 1. 所有权与装配

| A 负责 | B 负责 |
| --- | --- |
| `App.vue`、路由、会话、后台布局、列表和分类管理 | `PublicReader.vue`、`ArticleEditor.vue`、`SafePreview.vue` |
| `api.ts`、`models.ts`、全局样式、依赖与锁文件、后端与迁移 | 新增富文本组件、`src/styles/article-content.css`、公开网站局部样式 |

- A 保留 Element Plus 中文配置，根组件在登录、后台、公开三套布局间切换。
- A 的公开布局提供站点页头、页脚和后台入口；B 的 PublicReader 只提供列表/详情主体，避免重复页头。
- 站点名称集中配置，默认“内容中心”；A 提供给公开布局，B 不重复写死。
- 正式页面的宽度和页面边距由 B 的阅读容器负责，正文 CSS 不负责外围布局。
- A 负责添加并固定 Quill 2 稳定版依赖，B 使用原生 Quill API，不引入第三方 Vue 包装库。

## 2. 路由及组件边界

所有地址均相对应用上下文解析，不写死 `/cms` 或本地端口。

| 地址 | 约定 |
| --- | --- |
| 上下文根路径 | A 跳转 `content/public.html#public` |
| `content/public.html#public` | B 的文章列表 |
| `content/public.html#public/{id}` | B 的文章详情，id 为正整数 |
| `content/index.html#login` | A 的独立登录页 |
| `content/index.html#articles`、`#categories`、`#review` | A 的后台 |
| 旧 `content/index.html#public...` | A 保留公开链接的兼容跳转 |

- PublicReader 保持 `props: { route: string }`，值为不带 `#` 的 `public` 或 `public/{id}`，不读取或恢复后台会话。
- 列表状态使用公开入口的 query 参数 `title`、`categoryId`、`page`，hash 仍只表示列表/详情。B 用 URL/历史记录维护它们；打开详情和返回列表保留参数。A 的兼容跳转也须保留这些参数。
- ArticleEditor 保持 `props: { article?: Detail }` 和 `close`、`saved` 事件；B 内部新增 Quill 组件，不改变列表的挂载约定。
- 富文本组件契约：`modelValue: string`、`disabled?: boolean`；发出 `update:modelValue(html)`、`uploading-change(boolean)`。父组件根据上传状态阻止保存。
- ArticleEditor 暴露 `beforeLeave(): Promise<boolean>`。A 的后台路由切换、退出和组件卸载流程必须先调用；返回 false 不切换。B 负责关闭按钮和浏览器离开提醒。保存成功后清除 dirty 状态，不再提醒。
- 未保存检查覆盖标题、摘要、分类及正文；接收初始 HTML 的规范化不算用户修改。会话失效时只保留当前页面内存中的草稿，不写入持久化存储，也不跨账号自动恢复。
- SafePreview 保持 `props: { content: string }`，保留无权限 sandbox、no-referrer 和禁止脚本的 CSP。不能把它当 HTML 清洗器。

## 3. 数据与 API 契约

使用现有 `ApiResponse<T>` 封装、`ApiError` 错误处理、同源 Cookie、CSRF 和过期响应防护。组件不重复实现认证请求。以下接口路径相对现有 API base。

| 能力 | 当前状态 | 对接约定 |
| --- | --- | --- |
| `GET public/articles` | 已有 | `title?`、`categoryId?`、`page`、`size=10`；返回 `Page<PublicArticle>` |
| `GET public/articles/{id}` | 已有 | 返回 `PublicDetail`；404 显示不存在或停止公开 |
| `GET public/categories` | 待 A 实现 | 匿名返回 `{ id: number, name: string }[]`；仅包含有已发布文章的分类，按分类顺序排列 |
| 文章创建与修改 | 已有 | 继续使用 title、summary、categoryId、content；修改携带 version，不新增 Delta 字段 |
| `POST media/images` | 待 A 实现 | multipart 字段 `file`；成功返回下文 ImageUploadResult |

```ts
interface ImageUploadResult {
  id: string; // 媒体随机标识，非文章数值 ID
  url: string; // 同源、以 / 开头、含部署上下文的媒体路径
  width: number;
  height: number;
  size: number; // 字节
}

// A 在 api.ts 提供；组件不手工设置 multipart Content-Type。
uploadImage(file: File, options?: { signal?: AbortSignal }): Promise<ImageUploadResult>;
```

- A 的上传封装负责 CSRF、响应封装校验、401/403、请求编号和会话 epoch；取消、卸载或旧会话响应不得插入图片。
- 首版提供上传中/成功/失败状态，不要求百分比进度。
- 单张不超过 5 MiB；JPEG、PNG、WebP；宽高各不超过 8192px、像素总数不超过 2000 万。前端检查用于反馈，后端必须独立校验。
- 上传图片是可通过公开链接访问的资源；B 在上传入口说明。A 返回同源媒体地址，B 不构造或猜测文件地址。
- A 在 `models.ts` 补齐 PublicArticle/PublicDetail 已由后端返回的 `categoryId: number` 及图片、分类类型。现有 Article/Detail/Page 字段含义不变。
- 公开分类请求失败不阻止现有文章列表和搜索使用，分类区域单独提供重试。

## 4. HTML 与安全边界

- 首版工具：h1–h3、段落、strong/em/u、有序/无序列表、blockquote、pre/code、HTTP(S) 链接、图片、清除格式和历史操作。不提供颜色、任意字号、表格、视频和自由布局。
- Quill 内部可使用 Delta，但 API 和数据库仍存 HTML。B 导出语义 HTML；A 清洗规则与此契约配套。
- 持久化 HTML 使用普通 `ul/ol/li`、`pre/code`，不依赖 `.ql-*`、任意 style 或 data 属性。B 在 B5 规范化导出并验证嵌套列表；不把原始 Quill 编辑 DOM 直接保存。
- 图片只允许 A 的媒体 URL，以及安全 alt/width/height；拒绝外部图片、Base64、脚本与事件属性。A 保留链接安全属性，并以服务端清洗为最终准则。
- 正文清洗前后最多 100000 字符，且必须有可见文字；仅空段落、空格或图片不满足要求。标题 200、摘要 500 字符限制不变。
- B5 必须检查旧 HTML 导入/导出是否丢失内容；无法保留时提示并阻止静默覆盖。B1/B2 不改写已有文章。
- B8 才在图片接口与校验完成后调整预览 CSP，精确允许系统图片；B2 不提前开放网络加载。
- 正式正文只渲染服务端清洗后的 HTML；未保存预览继续使用隔离 iframe。

## 5. B2 样式使用契约

- 唯一正文样式源：`frontend/src/styles/article-content.css`，所有选择器限定在 `.cms-article-content` 内。
- SafePreview 以 `?inline` 导入 CSS，写入 iframe 自身样式；正文容器加相同 class。样式不受外层后台主题影响。
- B4 的正式阅读组件正常 import 同一 CSS，并把清洗后的 HTML 放进 `.cms-article-content` 容器。
- CSS 只定义阅读排版，不设置正文固定高度。正式阅读栏在 B4 设置约 780px 最大宽度；预览现有 380px 滚动区域暂时保留。
- 图片自适应宽度并保持比例；代码块自身横向滚动；长链接换行；嵌套列表、中文、粗体和引用拥有一致间距。
- B2 只准备图片样式，不表示现阶段服务端或预览已经支持图片。

## 6. 交接验收点

- A 确认路由/布局装配、beforeLeave 调用、上传函数及类型契约；尚未确认时不把文档标记为双方已联调。
- B2：类型检查与生产构建通过；桌面/窄屏检查正文排版、长链接、嵌套列表和长代码；确认不污染后台控件。
- B5/B8：验证“导入旧文 → 编辑 → HTML 导出 → 后端清洗 → 再读取”不丢失支持格式，非法链接/脚本/图片仍被拒绝。
- B3/B4/B7 后续各自负责完整页面、图片功能及相应验证，本轮不扩大到这些任务。

## 7. 本轮验证记录（2026-11-05）

- 已通过 `vue-tsc --noEmit` 和 Vite 生产构建；环境 PATH 未提供 npm，使用本机已有 Node 直接运行项目对应 CLI，未安装依赖、未改动锁文件。
- 使用临时本机检查页加载相同 CSS，并模拟 SafePreview 的 srcdoc、sandbox、CSP 和正文容器；桌面及 390px 窄屏下检查中文、标题、强调、嵌套列表、引用、长链接和代码块。
- 390px 下正文宽度/滚动宽度均为 311px，长代码在自身区域滚动（容器 309px、内容 1187px），没有撑宽正文。
- 本轮未修改或发布业务文章，未部署 WAR；图片、Quill 和接口往返验证留给后续对应任务。

## 8. A 方一次性执行清单

### A-1：依赖、挂载和样式边界（先提供）

- [ ] 在 `package.json` 固定 Quill 2 稳定版本并提交对应 lockfile，保留构建产物中的开源许可证；不安装收费扩展、云服务或第三方 Vue 包装库。把实际版本写到第 9 节。
- [ ] 保留 PublicReader 的 `route` prop 和 ArticleEditor 的 `article`、`close`、`saved` 接口。ArticleManager 继续在保存成功后刷新列表并关闭编辑器，失败不主动卸载编辑器。
- [ ] App → ArticleManager → ArticleEditor 建立组件 ref 转发：ArticleManager 也暴露 `beforeLeave(): Promise<boolean>`，没有打开编辑器时返回 true。A 不直接跨层访问 ArticleEditor 内部表单。
- [ ] 路由切换和退出在执行前 await beforeLeave，用户取消则保留页面、URL、会话和编辑内容。浏览器 hash/back/forward 同样接入；已发生 URL 变化时恢复当前 URL，不能只拦菜单点击。初版 A 可先可选调用，B6 方法就绪后必须补齐验收。
- [ ] 会话过期时不要立即卸载正在编辑的组件导致草稿丢失：阻止继续写入，保留原账户内存草稿，完成登录后仅在账户相同的情况下恢复；切换账户不得显示上一账户草稿。不延长或绕过服务端会话。
- [ ] A 的公开外壳不额外套固定宽度卡片、不重复显示“CMS / 公开内容”或搜索工具栏，正文主体宽度由 B 设置。
- [ ] 清理旧 `.reading-card`、`.reading-item`、`.metadata` 等公开样式时与 B 同步；B3/B4 切换完成前不提前删除仍被使用的规则。保留 `.preview-frame` 给编辑预览使用，正式正文将在 B4 脱离 iframe。

### A-2：公开数据与共享类型（可与 A-1 并行）

- [ ] 补齐 `PublicArticle.categoryId`；保留 ISO 时间字符串，不在接口里返回本地化日期。B 继续在展示端格式化。
- [ ] 增加并导出 `PublicCategory { id: number; name: string }` 与第 3 节的 `ImageUploadResult`。
- [ ] 实现公开分类接口；只按公开可读文章集合取分类，不要求登录，也不返回分类后台状态或作者账户信息。
- [ ] 现有公开列表/详情继续限制为已发布且未删除的文章；无效 ID、不存在和已下架保持一致的公开错误处理，不泄露草稿。
- [ ] 开发者 A 自己负责的作者筛选接口与 B 无耦合，不应阻塞公开页面或编辑器开发。

### A-3：图片后端与上传封装（B7 前提供）

- [ ] 新增媒体元数据迁移和外置持久化目录配置，文件不能放进前端构建输出目录（Vite 会清空该目录）或临时目录。
- [ ] 启用 Spring MVC multipart 解析和容器请求大小限制；略大于 5 MiB 的传输上限用于容纳 multipart 开销，业务文件大小仍严格限制为 5 MiB。超限需返回可识别错误，避免只得到容器 HTML 页面。
- [ ] 上传需要 `cms:article:create` 或 `cms:article:edit` 至少一个权限，并执行现有 CSRF 校验；不新增用户管理读取权限要求。
- [ ] 文件真实性、实际解码、尺寸与像素限制由服务端检查。Java 运行时必须验证 WebP 解码能力，必要时增加开源解码依赖并保留许可；不能只有前端 accept 声称支持 WebP、后端实际无法验证。
- [ ] 图片标识使用随机字符串；返回路径为 `<contextPath>/media/images/{id}`，GET 使用服务端元数据指定的媒体类型和 `nosniff`，禁止目录浏览和任意磁盘路径读取。
- [ ] `uploadImage` 必须使用 FormData，不走当前会把 body JSON.stringify 的分支；不要手工设置 multipart Content-Type。可扩展现有 request 内部实现，但保持既有 JSON 调用兼容。
- [ ] 取消上传使用标准 AbortError；会话 epoch 变化使用现有 StaleResponse；两者都不能插入迟到的上传结果。HTTP 失败使用 ApiError，保留状态码和请求编号。413、415、400、401、403 和网络错误提供清晰反馈，不自动重试写操作。
- [ ] 开发代理除 `/api` 外增加 `/media` 映射并重写应用上下文，保证开发环境可显示上传后的图片；生产仍同源访问，不引入 CDN。
- [ ] 上传返回的是公开 URL；不允许私密附件上传。未引用图片暂不自动清理，首版不实现删除接口。

### A-4：清洗规则与 B8 联调（发布功能前完成）

- [ ] 按第 4 节支持普通语义 HTML，保留安全图片；校验图片路径属于当前部署的媒体地址且对应有效元数据，不能只用字符串前缀放行。
- [ ] 不通过开放任意 style/class/data 属性来保留 Quill 格式；先与 B 用真实导出样本确认列表、缩进和代码块结果。
- [ ] 继续校验清洗前后 HTML 长度及可见文字；对 script、onerror、javascript:、data:、外部图片和路径绕过增加回归覆盖。
- [ ] 本轮 SafePreview CSP 未开放图片。到 B8 时由 B 处理 CSP 和图片地址解析：sandbox 无 allow-same-origin，不能假设 `'self'` 或根相对地址在不透明源中自然满足需求；使用明确的同源媒体绝对地址和受限资源策略验证，不增加脚本权限。

## 9. A 回交格式与共同联调清单

A 完成对应阶段后，在本节补充实际结果或新增链接，避免口头称“已完成”而 B 无法对接。

| 回交项 | A 需填写 |
| --- | --- |
| Quill | 实际固定版本、依赖/锁文件变更位置、构建结果 |
| 入口与路由 | 可访问的公开/后台地址、部署上下文、旧链接跳转结果 |
| 组件装配 | beforeLeave 转发位置、会话过期草稿保留方式、尚待 B6 接通的项 |
| 公开分类 | 成功/空列表样例、匿名请求验证结果 |
| 上传 | 实际 multipart 字段和响应样例、失败样例、权限/CSRF/大小/类型验证结果 |
| 媒体部署 | 配置键、持久化目录、迁移脚本位置、WebP 验证和开发代理结果 |
| HTML 清洗 | 支持标签/属性、真实 Quill 样本往返结果、安全回归结果 |
| 集成状态 | 提交标识或变更文件、检查命令与结果、未完成项 |

共同验收顺序：

1. 未登录公开列表 → 分类筛选 → 详情 → 返回列表，确认筛选和分页恢复。
2. 后台新建文章 → Quill 输入中文/列表/代码 → 图片上传 → 预览 → 保存。
3. 重新打开文章 → 核对文字、格式和图片 → 提交审核 → 发布 → 访客阅读。
4. 编辑未保存时切换菜单、浏览器返回及退出，确认取消后内容仍在。
5. 上传中取消、会话过期、切换账户、网络失败和版本冲突，确认不丢失原账户内存输入、不向其他账户展示、不插入迟到图片、不重复提交。
6. 检查 `/cms` 及另一个部署上下文下的根入口、API、图片和旧公开链接。

B3/B4 可先根据现有公开接口开发；分类接口未就绪时清晰显示局部不可用，不伪造数据。B5 依赖 Quill 安装，B7 依赖上传接口，B8 依赖后端清洗规则。A 无需等待 B 的整站界面完成才交付依赖。


## 10. B 后续实现与 A 接入差异（2026-11-05 更新）

本节优先于前文的阶段状态描述；前面的接口约定仍有效。

### B 已落地

- PublicReader 的公开列表/详情、查询条件 URL 保留、独立阅读排版、错误与重试。
- 新增 PublicSiteLayout、site.ts，默认“内容中心”；App 已做最小装配，公开分支不再显示后台身份和页头。A 可继续重构后台与登录布局，保留这个公开分支或等价装配。
- Quill 2.0.3 已安装并锁定；免费本地包，不依赖 CDN 或云服务，生产构建会生成许可证说明。A 无需重复安装。
- RichTextEditor、HTML 规范化/校验、编辑保存保护、只在内存中按账户保存草稿、图片选择/粘贴/拖入及失败重试。
- ArticleEditor 已 expose beforeLeave/discard，ArticleManager 已转发；App 已接通菜单/hash 与退出拦截。A 继续调整路由时须保留拦截，不能绕开直接卸载编辑器。
- api.ts 仅增加 signal 和 FormData 支持，原 JSON 调用不变；图片类型和 uploadImage 暂放 `src/content/uploads.ts`，避免大幅修改 A 的 API 模块。A 可保持此位置，不必重复实现同名函数。
- models.ts 已补 PublicCategory、PublicArticle.categoryId。
- SafePreview 已接通同源媒体绝对地址与路径限制 CSP；继续使用空 sandbox，不允许脚本。

### A 仍需实现的后端依赖

- `GET /api/public/categories`。
- `POST /api/media/images`、`GET /media/images/{id}`、媒体表迁移、持久化存储和开发代理。
- HTML 清洗允许合法系统图片，核对 Quill 语义 HTML 往返。
- 根入口重定向、独立登录页、后台布局与筛选改造仍属于 A，不在 B 本次交付中。

当前真实后端没有上述图片能力，不能把隔离测试成功当作真实上传已可用。图片请求会显示后端错误，公开分类不可用时保留搜索/阅读。B 的接口功能、图片预览和保存回显使用独立回环测试服务验证，未修改业务文章、未部署 WAR。

### 对接注意

- 草稿只在页面进程内按 userId 保存，同账户重新登录后后台会重新打开；刷新/关闭页面只提供浏览器离开提醒，不承诺恢复。退出成功或明确丢弃后清除该账户草稿。
- A 的上传权限应使用项目实际存在的 `cms:article:edit`，不是 `cms:article:update`。
- 图片可在一篇文章中连续插入多张；当前单次选择一张，不限制文章只能有一张图片。
- 原 `content-pages.cjs` 的 textarea 定位与旧公开页面断言不再适用。新增 `content-b-fixture.cjs` 为人工浏览器验收服务，不连接真实数据库，也不打包进 WAR；不可声称旧浏览器脚本已通过。

B 的实际交付、逐项验证和待联调限制已集中在 [B 前端验证记录](../verification/UI改造-B前端验证记录.md)。本次只覆盖 B 前端及必要装配，不能据此标记真实媒体后端或完整发布流程验收完成。

公开首页后续按用户要求改为 WordPress 博客式布局：PublicSiteLayout 负责刊头和导航，PublicReader 负责文章流及侧栏。A 装配时应保留完整公开布局，不在外层再次增加后台页头或卡片。样式与验证见 B 前端验证记录末尾。
