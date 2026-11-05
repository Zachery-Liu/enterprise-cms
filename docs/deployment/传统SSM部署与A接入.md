# 传统 SSM 部署与 A 接入

更新：2026-10-09。迁移基线位于 `feat/security-ssm`；具体提交见本文件所在分支的 Git 历史和迁移验证记录。

## 1 环境与构建

| 项目 | 本轮验证版本 |
| --- | --- |
| JDK | Microsoft OpenJDK 17.0.20.1+1，实际运行测试使用 JDK 17 |
| Maven | 3.9.6 |
| Spring Framework | 6.2.15 |
| MyBatis-Plus / MyBatis-Spring | 3.5.10 / 3.0.4，非 Boot 集成 |
| 外部 Tomcat | 10.1.60，Servlet 6，jakarta 命名空间 |
| MySQL | 8.0.44 |

设置 `JAVA_HOME` 为自己的 JDK 17 安装目录，执行 `java -version` 和 `mvn -version` 确认实际版本。在项目根目录执行：

```text
mvn clean verify
mvn dependency:tree
```

Windows 可使用 `scripts/build.ps1`，它将临时目录和 Maven 缓存放在项目内，不固定盘符。B 本机将项目放 E 盘，所以相关文件也落在 E 盘。仓库脚本使用 `scripts/settings.xml`；需要代理/镜像时，可使用个人 Maven settings 执行 Maven，不要提交个人凭证。

成功产物：`target/enterprise-cms.war`。普通 `verify` 执行 112 项自动测试，使用独立 H2 数据库；不会连接真实 MySQL。真实 MySQL 与外部 WAR 测试是另外显式执行的 `*IT`，见验证记录。

## 2 数据库

先创建自己的 MySQL 8.0 数据库与应用账号。新空库按顺序执行：

1. `sql/001_identity_schema.sql`：B 的 6 张表。
2. `sql/003_identity_seed.sql`：角色、权限、演示用户与授权。
3. `sql/004_registration_default_role.sql`：注册默认 USER 角色。

不要在已有数据的库上重复执行建表/种子脚本。已有 B 数据库只需检查上述结构与默认角色是否已部署；本轮没有新增业务表或数据迁移。本次验收使用新建隔离实例，未修改原有 MySQL 数据。

A 的 `sql/002_content_schema.sql` 由 A 验证后接入；本轮 B 验收没有覆盖 A 表。种子文件中的密码摘要不是明文密码，本说明不猜测演示账号密码。外部验收的临时账号仅存在于隔离测试库；实际部署使用双方已确认的账号或在专用开发库按密码契约初始化管理员。

## 3 配置

默认读取 `src/main/resources/cms.properties`，支持下列环境变量：

| 环境变量 | 用途 |
| --- | --- |
| CMS_DB_URL | JDBC 地址及库名，保留 UTC 参数 |
| CMS_DB_USER / CMS_DB_PASSWORD | 数据库用户名/密码 |
| CMS_REGISTRATION_DEFAULT_ROLE | 默认注册角色编码，默认 USER |
| CMS_SESSION_COOKIE_SECURE | 本地 HTTP 为 false，HTTPS 为 true |

这些变量必须传给 **Tomcat 进程**；只在 IDEA 的 Maven 配置里填写不会影响外部 Tomcat。

也可将 `config/cms.example.properties` 复制为 `config/cms.local.properties`，填写本机配置，再向 Tomcat 的 JVM options / `CATALINA_OPTS` 添加：

```text
-Dcms.config=file:/absolute/path/config/cms.local.properties
```

Windows 示例形式为 `-Dcms.config=file:E:/your-project/config/cms.local.properties`，含空格的路径需整体引用。该文件已被 Git 忽略；配置示例没有真实凭证。外部文件覆盖默认配置，同名 JVM 系统属性优先。明确指定的配置文件不存在时启动会失败。

## 4 外部 Tomcat 部署

1. 使用 JDK 17 启动 Tomcat 10.1；不要用 Tomcat 9 的 javax Servlet 容器。
2. 首次部署把 WAR 复制到 Tomcat `webapps` 目录；更新已有部署时，先停止对应实例并备份旧包与配置，按 Tomcat 部署流程替换。
3. 使用官方 `bin/catalina.bat run`（Windows）或 `bin/catalina.sh run`（Linux）启动。查看 Tomcat 日志，确认根 Spring 容器、MVC 容器及数据库连接池初始化完成。
4. 访问 `http://localhost:8080/enterprise-cms/api/auth/csrf-token`，应返回 200、统一 JSON 响应和 `X-Request-Id`。

