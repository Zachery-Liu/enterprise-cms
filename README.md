# enterprise-cms

传统 Spring + Spring MVC + MyBatis-Plus 课程项目。Java 17，WAR 部署外部 Tomcat 10.1，MySQL 8.0；不使用 Spring Boot。

当前迁移分支：`feat/security-ssm`，从 B 业务基线 `dff0cca` 建立，选择性接入队友的 `71a4176` 迁移文档。原有注册、会话认证、当前用户、用户批量读取、角色权限查询和分配保留。

2026-10-11 后续开发分支：`feat/security-aop`，从已交付迁移基线 `c5bf818` 建立，新增 B-14 权限注解和切面。

日志记录分支：`feat/operation-log`，基于 B-14 提交 `fcbff83`，实现 B-15 操作日志及事务一致性。查询分支：`feat/operation-log-query`，基于 B-15 提交 `f9abceb`，实现 B-16 日志分页与组合筛选。当前用户管理分支：`feat/user-administration`，基于 B-16 `a65db1a`，补齐 B-11 用户分页和账号启停。

- [构建、部署与 A 接入说明](docs/deployment/传统SSM部署与A接入.md)
- [迁移验证记录](docs/verification/BM01-BM06传统SSM迁移验证记录.md)
- [公共契约](docs/design/B模块公共契约.md)
- [B-14 权限注解接入与验收](docs/api/B14权限注解接入.md)
- [B-15 操作日志接入与验收](docs/api/B15操作日志接入.md)
- [B-16 操作日志查询与 ApiFox 用例](docs/api/B16操作日志查询.md)
- [B-11 用户管理与 ApiFox 用例](docs/api/B11用户管理.md)
- [B-17 用户角色查询优化与评分证据](docs/optimization/B用户角色查询优化.md)
- [B 任务清单](docs/tasks/B模块逐项任务清单.md)

用 IDEA 打开本目录的 `pom.xml`，Maven 项目名为 `cms`；构建产物为 `target/enterprise-cms.war`。这不是独立可执行 JAR，也没有 Spring Boot 启动类。接口默认部署地址为 `http://localhost:8080/enterprise-cms/api/...`，本分支未提供登录网页。

2026-10-12 当前优化分支：`perf/user-role-query`，基于 B-11 `e8cd81d`。用户列表增加角色数组，采用分页后批量读取；真实 MySQL 每页 10/20/100 条分别由对照方案的 12/22/102 次 SQL 降至固定 3 次，完整数据一致。

B-M01～B-M05 已通过迁移验收，B-M06 的交付文档已准备；A-M00 联合接入验收仍待 A 执行。B-14 已接入角色/权限管理入口，B-15 已接入注册、登录退出和角色权限分配。B-16 提供日志分页筛选；B-11 提供用户分页与账号启停，包含版本冲突、禁止自停用和最后管理员保护，并接入权限和日志。管理页面及 A 的真实分类、文章权限与日志联调仍待完成。
