# B 用户角色查询优化与 MyBatis-Plus 应用

日期：2026-10-12。B-17 分支 `perf/user-role-query`，基于 B-11 `e8cd81d`。不使用 Spring Boot，不新增 SQL 迁移。

## 1 问题与实现

此前 B-11 的列表仅返回账号字段，没有提供角色集合。本次为 GET /api/users 的每个账号追加只读 roles，保留原有字段与分页结构；PUT /api/users/{id}/status 的响应不变。

常见低效实现是先分页查询用户，再为本页每个账号查一次角色。包含总数 SQL 时，一页 N 个用户共 N+2 次查询。本次仅在测试源码中复现该做法，用于对照，不声称它曾经存在于已发布的 B-11 代码。

正式实现位于 UserQueryService：

1. 使用 MyBatis-Plus 查询总数和用户页，先决定本页账号。
2. 提取本页最多 100 个 ID，调用一次 selectAssignedRolesForUsers。
3. 按 userId 分组装配 roles，沿用用户页顺序；角色按 code、id 排序。
4. 本页无用户时跳过角色 SQL。

因此非空页固定为 3 次 SQL。直接把用户与多角色关系连接后分页容易把一人多条关系算成多行，本实现先分页用户，避免分页总数和边界受角色数量影响。三次查询在同一个只读 REPEATABLE_READ 事务中读取；未宣称不同 HTTP 请求之间有固定快照。

## 2 展示规则与兼容性

新增 UserListView，列表的每个账号原字段不变，增加：

```json
{
  "roles": [
    {
      "id": 2,
      "code": "EDITOR",
      "name": "编辑",
      "description": null,
      "status": "ENABLED"
    }
  ]
}
```

- 无角色或只关联已逻辑删除角色时返回 roles=[]。
- 管理列表展示已分配且未删除的角色，包含停用角色，便于管理员理解现状；停用账号也保留角色展示。
- 新增 Mapper 方法独立于原 selectRolesForUsers。后者仍只返回有效账号的有效角色，登录鉴权语义不变。
- roles 只是管理展示数据，不能当作有效权限或授权依据。
- 请求仍需要 cms:user:read；集合只读，不允许通过账号启停请求修改角色。
- 所有集合在返回时复制为不可变集合。

## 3 实测方法

实际环境：Microsoft OpenJDK 17.0.20.1+1、Maven 3.9.6、Spring 6.2.15、MyBatis-Plus 3.5.10、MySQL 8.0.44。使用隔离实例 127.0.0.1:33079，新空库 cms_b17_roles_final_20261012，执行正式 sql/001_identity_schema.sql 建表。

每个用例重新准备：

| 数据 | 数量/结构 |
| --- | --- |
| 用户 | 120 个未删除账号：108 有效、12 停用；另有 1 个逻辑删除账号 |
| 角色 | 4 个：ADMIN、EDITOR 有效，REVIEWER 停用，DELETED_ROLE 逻辑删除 |
| 用户角色关系 | 122 条，包含无角色、多角色、停用角色及已删除角色场景 |
| 时间分布 | 120 个用户每 10 人同一秒，验证相同时间按 ID 倒序 |
| 管理查询权限 | 为专用测试管理员分配 cms:user:read |

在测试 DataSource 包装连接的 JDBC prepareStatement，计入用户总数、用户页和角色 SQL。计数窗口仅包含列表 Service，不包含建库、准备数据、HTTP 身份检查、事务控制语句；MyBatis 缓存未执行的查询也不会被虚计。每轮分别运行测试专用逐用户方案和正式批量方案，比较整个 PageResponse 的字段、顺序、总数和角色内容。

最初在 MyBatis StatementHandler 上计数，单独执行通过，但全量测试中的多个配置上下文使分页总数语句漏计。最终改为直接观察 JDBC 连接，不通过减少期望查询数来绕过漏计问题；以下证据以修正后结果为准。

每页 10、20、100 条各 5 轮，共 15 对；每对均断言结果相等并核对实际 SQL 数。

## 4 真实 MySQL 结果

| 每页账号数 | 对照查询次数，每轮 | 批量查询次数，每轮 | 查询次数减少 | 轮数 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 10 | 12 | 3 | 75.00% | 5 | 全部一致 |
| 20 | 22 | 3 | 86.36% | 5 | 全部一致 |
| 100 | 102 | 3 | 97.06% | 5 | 全部一致 |

15 行原始测量输出见 [B17真实MySQL查询次数.txt](B17真实MySQL查询次数.txt)，从实际测试日志提取，非手工编造。完整日志在 B 工作区 work/b17-mysql-roles.log。

无匹配或超出最后一页时，实测只执行总数 SQL 1 次，不访问角色表。超出最后一页仍返回正确 total。另逐页遍历 120 个账号，验证没有重复、遗漏和角色导致的分页错位。

上述百分比只表示数据库查询次数减少，未采集稳定的端到端耗时或执行计划，因此不把它说成速度提升百分比。数据规模属于课程演示，并不代表大规模生产压测。

## 5 MyBatis-Plus 评分证据定位

| 能力 | 正式实现位置 | 实际验证 |
| --- | --- | --- |
| 数据库分页 | UserQueryService、OperationLogQueryService 的 selectPage；PersistenceConfiguration 的 PaginationInnerInterceptor | 页大小上限、总数、空页、LIMIT SQL |
| 条件构造 | 两个 QueryService 的 LambdaQueryWrapper；用户名 LIKE 使用参数绑定和字面通配符转义 | 组合筛选、UTC 时间、非法参数和注入字符串 |
| 逻辑删除 | UserEntity 的 TableLogic；MP 自动给用户查询附加 deleted=0，角色关联 XML 显式过滤 | 已删除用户不展示、已删除角色不附带；没有新增账号删除入口 |
| 自动填充 | IdentityAuditFillHandler；用户/关系/日志实体的 TableField fill | B-11/B-15 测试验证更新操作者、UTC 时间和回滚一致性 |
| 乐观锁 | UserEntity 的 Version 与 OptimisticLockerInnerInterceptor，B-11 状态更新使用 updateById | 并发相同版本只有一个成功、旧版本 409 |
| 批量关系查询 | RbacQueryMapper.selectAssignedRolesForUsers | 本页角色一次 IN 查询，3 次总查询不随页大小增长 |

逻辑删除证据是正式查询的过滤行为，测试预置 deleted=1；本轮没有对外开放删除账号接口。自动填充和乐观锁沿用已实现的 B-11，不以测试夹具代替正式功能。

## 6 验证和复现

- UserRolePageIntegrationTest：6 项新增常规用例；MySQL 对应 MysqlUserRolePageIT 重跑同组用例。
- 普通回归使用 JDK 17 执行 mvn clean verify。
- 真实数据库须准备新的空测试库，设置 CMS_MYSQL_TEST_URL、CMS_MYSQL_TEST_USER、CMS_MYSQL_TEST_PASSWORD，再执行 mvn -Dtest=MysqlUserRolePageIT test。测试会建表和清理夹具数据，不得指向业务库。
- naive 对照方法、SQL 探针和演示数据全部位于 src/test，正式 WAR 不包含它们。
- 外部 Tomcat 验收追加了用户列表角色 ID/code 的断言，完整结果见[B-17 验证记录](../verification/B17用户角色查询优化验证记录.md)。

人工 ApiFox、前端页面、真实 A/B 联调仍属后续任务。
