# CMS 界面改造 · A 方交接

本批基于已合入主分支的 Quill 2.0.3、公开门户和后台布局，补齐作者筛选、公开分类、图片上传与读取。部署仍使用 JDK 17、传统 Spring MVC、MyBatis、MySQL 和外部 Tomcat 10.1。

## 部署顺序

1. 在目标 CMS 数据库执行一次 [006_article_media.sql](../../sql/006_article_media.sql)。已有库只需执行这次新增的媒体表迁移。新安装按项目的基础建库步骤创建账号和内容表后执行 006。图片元数据通过外键关联上传账号。
2. 为运行 Tomcat 的账号准备一个可读写的持久化目录，例如 Windows 的 `E:/cms-data/images`。在现有外部 CMS 配置中设置 `cms.media.directory=E:/cms-data/images`，或通过服务进程环境设置 `CMS_MEDIA_DIRECTORY`。目录须位于 WAR、静态构建输出及临时目录之外。省略配置时，已有文章业务可启动，媒体接口返回 503。
3. 从 `frontend` 运行 `npm ci`、`npm run build`，回到项目根目录运行 `mvn verify`，部署 `target/enterprise-cms.war`。生产 URL 随 WAR 上下文变化。例如部署为 `cms.war` 时，访问 `/cms/` 进入公开网站，访问 `/cms/content/index.html#articles` 进入后台。开发代理沿用 `VITE_CMS_CONTEXT`、`VITE_CMS_TARGET`。
4. 数据库与图片目录一起备份和还原。应用回退时保留媒体表及文件。文件链接公开可读，首版保留已上传但未插入的图片。

## 接口

下表路径相对于 WAR 上下文。JSON 使用项目既有的 `ApiResponse` 封装。

| 请求 | 权限及结果 |
| --- | --- |
| `GET /api/articles/filter-options/authors?keyword=&page=1&size=20` | 文章读取或审核权限。分页返回 `id`、`displayName`。仅有审核权限时只列出待审队列中的作者。下拉框支持文字检索及继续加载。 |
| `GET /api/public/categories` | 匿名返回有已发布文章的分类，按排序值和 ID 排列。无公开内容时为 `[]`。 |
| `POST /api/media/images` | 文章创建或编辑权限，须带会话与 CSRF 令牌。multipart 字段为 `file`。成功返回 `id`、`url`、`width`、`height` 和字节数 `size`。 |
| `GET /media/images/{id}` | 匿名读取登记的图片，返回实际 MIME 类型和 `X-Content-Type-Options: nosniff`。不存在时返回 404。 |

支持 JPEG、PNG、WebP。每张最多 5 MiB，宽高各最多 8192 像素，总量最多 2000 万像素。正文存储与读取均校验图片属于当前应用上下文，且随机标识在媒体表中存在。仅包含图片的文章仍需补充可见文字。WebP 解码器使用 TwelveMonkeys 3.12.0，许可随 WAR 附在 `WEB-INF/licenses/TwelveMonkeys.txt`。前端依赖许可由 Vite 生成至 `content/THIRD_PARTY_LICENSES.md`。

## 2026-11-26 验收

| 环境 | 检查结果 |
| --- | --- |
| JDK 17，Maven | `mvn verify` 通过，290 项测试通过，生成 WAR。覆盖作者权限范围、分类公开与隐藏、图片解码、上传权限与 CSRF、媒体元数据校验。 |
| Node，Vue | 13 项前端测试通过；TypeScript 类型检查和 Vite 生产构建通过。 |
| MySQL 8.0.44，独立实例 | 新库执行 006 后共有 10 张表；16 项真实数据库专项测试通过。 |
| Tomcat 10.1.60，Chrome，实际 WAR | 通过真实账号登录，以 Quill 粘贴标题、列表、代码和链接，上传 WebP，保存并重新打开草稿，按作者筛选，提交审核和发布。访客可以查看正文与图片，下架后详情返回 404，历史与日志正确。测试数据只写入本轮新建的隔离库。 |
| Chrome 本地界面回归 | 15 项检查通过，含两个上下文、退出与过期、草稿恢复与隔离、390/768/1280/1440 像素下的后台布局。窄屏截图来自桌面 Chrome 调整视口。 |

| 页面 | 桌面 | 390px |
| --- | --- | --- |
| 登录 | [桌面](../verification/ui-a/login-desktop.png) | [窄屏](../verification/ui-a/login-mobile.png) |
| 后台 | [桌面](../verification/ui-a/admin-desktop.png) | [窄屏](../verification/ui-a/admin-mobile.png) |
| 编辑器 | [桌面](../verification/ui-a/editor-desktop.png) | [窄屏](../verification/ui-a/editor-mobile.png) |
| 公开列表 | [桌面](../verification/ui-a/public-desktop.png) | [窄屏](../verification/ui-a/public-mobile.png) |
| 公开详情 | [桌面](../verification/ui-a/detail-desktop.png) | [窄屏](../verification/ui-a/detail-mobile.png) |

登录和后台截图取自回环界面测试；编辑器及两张公开页面取自实际 WAR 和 MySQL。Safari、手机真机键盘输入、触屏滚动与工具栏仍待实机验收。团队部署前请在目标环境完成前述迁移及目录配置。
