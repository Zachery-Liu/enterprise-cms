# B-14 权限 AOP 验证记录

日期：2026-10-11。分支：`feat/security-aop`，从已交付传统 SSM 基线 `c5bf818` 创建。无 Spring Boot，新文件、缓存与临时服务均在 B 本机 E 盘。

## 1 本轮变更

- 实现契约规定的 `@RequirePermission(String value)`，每个方法声明一个精确权限。
- `PermissionAspect` 注册在 MVC 子容器，进入业务方法前读取 `CurrentUserContext`；未登录/不可用账号为 401，无权限为 403，ADMIN 不绕过。
- 将角色查询、角色详情、角色权限、权限查询、用户角色分配、角色权限分配 6 个入口切换到注解，删除临时 `PermissionQueryGuard`。
- B-13 Service 的事务内加锁、权限复核、回滚及最后管理员保护保持不变。请求快照不替代等待锁后的重新检查。
- 公开访问继续使用具体方法的 `@AnonymousAccess`，写请求仍校验 CSRF；非法注解冲突不能形成匿名放行。

## 2 实际验证

| 验证 | 结果 |
| --- | --- |
| JDK 17 `mvn clean verify` | 125 项通过，失败 0、错误 0、跳过 0 |
| 新增 `PermissionAspectIntegrationTest` | 12 项通过，包含在上述 125 项中 |
| 新增 `PermissionAspectTest` | 1 项通过，包含在上述 125 项中 |
| MySQL 8.0.44 `MysqlAssignmentIT` | 18 项通过，失败 0、错误 0、跳过 0 |
| 外部 Tomcat 10.1.60 `ExternalTomcatIT` | 1 项连续 HTTP 场景通过，失败 0、错误 0、跳过 0 |
| WAR 检查 | 包含新注解/切面；不含旧 Guard、Boot、H2、测试探针或内嵌 Tomcat |

运行版本沿用迁移验证环境：Microsoft OpenJDK 17.0.20.1+1、Maven 3.9.6、Spring 6.2.15、MyBatis-Plus 3.5.10。常规回归的 112 项原场景没有删减；新增 13 项后为 125 项。MySQL 专项复用了已有事务场景，不宣称所有测试数量代表互不重复的业务用例。

### 新增场景

1. 分类读取、文章新增/发布的权限矩阵；编辑可新增而无发布授权时返回 403。
2. 缺失权限或 CSRF 时业务计数不增加，证明业务方法未执行。
3. 同一会话中授予/撤销权限，下一请求生效。
4. ADMIN 角色没有隐式放行。
5. 停用/删除角色或权限不能授权。
6. 停用/删除账号先返回 401 并使 Session 失效。
7. 显式匿名读取与匿名写请求的 CSRF 约束。
8. 匿名注解与权限注解冲突时不能绕过保护。
9. 未知/空权限编码拒绝访问。
10. 403 响应保留 requestId，完成后清理请求内身份快照。
11. 无认证请求直接调用 Spring Controller 代理仍被拒绝。
12. 当前 6 个生产入口可代理，权限切面只注册在 MVC 容器。
13. 非活动身份快照的防御分支清除剩余会话，不进入目标方法。

分类/文章 Controller 是测试示例，不创建 A 的内容表，不进入 WAR，不作为 A 真实接口联合验收。

## 3 真实数据库与外部部署

复用之前建立的独立 MySQL 实例（127.0.0.1:33079），本轮另建空库 `cms_ssm_transactions_b14_20261011` 执行 18 项事务/并发测试。原业务实例和数据未修改。测试中的故障注入会记录预期异常，最终测试断言及退出码均为成功。

最终 WAR 部署到独立 Tomcat，HTTP 地址为 `http://127.0.0.1:18079/enterprise-cms`，使用隔离库 `cms_ssm_http`。沿用真实 HTTP 场景检查注册、登录与 Cookie 轮换、普通用户管理访问 403、授权/撤销、退出失效及账号停用。

首次增量打包发现旧 Guard 的 class 残留。执行 `clean verify` 后重新检查并部署最终包，旧类已移除。最终 WAR SHA-256：

```text
5bb95742bcbaf39a69af5854efdeb6811d11eecdbe39fb628ab6d391c8b7ab9c
```

该摘要仅标识本次受验包。日志保留在 B 工作区 `work/b14-verify.log`、`work/b14-mysql-verify.log`、`work/b14-http-verify.log`，外部容器日志在项目 `runtime/b14-tomcat-final/logs`；不提交数据库凭证、运行文件或 WAR。

测试结束已停止本轮 Tomcat，并正常关闭隔离 MySQL 实例；原业务服务未调整。

## 4 完成边界与复验

普通构建使用 `mvn clean verify`。专项复验的环境变量和隔离数据库要求沿用[迁移验证记录](BM01-BM06传统SSM迁移验证记录.md)，数据库专项必须另建空库。

B-14 的 B 端实现和回归已完成；任务清单的「使用 A 的一个分类接口和一个文章接口」尚未完成。2026-10-11 拉取远端后，`feature/content@dde44fb` 仍无可运行的 Java 内容实现，未擅自合入旧 A 业务或代写其业务规则。

同类内部调用、非 Spring 对象、根容器 Service 注解不属于当前 MVC 代理保护范围，已在[接入说明](../api/B14权限注解接入.md)明确。A-M00 联合验收、真实 A 接口权限联调、人工 ApiFox 点测以及 B-15 操作日志仍待完成。
