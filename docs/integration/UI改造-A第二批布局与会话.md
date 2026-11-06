# UI 改造 A：第二批布局、路由与会话

日期：2026-11-07。基于第一批 `d78cb55`，继续在 `feat/ui-a` 开发，不改 B 所有的 PublicReader、ArticleEditor、SafePreview 和共享正文 CSS。

## 已实现

- 独立浅色登录页、后台工作台、公开站点外壳。公开外壳只有站点页头、正文挂载区和页脚后台入口，不读取后台会话，不显示后台身份或退出按钮。
- 登录恢复内部后台目标；首次匿名访问不显示会话过期；恢复期间只显示加载。登录页支持密码显隐、回车、防重复提交及错误提示。
- 桌面侧栏、手机抽屉；筛选区/卡片/分页间距、表格局部滚动、长标题省略及原文 title。手机初次挂载默认折叠高级筛选，排序字段与方向保持同组。
- 菜单、退出、hash 和同页浏览器返回/前进经过 ArticleManager.beforeLeave。取消保留 URL、菜单高亮和输入；B6 尚未暴露方法时采取“编辑器打开就提示”的保守回退，完整 dirty 判断和编辑器自身关闭按钮仍由 B6 提供。
- 暂无 B6 方法时补充浏览器关闭/刷新原生提醒；不尝试把异步确认放在卸载之后执行。
- 会话过期后保持文章编辑组件挂载，隐藏其弹窗，锁住业务写请求，仅保留本页内存草稿；原账号重登后继续，另一账号登录前销毁旧编辑组件。不使用 localStorage/sessionStorage 保存正文。
- 写操作失败不自动重试；版本冲突保留输入；退出失败保留后台并显示原因。
- 旧 index.html#public 链接转换到 public.html，并保留 title/categoryId/page。后台返回目标仍只接受 articles/categories/review。
- 新增 PublicEntryController，把上下文根入口重定向到实际 contextPath 下的公开入口，不写死部署名，不转发外部返回目标。

## 验证结果

| 检查 | 实际结果 |
| --- | --- |
| 前端 `npm test` | 13 项通过，包括过期会话业务写入锁与允许重新认证 |
| `npm run build` | TypeScript 类型检查及生产构建通过 |
| JDK 17 `mvn clean verify` | 286 项，失败/错误/跳过均为 0；WAR 构建成功 |
| 根入口与 SSM 专项 | 4 项通过，已包含在全量测试中；入口测试覆盖空上下文、/cms、/another-cms |
| 无头 Chrome 浏览器 | 12 组连续场景通过；未出现 pageerror |
| 窄屏与桌面 | 390/768/1280/1440px 整页无横向溢出；390px 抽屉可切换分类，初次挂载高级筛选折叠 |

浏览器脚本：`src/test/browser/ui-shell.cjs`。测试使用回环 HTTP 夹具与合成账号/文章，不连接或修改业务数据库。覆盖匿名公开页面、旧链接、另一个部署上下文、登录目标、取消菜单/返回/退出、409、会话过期同账号恢复、换账号清除、退出失败及会话恢复加载。菜单及退出在编辑弹窗背后时，测试直接派发组件点击事件以检查守卫；实际浏览器返回通过 history.back 触发。

命令示例（先按本机环境配置工具路径）：

```text
npm --prefix frontend test
npm --prefix frontend run build
node src/test/browser/ui-shell.cjs
mvn clean verify
```

浏览器脚本使用 `CMS_PLAYWRIGHT_MODULE`、`CMS_CHROMIUM_EXECUTABLE` 指定已有 Playwright 和 Chrome。截图及结果在本机 `output/playwright/ui-a/`：login-desktop、login-mobile、admin-desktop、admin-mobile、public-desktop 和 results.json。本轮已查看登录及后台截图检查布局，不代表学生人工验收。完整后端日志在工作区 `work/ui-a-shell-verify.log`。工具、缓存、截图与临时文件均位于 E 盘，测试服务器及无头浏览器在脚本退出时关闭。

## 仍需推进

- B 的 B1/B2 文件尚未取得，不声明双方已联调。公开正文、旧编辑器和预览继续使用当前实现。
- 作者远程选项、树形分类筛选、公开分类接口及图片后端仍未实现。
- B6 完成后需再验收精确 dirty 判断、编辑器关闭按钮、保存后不提示，以及真实 Quill 文本和图片的会话恢复。
- 此次浏览器结果不是真实 MySQL/部署容器的 UI 全流程验收；根入口是 MVC 自动测试，WAR 未部署到业务环境。
- Safari、真实手机浏览器、图片上传、发布到公开阅读的新增富文本往返仍待后续。
