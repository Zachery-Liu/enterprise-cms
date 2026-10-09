# enterprise-cms

传统 Spring + Spring MVC + MyBatis-Plus 课程项目。Java 17，WAR 部署外部 Tomcat 10.1，MySQL 8.0；不使用 Spring Boot。

当前迁移分支：`feat/security-ssm`，从 B 业务基线 `dff0cca` 建立，选择性接入队友的 `71a4176` 迁移文档。原有注册、会话认证、当前用户、用户批量读取、角色权限查询和分配保留。

- [构建、部署与 A 接入说明](docs/deployment/传统SSM部署与A接入.md)
- [迁移验证记录](docs/verification/BM01-BM06传统SSM迁移验证记录.md)
- [公共契约](docs/design/B模块公共契约.md)
- [B 任务清单](docs/tasks/B模块逐项任务清单.md)

用 IDEA 打开本目录的 `pom.xml`，Maven 项目名为 `cms`；构建产物为 `target/enterprise-cms.war`。这不是独立可执行 JAR，也没有 Spring Boot 启动类。接口默认部署地址为 `http://localhost:8080/enterprise-cms/api/...`，本分支未提供登录网页。

B-M01～B-M05 已通过迁移验收，B-M06 的交付文档已准备；A-M00 联合接入验收仍待 A 执行。B-14 权限 AOP、B-15 操作日志业务尚未完成，切面配置可用不代表这两项业务已经实现。
