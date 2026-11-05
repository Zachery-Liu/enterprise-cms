# A 内容模块范围与接口契约

版本 V1.1，2026-10-30。对应 A-00.1～A-00.4，当前为设计基线，接口实现状态见任务清单。

## 1 范围与技术约束

实现分类树、分类维护、文章草稿与编辑删除、分页筛选、提交审核、审核通过/退回、发布/下架/重开、状态历史、批量发布、匿名公开查询，以及对应页面、测试和交付材料。

恢复删除文章、正文版本库、定时发布、多站点、动态字段、复杂审批与素材库不纳入本轮 MVP。A 拥有三张内容表和内容业务；B 拥有身份、权限、通用操作日志和公共接口。

应用只采用 Spring Framework、Spring MVC、非 Boot MyBatis-Plus、Maven WAR 和外部 Tomcat。Java 17，MySQL 8.0.16+。不采用 Boot parent、BOM、starter、插件、启动器或测试 API。本轮因用户明确要求，在本地同步替换原公共工程配置；这不表示接管 B 的身份/RBAC/日志业务。

## 2 固定业务规则

### 2.1 分类

- 根节点 `parentId=null`，其他父节点必须存在且未删除。新增、移动和启用时要求目标父节点及其祖先全部启用。
- 名称 strip 后 1～64 字符，同一父级下有效名称不重复，比较使用数据库排序规则；删除后的名称可以复用。排序值为 0～2147483647，同级按 sortOrder、id 升序。
- 查询后台树包括停用节点，排除已删除节点；不接受“仅启用节点”筛选而产生断开的树。文章选择列表可在整树组装后按祖先有效性过滤。
- 不允许移到自身或任意后代；无固定的小层级上限。停用祖先后，其后代的“有效可用”也为 false，但不批量改写子节点状态。
- 停用不自动下架已有文章，历史文章仍可查询；新绑定、提交和新发布要求分类及祖先有效。
- 有未删除子节点或任何未删除文章引用时不能删除；逻辑删除不级联。
- 普通 version 乐观锁不足以阻止两个节点交叉移动成环。分类写事务需按统一顺序执行锁定读、检查树/重名/引用后再更新；文章绑定分类也遵循分类先于文章的锁顺序。并发集成测试是 A-02 的验收条件，不能只测顺序执行。
- 实现补充：有效同级名称由生成列唯一约束兜底，覆盖根分类和删除后复用；分类写采用 READ_COMMITTED 事务锁定现存全部分类，再读取最新快照。后续文章写事务使用 `CategoryService.requireAvailableForWrite` 持有相同分类锁，不在事务外预检后直接写文章。

### 2.2 文章与正文