本轮实际验收端口为 18079，MySQL 隔离端口为 33079；它们只是测试实例端口，不是公共配置要求。不要用 `java -jar` 启动 WAR。WAR 名字决定默认上下文路径，改名后需同步接口客户端的 base URL。

Session 仅使用 Cookie，默认 30 分钟空闲超时。Cookie 为 HttpOnly、SameSite=Lax，Path 为实际上下文路径；HTTPS 部署打开 Secure。本地 HTTP 打开 Secure 后客户端不会正常回传 Cookie，可能表现为反复登录失败。

## 5 ApiFox / Postman 验收

导入 `docs/api/B13角色权限配置.postman_collection.json`，默认 `baseUrl=http://localhost:8080/enterprise-cms`。填写本机账号和专用测试用户/角色 ID，开启 Cookie 保持。接口路径仍是 `/api/...`，不要再重复添加 `/enterprise-cms`。

顺序：获取 CSRF → 注册或登录 → 登录后重新获取 CSRF → 查询当前用户 → 查询/配置角色权限 → 退出。写请求携带 `X-CSRF-Token`。未登录访问受保护接口会返回 401 并可能清除匿名会话，此后需重新获取令牌。普通用户查询管理接口应返回 403。

集合中的授权请求会替换目标已有角色/权限，只选择专用测试对象。本轮已完成自动真实 HTTP 验收；没有把它标成 ApiFox 人工点测已完成。

## 6 A 接入约定

| 内容 | 接入方式 |
| --- | --- |
| Service / 普通组件 | `com.zachery.cms.modules.content` 下，根容器扫描 |
| Controller / ControllerAdvice | MVC 子容器扫描；避免在根容器重复注册 |
| Mapper 接口 | `com.zachery.cms.modules.content.persistence.mapper` |
| XML | `src/main/resources/mapper/content/**/*.xml`；统一匹配 `classpath*:mapper/**/*.xml` |
| 事务 | Service 上使用 Spring `@Transactional`，共享单个事务管理器 |
| 分页 / 乐观锁 | 已配置唯一 MyBatis-Plus 插件链，先乐观锁再分页，单页最多 100；A 不重复定义 |
| 审计字段 | 沿用字段名及 MyBatis-Plus FieldFill 标记；现有处理器写 UTC 时间，SessionAuditActorProvider 提供当前用户 ID |
| 当前用户 / 作者显示 | 使用已有 CurrentUserContext / UserReader，详见公共契约；不得另建登录上下文 |
| 公开接口 | 方法标注 `@AnonymousAccess`；只读 GET 不要求 CSRF，写请求仍要求 CSRF |
| 新配置 / 切面 | `@Configuration` 不随模块自动扫描，显式加入对应容器的 `@Import`；作用于 Controller 的切面放 MVC 容器，Service 切面放根容器 |

两层容器都启用了 AOP；测试探针证明 Controller 能被代理，但 **B-14 业务权限注解/切面及 B-15 操作日志仍待实现**。现有角色权限接口继续由 `PermissionQueryGuard` 保护。A 新业务接口在接入 B-14 前不能声称已通过权限验收。

迁移范围已交付注册、登录退出、当前用户、用户批量读取、角色权限查询和分配、公共响应/异常/校验/requestId。用户管理完整业务、日志及其查询等剩余任务以 B 清单为准。

## 7 联合接入下一步

A 从该迁移分支的完整基线建立自己的内容分支，避免只摘取单个配置文件。双方按 A-M00 检查构建、数据库配置、content Mapper/XML、作者读取及权限接口；A 实际验收结果另行记录。本次没有替 A 确认验收，也未合并 main。

随后继续 B-14 分类权限联调，再做 B-15 写操作日志联调。原 Boot 验证记录作为历史保留，新环境结论见迁移验证记录。

参考：[MyBatis-Plus 官方安装说明](https://baomidou.com/getting-started/install/)、[Apache Tomcat 10 下载及版本说明](https://tomcat.apache.org/download-10.cgi)。
