# A 重启开发与 B 迁移 SSM 实施计划

编制日期：2026-10-08。

本计划按老师禁止 Spring Boot、保留 Spring 与 Spring MVC 的约束编制。按课程现有要求保留 MyBatis-Plus、MySQL、参数校验和 Spring AOP。A 旧实现不作为新开发基础；本文不执行代码删除、分支重置或合并。

执行更新（2026-10-09）：B 已从 `dff0cca` 完成传统 SSM 迁移，代码提交 `420fa8f` 位于 `feat/security-ssm`。B-M01～B-M05 验证通过；B-M06 部署、配置和接入文档已准备，仍待 A-M00 联合验收。下文保留最初计划语义，实际结果以[迁移验证记录](../docs/verification/BM01-BM06传统SSM迁移验证记录.md)为准。B-14/B-15 尚未完成。

方案已落实到[A/B 任务分工](../docs/A与B模块任务分工.md)、[A 逐项任务清单](../docs/tasks/A模块逐项任务清单.md)与[B 逐项任务清单](../docs/tasks/B模块逐项任务清单.md)。执行及勾选进度以这些任务文件为准。

## 1 远端核实结果

已执行 `git fetch origin`，核查远端代码和任务记录：

| 分支 | 最新提交 | 实际情况 |
| --- | --- | --- |
| `origin/feature/security` | `dff0cca` | 已实现统一响应、异常、校验、请求标识、注册、Session 登录退出、当前用户、批量用户读取、角色权限查询及事务分配 |
| `origin/feature/content` | `dde44fb` | 当前仅有需求文档，没有 A 的 Java 业务实现 |

B 的“已完成”应理解为已提交部分功能，不能视为 B 全部交付完成。B-11 用户管理、B-14 权限 AOP、B-15/B-16 操作日志、B-17 优化证据、B-18～B-20 联调交付仍需继续推进；部分已实现任务还待 MySQL 8.0、ApiFox 和 A 接入验收。

远端 `PermissionQueryGuard` 明确是 B-14 完成前的临时保护。已有 `AnonymousAccess`，尚无实际 `RequirePermission`、`OperationLog` 注解和切面实现。

本地当前位于 `feature/security`，含多处未提交及未跟踪文件，且本地认证实现结构与远端不同。新计划以远端为依据，现有本地文件必须先归档或隔离，不能直接混入新基线。

## 2 责任调整

- B 主维护公共工程迁移：POM、容器启动、MVC、持久层、事务、会话、公共测试和构建部署，并继续完成身份权限、AOP 和日志任务。
- A 主维护内容领域重建：分类、文章、审核发布、状态历史、内容 SQL、内容接口、页面、测试和优化证据。
- A 与 B 共同确认版本、公共契约、权限编码和部署路径；由 A 验收公共能力对内容模块的实际支持。
- A 与 B 在同一个 WAR 应用中协作，不建立两个独立后端。

建议基线为 JDK 17、Spring 6.x、Tomcat 10.1、兼容 Spring 6 的非 Boot MyBatis-Plus 集成、MySQL 8.0。实现时锁定经过验证的具体版本。若老师指定 Spring 5/Tomcat 9，则统一改用对应 javax 体系，额外安排 Servlet 和 Validation 迁移。

Spring 允许 Java 配置。老师若明确要求 XML，再采用 `web.xml` 和 Spring XML；不要仅因禁用 Boot 就增加 XML 重写范围。

## 3 B 的新增迁移任务

保留原 B-01～B-20 编号，新增 B-M01～B-M06，区分基础迁移与尚未完成的业务。

### B-M01 依赖和 WAR 构建

- 删除 Boot parent、全部 Boot starter、Boot Maven 插件及 Boot BOM；检查传递依赖。
- 非 Boot 方式引入 MyBatis-Plus 和兼容版本的 MyBatis-Spring。
- 显式管理 Spring MVC、context、jdbc、tx、aop、AspectJ、Jackson、Java 时间模块、Validation 实现、数据库连接池、MySQL 驱动、日志及测试依赖。
- Servlet API 使用 provided；设置 WAR 打包及编译、测试、WAR 插件版本。
- 删除 `scripts/build.ps1` 的 E 盘硬编码，改为基于项目目录构建；更新交付命令。

验收：依赖树无 `org.springframework.boot`，普通 Maven 构建可生成 WAR，构建不依赖特定盘符。

### B-M02 Spring 与 MVC 启动