- 新建由 ArticleService 编排，调用 ArticleLifecycleService 的创建入口：固定 DRAFT、version=0，作者/创建人取真实当前用户；文章和 CREATE 历史在同一事务保存。
- 普通编辑仅接受 title、summary、content、categoryId、version；仅 DRAFT/REJECTED 可编辑，不隐式修改状态。OFFLINE 必须先显式 reopen 成为 DRAFT。
- DRAFT/REJECTED/OFFLINE 可逻辑删除，其他状态拒绝；所有修改/删除携带 version，旧请求返回 409；删除历史保留，不额外引入 DELETED 状态。
- 标题 strip 后 1～200 字符，摘要可空且最多 500；正文清洗前后均不超过 100000 字符，清洗后必须包含可见文本，纯空格、实体空白或格式字符不算可见正文。长度以 Java 字符串长度计，API 和校验保持一致。
- 正文保存前使用 jsoup 1.23.2 的 Cleaner/Safelist 白名单清洗。只允许基础段落、标题、列表、强调、引用和绝对 http/https 链接；移除脚本、事件属性、style、iframe、object、危险协议、相对链接与媒体元素；链接附加 rel=nofollow noopener noreferrer。参考[jsoup 官方清洗说明](https://jsoup.org/cookbook/cleaning-html/safelist-sanitizer)。不用正则解析 HTML，详情展示额外经过同一清洗边界。
- 后台预览与公开展示只显示清洗后的正文。列表不读取正文；公开 VO 不包含内部审核意见或审核人。

### 2.3 状态、权限与历史

| 原状态 | 动作 | 目标状态 | 权限 | 附加行为 |
| --- | --- | --- | --- | --- |
| 无 | CREATE | DRAFT | cms:article:create | 初始状态与 CREATE 历史原子保存 |
| DRAFT / REJECTED | SUBMIT | PENDING | cms:article:submit | 重验正文/分类；清空当前审核人、审核时间和意见，历史意见保留 |
| PENDING | APPROVE | APPROVED | cms:article:audit | 记录当前审核人、UTC 时间和可选意见 |
| PENDING | REJECT | REJECTED | cms:article:audit | 退回原因 strip 后非空，原始输入最多 500 字符 |
| APPROVED | PUBLISH | PUBLISHED | cms:article:publish | 重验分类与正文，写入发布时间 |
| PUBLISHED | OFFLINE | OFFLINE | cms:article:offline | 立即停止公开，保留上次发布时间 |
| OFFLINE | REOPEN | DRAFT | cms:article:edit | 清空当前审核信息与发布时间；历史保留，后续重新审核 |

其他转换均返回 409，重复请求也不返回伪成功。REJECTED 可在内容有效时重新提交，不引入“必须修改过才可提交”的隐含规则。初始创建和每次成功状态变化追加一条历史；普通正文编辑、逻辑删除由通用操作日志记录，不伪造状态转换。

MVP 以有效权限控制操作，拥有审核权限的作者可审核自己的文章，不增加尚未约定的自审禁令或仅本人编辑限制。编辑角色本身不具备审核/发布权限。若后续要求职责分离，须同步权限业务规则、测试和文档。

状态规则类只判断转换和意见格式，不替代 B 权限判断或数据库条件更新。实际写入必须匹配 id、原状态、version、deleted=0，影响行数须为 1；文章和历史同事务提交，任何失败回滚。

批量发布请求包含 1～50 个 `{id,version}`，重复 id 返回 400，不静默去重。批量预检存在性、版本、APPROVED 和分类有效性；按固定 id 顺序锁定/更新，整批状态与历史全成全败。业务冲突响应指出可识别的文章 ID 和原因，不返回 SQL/堆栈。

## 3 通用输入输出

- base URL 包含 WAR 上下文，例如 `/cms`；下表路径不重复写上下文。
- 复用 `ApiResponse<T>(code,message,data,requestId)`，成功 code=OK；空数据使用 null，空列表用 []。
- 分页复用 `PageResponse<T>(records,total,page,size)`；页码从 1 起，默认 page=1、size=20，size≤100。不做内存分页。
- ID 为正 Long；version 为非负 Integer。所有写入携带 Session Cookie 和当前 `X-CSRF-Token`，不接受 X-User-Id 一类伪造身份。
- 未列入 DTO 的字段不参与业务写入；即使 JSON 层忽略未知字段，authorId/status/operatorId 等也无效。
- 时间统一带偏移的 ISO 8601，转为 UTC；范围为 `[from,to)`，from 必须小于 to，无偏移时间返回 400。
- 400 参数错误；401 未登录；403 无权限/CSRF；404 不存在或不可公开；409 状态、版本、重名、引用冲突；500 未预期异常。复用 B 的 BusinessException，不建立平行异常框架。
- 写接口返回更新后的 VO，含最新 version；逻辑删除返回成功且 data=null。

## 4 分类接口

| 方法与路径 | 输入 | data | 权限 / 日志动作 |
| --- | --- | --- | --- |
| GET /api/categories/tree | 可选 rootId，存在时返回含根的子树；不存在返回 404 | CategoryTreeVO[]，空库 [] | cms:category:read / 无 |
| GET /api/categories/{id} | 正 ID | CategoryVO | cms:category:read / 无 |
| POST /api/categories | CategoryCreateRequest | CategoryVO | cms:category:manage / CATEGORY_CREATE |
| PUT /api/categories/{id} | name, version | CategoryVO | cms:category:manage / CATEGORY_UPDATE |
| PATCH /api/categories/{id}/parent | parentId（根为 null）, version | CategoryVO | cms:category:manage / CATEGORY_MOVE |
| PATCH /api/categories/{id}/sort | sortOrder, version | CategoryVO | cms:category:manage / CATEGORY_SORT |
| PATCH /api/categories/{id}/status | status=ENABLED/DISABLED, version | CategoryVO | cms:category:manage / CATEGORY_STATUS |
| DELETE /api/categories/{id}?version=N | version | null | cms:category:manage / CATEGORY_DELETE |

`CategoryCreateRequest={parentId?,name,sortOrder?}`，sortOrder 缺省为 0，初始状态固定 ENABLED。`CategoryVO={id,parentId,name,sortOrder,status,version}`；`CategoryTreeVO` 增加 children（叶子为 []）。

## 5 后台文章接口

| 方法与路径 | 输入 | data | 权限 / 日志动作 |
| --- | --- | --- | --- |
| POST /api/articles | ArticleCreateRequest | ArticleDetailVO | cms:article:create / ARTICLE_CREATE |
| GET /api/articles/{id} | 正 ID | ArticleDetailVO | cms:article:read / 无 |
| PUT /api/articles/{id} | ArticleUpdateRequest | ArticleDetailVO | cms:article:edit / ARTICLE_UPDATE |
| DELETE /api/articles/{id}?version=N | version | null | cms:article:delete / ARTICLE_DELETE |
| GET /api/articles | ArticlePageQuery | PageResponse<ArticleListVO> | cms:article:read / 无 |
| GET /api/articles/review-queue | 审核队列查询 | PageResponse<ArticleListVO> | cms:article:audit / 无 |
| GET /api/articles/{id}/history | page,size | PageResponse<ArticleHistoryVO> | cms:article:read / 无 |

- `ArticleCreateRequest={title,summary?,content,categoryId}`；`ArticleUpdateRequest` 增加 version。
- `ArticlePageQuery={page,size,title?,categoryId?,status?,authorId?,createdFrom?,createdTo?,updatedFrom?,updatedTo?,sort?,direction?}`。
- sort 白名单：createdAt/updatedAt/publishedAt/title；direction 只能 ASC/DESC，默认 updatedAt DESC，同方向 id 为稳定次键；未知字段拒绝。
- 后台分页实现使用统一 MyBatis-Plus 分页插件，默认 page=1、size=20、最大 100；越界页保留总数并返回空 records，不自动跳回首页。标题中的 `%`、`_`、`!` 按字面搜索，不允许客户端构造 LIKE 通配式。
- 创建和更新时间均采用 UTC 的 `[from,to)` 区间，带偏移量输入先转 UTC；分页计数与记录在同一只读 REPEATABLE_READ 事务中查询。列表关联分类名、不加载正文；本页作者 ID 去重后只调用一次 UserReader，空页不调用。
- 审核队列固定 PENDING，支持 title/categoryId/authorId/submittedFrom/submittedTo，默认提交时间 ASC、id ASC。提交时间从当前轮 SUBMIT 历史关联取得，不复用编辑时间；每篇文章仅取最新 SUBMIT，避免联表重复分页。
- `ArticleListVO={id,title,summary,categoryId,categoryName,authorId,authorDisplayName,status,version,createdAt,updatedAt,publishedAt,submittedAt?}`。
- `ArticleDetailVO` 在列表字段上增加 content、reviewerId、reviewerDisplayName、reviewComment、reviewedAt。
- `ArticleHistoryVO={id,articleId,action,fromStatus,toStatus,reason,operatorId,operatorDisplayName,createdAt}`，按 createdAt、id 升序分页。
- 分类关联采用联表或本页批量查询；作者与历史操作者通过 UserReader 一次批量读取，单批≤500；缺失用户显示“未知用户”，停用用户仍展示历史姓名，不循环补查。

## 6 生命周期接口

本表为本轮唯一正式路径，取代旧设计中 submission/approval/publication 等候选路径；不同时维护两组别名。

| 方法与路径 | JSON | 权限 / 日志动作 |
| --- | --- | --- |
| POST /api/articles/{id}/submit | `{version}` | cms:article:submit / ARTICLE_SUBMIT |
| POST /api/articles/{id}/approve | `{version,comment?}` | cms:article:audit / ARTICLE_APPROVE |
| POST /api/articles/{id}/reject | `{version,comment}` | cms:article:audit / ARTICLE_REJECT |
| POST /api/articles/{id}/publish | `{version}` | cms:article:publish / ARTICLE_PUBLISH |
| POST /api/articles/{id}/offline | `{version}` | cms:article:offline / ARTICLE_OFFLINE |
| POST /api/articles/{id}/reopen | `{version}` | cms:article:edit / ARTICLE_REOPEN |
| POST /api/articles/batch-publish | `{items:[{id,version},...]}` | cms:article:publish / ARTICLE_BATCH_PUBLISH |

单篇返回 `{id,status,version,updatedAt}`；批量成功返回 `{count,items:[{id,status,version,updatedAt},...]}`，按请求顺序返回；失败返回统一错误，data 可提供 `{failures:[{id,code,message}]}`，不能暗示部分已提交。

历史实现进度（2026-10-29，当前状态见下文 2026-10-30 更新）：单篇动作已由 ArticleLifecycleService.transition 统一实现；action 是应用入口选择的枚举，不是可由通用编辑 DTO 写入的目标状态。后台历史、待审队列、公开查询及批量发布 Service 已实现。PublicationBatchService 委托生命周期服务的单个事务，文章一次批量读取并锁定，结果保持请求顺序；BatchPublicationException.getFailures 提供结构化失败信息，消息包含首个失败 ID 和安全原因。data.failures 的 HTTP 适配仍待统一接口接入。所有上述 HTTP 路由均待 B 权限、日志及匿名声明接入后开放，不将 Service 测试当作接口越权验收。

## 7 公开查询

| 方法与路径 | 输入 | data |
| --- | --- | --- |
| GET /api/public/articles | page,size,title?,categoryId? | PageResponse<PublicArticleListVO> |
| GET /api/public/articles/{id} | 正 ID | PublicArticleDetailVO |

公开接口使用 B 的 com.zachery.cms.security.annotation.AnonymousAccess 声明，无需登录。SQL 固定 status=PUBLISHED AND deleted=0，不接收客户端 status/authorId/internalSort。默认 publishedAt DESC、id DESC。

`PublicArticleListVO={id,title,summary,categoryId,categoryName,authorDisplayName,publishedAt}`；详情额外包含清洗后的 content，不含 version、审核意见、内部用户 ID 和审计字段。非发布或已删除文章统一 404。分类停用不自动隐藏已经发布的文章；显式下架后才停止公开。

## 8 B 公共能力核对与交接

2026-10-30 已从 B 的 feat/operation-log-query@a65db1a 建立隔离工作树 runtime/a-content-ssm，实际复用了下列代码；原目录中未提交的认证替代实现未带入。

| 能力 | 约定完整类名 | 本地情况 / A 接入要求 |
| --- | --- | --- |
| 当前身份 | com.zachery.cms.common.context.CurrentUserContext | 接口和 Session 实现存在，A 调用 getRequiredUser，不读取 B Mapper |
| 身份模型 | com.zachery.cms.common.context.CurrentUser | userId、username、displayName、status、roleCodes、permissionCodes 存在 |
| 批量用户 | com.zachery.cms.modules.identity.user.service.UserReader / UserSummary | 本地存在；通过 findByIds 读取本页作者摘要 |
| 统一响应/分页 | com.zachery.cms.common.api.ApiResponse / PageResponse | 本地存在，分页字段为 records/total/page/size |
| 统一异常 | com.zachery.cms.common.exception.BusinessException | 本地存在，构造参数 HttpStatus、code、message |
| 权限 AOP | com.zachery.cms.security.annotation.RequirePermission | 已复用 B-14 的 MVC 子容器权限切面；控制器入口统一声明权限 |
| 操作日志 | com.zachery.cms.logging.annotation.OperationLog | 已复用 B-15，写成功与事务一致，回滚后不留虚假成功日志 |
| 匿名访问声明 | com.zachery.cms.security.annotation.AnonymousAccess | 已复用 B，公开 GET 通过匿名网络 HTTP 验证 |

日志 module 使用 CATEGORY 或 ARTICLE，targetType 使用 CATEGORY/ARTICLE；创建成功取返回 VO 的 id，其他动作取路径 id。B 当前注解只支持单个 Long targetId，批量操作暂使用空 targetId；状态历史逐篇记录。目标集合摘要仍需 B 扩展后确认，不以不存在的批次表或伪造目标 ID 代替。不记录正文、Cookie 或完整请求体。

## 9 本轮已作决策与待验证项

已明确：OFFLINE 显式重开；同级分类不重名；无固定小层级上限；批量重复 ID 拒绝；基础权限为角色能力，不增加隐含作者范围；恢复为扩展；保存前清洗富文本。

批量失败提供 data.failures；登录权限由 auth/me 读取，后台和公开路由遵守本契约的权限与状态约束。
