# B-M01～B-M06 传统 SSM 迁移验证记录

验证日期：2026-10-09。分支：`feat/security-ssm`。代码基线：`420fa8f`，其后交付提交仅更新说明、验收记录及 ApiFox 集合默认地址。

## 1 基线与范围

- 从 `feature/security@dff0cca` 创建迁移分支，保留 B 最新业务实现。
- `4edb79b` 选择性接入队友 `71a4176` 的迁移文档；保留 B 最新公共契约、逐项完成记录和原验证资料，没有把旧文档覆盖到新业务记录上。
- `420fa8f` 完成传统 Spring 配置、WAR 构建和测试迁移；原生产业务实现未改写。B-14/B-15 不属于本轮完成范围。
- 原 Boot 环境 109 项结果只作为历史。本轮使用实际 JDK 17 重新执行，不能把 Java 21 编译目标设为 17 当作 JDK 17 运行证据。

## 2 实际环境

| 项目 | 实测环境 |
| --- | --- |
| 操作系统 | Windows，文件/缓存/临时数据均位于 E 盘 |
| JDK / Maven | Microsoft OpenJDK 17.0.20.1+1 / Maven 3.9.6 |
| Spring / MyBatis-Plus | 6.2.15 / 3.5.10 |
| MyBatis-Spring / Servlet | 3.0.4 / 6.0.0 provided |
| 外部 Tomcat | 10.1.60，WAR 路径 `/enterprise-cms`，测试端口 18079 |
| 真实数据库 | MySQL 8.0.44，全新隔离实例，127.0.0.1:33079 |
| 隔离数据库 | `cms_ssm_transactions`、`cms_ssm_http` |

JDK、Tomcat 从官方地址获取并核对官方 SHA-256 / SHA-512。没有安装系统服务，没有改动原有 MySQL 实例。公共脚本以项目目录为根，不强制其他成员使用 E 盘。

## 3 执行结果

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| JDK 17 `verify` | 112 项，失败 0、错误 0、跳过 0 | 原 109 项 + 3 项 SSM 装配验证 |
| `MysqlAssignmentIT` | 18 项，失败 0、错误 0、跳过 0 | 原 17 项角色/权限事务测试在真实 MySQL 执行，另加外键拒绝悬空角色 |
| `ExternalTomcatIT` | 1 项，失败 0、错误 0、跳过 0 | 对最终 WAR、外部 Tomcat、真实 MySQL 的连续 HTTP 验收场景 |
| Secure Cookie 配置 | 通过 | 独立以 `cms.session.cookie-secure=true` 启动实际 WAR，GET 返回 Secure / HttpOnly / SameSite=Lax / 正确 Path |
| WAR 与依赖检查 | 通过 | 无 Boot 依赖/API；WAR 不含 H2、JUnit、测试探针、内嵌 Tomcat 或 Servlet API |

以上是不同测试层次的执行数；18 项中的 17 项与常规回归场景重叠，不宣称 131 个互不重复的业务场景。

### 3.1 常规回归明细

| 测试类 | 数量 |
| --- | ---: |
| CommonWebContractTest | 16 |
| RequestIdFilterTest | 3 |
| RequestValidationTest | 14 |
| SsmWiringIntegrationTest | 3 |
| CurrentUserIntegrationTest | 14 |
| PasswordHasherTest | 4 |
| RegistrationIntegrationTest | 12 |
| SessionIntegrationTest | 15 |
| IdentityMapperIntegrationTest | 2 |
| RbacAssignmentIntegrationTest | 17 |
| RbacQueryIntegrationTest | 12 |

保留原密码、参数校验、统一错误、requestId、注册、登录退出、超时、授权快照、作者批量读取、逻辑删除、乐观锁和审计填充断言。测试改用普通 Spring Test 的根/子容器，每个集成类显式绑定独立 H2 数据源，避免上下文缓存使测试类串库。

新增装配验证覆盖：业务 Bean 只在根容器，Controller 在 MVC 子容器；MVC 子容器内的测试切面确实代理 Controller；content Mapper 与递归 XML 被扫描，分页查询返回正确总数及页内容。测试探针仅在 `src/test`，不进入 WAR，不等同于 B-14 业务权限切面。