- 用传统 Servlet 初始化方式替换 `CmsApplication`；不使用 `SpringBootServletInitializer`。
- 根容器负责 Service、Mapper、事务和数据源；MVC 子容器负责 Controller、异常处理和 MVC 基础设施。
- 限定组件扫描，避免重复创建业务 Bean。拦截器配置在 MVC 容器生效。
- 提供 A 的 content 包扫描扩展约定。
- 根据权限切面的实际作用位置，在相应容器启用 AOP，不能只在根容器启用后假定 Controller 也被代理。

验收：WAR 在外部 Tomcat 启动，注册、登录、当前用户及角色接口可访问。

### B-M03 数据源、MyBatis-Plus 和事务

- 显式配置 DataSource、`MybatisSqlSessionFactoryBean`、Mapper 扫描、Mapper XML、事务管理器和事务启用。
- 保留已有 XML 和实体，显式设置驼峰映射、逻辑删除、插件及 MetaObjectHandler。
- 保留乐观锁插件；为 A 的数据库分页准备兼容的分页插件和所需解析器依赖，统一维护一套插件链。
- XML 搜索范围同时支持 identity 与未来 content 目录。
- 移除 `IdentityPersistenceConfiguration` 中的 `ConditionalOnMissingBean`。生产环境显式使用 `SessionAuditActorProvider`；测试环境按需提供测试实现，避免出现多个同类型 Bean。
- 回归注册和 RBAC 分配事务，包括失败回滚与锁相关场景。

验收：CRUD、自动填充、逻辑删除、乐观锁与事务行为通过；A 的 Mapper 不会因扫描或 XML 路径遗漏而失效。

### B-M04 MVC、配置、认证与会话

- 将 `application.yml` 中配置改为可显式加载的配置，例如 properties；保留环境变量传入数据库凭证与默认角色设置。
- 配置 Jackson Java 时间序列化、UTC 约定、Bean Validation 和 Service 方法校验；保留现有异常响应格式。
- 显式注册 `RequestIdFilter`，覆盖正常请求及所需异步、错误分派，保留 MDC 清理。
- 保留远端 `SessionAuthenticationInterceptor` 和 `CsrfInterceptor`，维持认证 order=0、CSRF order=10 的约定；不要照搬本地另一套 CsrfFilter。
- 显式配置 Session 超时、Cookie HttpOnly、Secure、SameSite、路径及 Cookie 跟踪。
- 验证 WAR 上下文路径，统一 ApiFox base URL。A 的公开文章 GET 接口按现有机制声明 `AnonymousAccess`，后台写接口继续经过认证与 CSRF。

验收：登录会话轮换、退出后旧会话失效、停用账号拒绝、CSRF、400/401/403 和 requestId 均符合原契约。

### B-M05 迁移测试

- 将 `SpringBootTest`、`WebMvcTest`、`AutoConfigureMockMvc`、`LocalServerPort`、Boot `TestConfiguration` 替换为普通 Spring Test 配置。
- 配置 JUnit 5 Spring 扩展、上下文、Web 测试上下文、MockMvc、测试数据源与 schema 初始化。
- 迁移 `CommonWebContractTest`、`IdentityMapperIntegrationTest`、`RegistrationIntegrationTest`、`SessionIntegrationTest`、`CurrentUserIntegrationTest`、`RbacQueryIntegrationTest`、`RbacAssignmentIntegrationTest`。
- 保留密码、校验与请求标识等单元测试。
- 对真实 Cookie、会话轮换及退出复用，增加外部 Tomcat 的真实 HTTP 验证，不能仅用 MockMvc 代替。

验收：保留原有测试场景和断言含义；记录实际运行结果，不直接复用 Boot 环境的通过结论。

### B-M06 公共交付与剩余任务

- 更新公共契约、部署文档及迁移验证记录，原 Boot 验证记录保留为历史。
- 提供可部署 WAR、配置示例、接口集合、演示账号准备方式和准确的能力完成清单。
- 优先完成 B-14 权限 AOP，并用 A 的分类接口验证代理确实生效。
- 随后完成 B-15 日志注解与持久化，和 A 的写操作验证事务语义；成功操作日志与文章状态历史是两种不同记录。
- 继续用户管理、日志查询、查询优化和最终交付任务。

迁移第一阶段退出条件：B-M01～B-M05 通过，身份和公共能力可在无 Boot 的外部 Tomcat 环境中使用。完整联调退出条件还必须包含 B-14/B-15。

## 4 A 的新版实现计划

保留原 A-00～A-07 业务编号，新增 A-M00 接入任务及 A-08/A-09 联调交付任务。

| 任务 | 修改后工作范围 | 依赖与验收 |
| --- | --- | --- |
| A-M00 接入新基线 | 从 B 迁移验收通过的提交建立新的 A 开发分支；确认配置、公共类、包扫描和内容 Mapper 扩展方式；不带入旧 A 实现或本地认证替代实现 | 本机外部 Tomcat 可使用 B 的登录和当前用户接口 |
| A-00 重做内容领域设计 | 固定三类对象、六种状态、合法转换、接口、DTO、权限及日志目标；按远端 B 契约设计 | 可以在 B 迁移期间完成；权限和日志未实现时只标记依赖，不伪称已接入 |
| A-01 重做并验证数据库脚本 | 设计或复核三张内容表；复核用户 ID、外键和审计字段；处理旧 `chk_category_not_self_parent` 的已知建表阻塞 | 先创建 B 身份表，再创建内容表；在目标 MySQL 空库实际执行；自指及循环关系由业务校验等可行机制保证 |
| A-02 分类与持久层 | 创建内容 Entity、Mapper、XML、Service；树查询、新增编辑、移动排序、启停删除；阻止自身及后代循环和引用删除 | 基线可启动后实现；第一批分类读写接口作为 B-14 联调样例 |
| A-03 文章基础管理 | 新建草稿、详情、编辑、逻辑删除；作者取真实 CurrentUserContext；DTO 禁止客户端覆盖作者与状态；落实正文防护和版本冲突 | 分类与身份能力可用；验证旧版本更新返回 409 |
| A-04 查询与分页 | 数据库分页、组合筛选、排序白名单；批量 UserReader 读取作者；统一分页及逻辑删除 | 统一分页插件可用；停用作者保留展示，缺失作者使用占位，不循环查用户 |
| A-05 生命周期 | 单一 ArticleLifecycleService 管理提交、审核、退回、发布、下架和必要的重新编辑；记录状态历史；公开查询仅返回已发布数据 | 状态更新校验 ID、原状态和版本；状态与历史同事务；最终验收要求 B-14/B-15 接入 |
| A-06 批量发布 | 限制 50 条，校验重复、存在性、状态和权限；同事务全成全败；处理中途失败和并发更新 | A-05 可用；失败时内容更新与状态历史整体回滚 |
| A-07 测试与优化 | 分类循环、文章版本冲突、非法状态、并发转换、批量回滚、N+1 优化与分页准确性 | 普通 Spring Test；保留 SQL 次数或执行计划等真实证据 |
| A-08 联调与页面 | 接入权限、日志、响应、当前用户；实现 A 负责页面；验证 CSRF、权限撤销、编辑越权审核和匿名公开查询 | B-14/B-15 及 A 核心接口可用；页面、ApiFox 与真实 HTTP 验证通过 |
| A-09 交付 | 更新 A 架构、数据库、接口、测试与过程材料；联合完成空库部署及全流程演示 | 与 B 的 WAR 和配置说明一致，另一环境可复现 |

若旧数据库设计也不再采用，A-01 重新设计同等职责的表；仍保留 B 的用户主键契约，并将旧脚本移出最终执行顺序。不能让有已知阻塞的旧脚本继续作为安装入口。

## 5 A 使用 B 能力的边界

- 创建人、更新人、审核人从 `CurrentUserContext.getRequiredUser()` 获取；客户端不得伪造操作者。
- 作者显示使用 `UserReader.findByIds`，不直接访问 B 的 Mapper；单批输入上限按远端契约为 500。
- 响应、分页、异常和字段校验使用 B 已交付类型，不新增平行实现。
- 权限用 B 完成后的 `RequirePermission`，日志用 B 完成后的 `OperationLog`。
- A 自己负责文章状态历史，B 负责通用操作日志；两者不互相替代。
- B-14 未交付时，A 可开发持久层和 Service，但后台接口不能以无权限校验状态作为完成交付；联调分支如需临时保护，应与 B 明确统一方案并随后替换。

## 6 推荐推进顺序

1. B 开始 B-M01～B-M05；A 同步完成 A-00 和 A-01 设计及 SQL 验证。
2. B 发布并验证传统 SSM 基线；A 从该提交建立新的内容开发分支，完成 A-M00。
3. A 完成分类样例，B 优先完成 B-14，进行第一次权限联调。
4. A 完成文章 CRUD、查询和生命周期；B 完成 B-15，并继续其剩余任务。
5. A 完成批量事务、优化、页面和测试，双方进行真实 MySQL、Tomcat 与 ApiFox 联调。
6. 双方共同完成干净环境交付。

同一时期的 POM、Spring 配置和公共插件链由 B 主维护。A 通过明确的 content 包、XML 路径和接口契约接入，减少公共基础冲突。

## 7 验证范围

本计划依据远端源码、提交记录及任务文档编制。本次未执行远端构建、自动化测试或部署，因此“已实现”不等同于已由本次检查验证运行成功。