### 3.2 真实 MySQL

使用 `sql/001_identity_schema.sql` 初始化专用事务测试库。覆盖分配/撤销、故障回滚、并发覆盖、最后管理员保护、等待锁期间权限撤销与外键约束；未用 H2 结论替代 MySQL。

HTTP 专用库依次执行 `001`、`003`、`004`。只有隔离库的管理员使用临时测试凭证，未修改原有账号。真实 HTTP 完成注册并验证未自动登录，再执行会话与 RBAC 操作。

### 3.3 外部 HTTP

最终 WAR 在独立 Tomcat 进程运行。自动场景验证：缺失 CSRF 为 403、无效注册为 400、注册成功 201、未登录 401、登录轮换 Cookie、旧 Cookie 无效、普通用户管理查询 403、旧 CSRF 无效、授权/撤销下一请求生效、退出后会话无效、停用账号下一请求 401，以及 JSON/requestId 契约。

访问受保护接口导致匿名会话失效后，测试重新取得 CSRF 再登录；这符合原行为，没有为了测试修改认证规则。HTTP Cookie 验证覆盖 `/enterprise-cms` 路径、HttpOnly、SameSite=Lax；另行启动验证 Secure 开关。完整 HTTPS 证书/反向代理部署不在本轮测试范围。

## 4 产物与证据

产物：`target/enterprise-cms.war`。本次受验包 SHA-256：

```text
9a5d7ed8180b30b0dee0eba0cd28dcbfa3e70951a585fae16575b1fbbc9fad3f
```

该摘要对应本次构建；重新打包可能因时间戳得到不同摘要。

依赖快照见 [BM-SSM-dependency-tree.txt](BM-SSM-dependency-tree.txt)。Maven Enforcer 禁止 Boot 及 MyBatis-Plus Boot 依赖，普通 Spring/Jackson BOM 用于统一组件版本。测试专用内嵌 Tomcat 只用于原 B09 的 HTTP 测试，生产部署使用外部 Tomcat。

B 本机原始日志保留在工作区 `work/ssm-verify.log`、`work/ssm-mysql-verify.log`、`work/ssm-http-verify.log`；Tomcat 日志与隔离库在项目 `runtime/`，不提交凭证、日志或数据库文件。

验收结束后已停止本次 Tomcat 测试进程，并通过 mysqladmin 正常关闭隔离 MySQL；原有服务未调整。

## 5 复验方式

普通构建：`mvn clean verify dependency:tree`。

真实数据库验收为显式选择，不随普通构建运行。先创建一个**全新的空测试库**，设置 `CMS_MYSQL_TEST_URL`、`CMS_MYSQL_TEST_USER`、`CMS_MYSQL_TEST_PASSWORD`，在项目根目录执行：

```text
mvn -Dtest=MysqlAssignmentIT test
```

该测试会建表并重建测试数据，不得指向日常开发/演示库；再次执行需要另一个空库。`ExternalTomcatIT` 要求专用 `cms_ssm_http` 库、已部署 WAR、已知测试管理员，并设置：

```text
CMS_HTTP_TEST_BASE
CMS_HTTP_TEST_DB_URL
CMS_HTTP_TEST_DB_USER
CMS_HTTP_TEST_DB_PASSWORD
CMS_HTTP_TEST_ADMIN
CMS_HTTP_TEST_ADMIN_PASSWORD
```

然后运行 `mvn -Dtest=ExternalTomcatIT test`；仅允许 localhost/127.0.0.1 的 HTTP 目标。不要将填写真实密码的环境文件提交。

## 6 交付边界

B-M01～B-M05 已完成。[部署与 A 接入说明](../deployment/传统SSM部署与A接入.md)、外部配置示例和 ApiFox 集合地址已提供。B-M06 的 **A-M00 联合验收仍待 A 执行**，人工 ApiFox 点测也未标记完成。

本轮没有实现 B-14 权限业务切面或 B-15 操作日志，没有验证 A 的内容 SQL、文章状态流转或 A/B 完整业务联调。原逐项清单的其他未完成任务保持原状态。下一步可交付该分支给 A 接入，再分别推进 B-14、B-15。
