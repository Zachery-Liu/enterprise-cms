# enterprise-cms B 模块公共契约

| 项目 | 内容 |
| --- | --- |
| 文档版本 | V1.0 |
| 编制日期 | 2026-09-15 |
| 技术约束修订 | 2026-10-08：禁止 Spring Boot，业务接口沿用远端契约 |
| 对应任务 | B-01 |
| 负责人 | B |
| 使用方 | A 与 B 全部后台模块 |

## 1 技术与命名基线

| 项目 | 约定 |
| --- | --- |
| Java | Java 17 |
| 构建 | Maven |
| 数据库 | MySQL 8.0 |
| ORM | MyBatis-Plus，复杂查询使用 XML Mapper |
| Web | Spring MVC |
| 核心与运行 | Spring Framework，WAR 部署外部 Tomcat；禁止 Spring Boot |
| 登录 | 服务端 Session |
| 根包名 | `com.zachery.cms` |
| Java 主键 | `Long` |
| MySQL 主键 | `BIGINT UNSIGNED` |
| 时间字段 | 数据库使用 UTC `DATETIME(3)`，接口使用带时区的 ISO 8601 字符串 |
| 字符集 | `utf8mb4` |
| 排序规则 | `utf8mb4_unicode_ci` |

具体 Spring、MyBatis-Plus、Servlet 和数据库驱动版本由工程初始化时在 `pom.xml` 中锁定，但不得改变本契约定义的业务接口和数据类型。

本轮由 B 迁移公共工程，A 从迁移验收后的提交重新开发内容模块。推荐 JDK 17、Spring 6.x 与 Tomcat 10.1，以保留现有 jakarta 体系；若教师指定 Spring 5/Tomcat 9，必须统一迁移命名空间及兼容依赖。禁止 Boot parent、BOM、starter、插件和运行/测试 API；MyBatis-Plus 使用非 Boot 集成。配置加载、数据源、事务、MVC、参数校验、Session 和过滤器均须显式配置。具体任务见[B-M01～B-M06](../tasks/B模块逐项任务清单.md)。已有业务完成记录不等于新环境回归通过。

### 1.1 单体项目目录与包边界

首期采用单体 Maven 工程，不拆分为多个独立服务。源码统一放在 `src/main/java/com/zachery/cms`，资源统一放在 `src/main/resources`。

```text
com.zachery.cms
├─ common
│  ├─ api                  # ApiResponse、PageResponse
│  ├─ exception            # 公共异常、全局异常处理
│  ├─ validation           # 公共校验
│  └─ context              # CurrentUser、CurrentUserContext、requestId
├─ security
│  ├─ annotation           # 权限注解
│  └─ aspect               # 权限 AOP
├─ logging
│  ├─ annotation           # 操作日志注解
│  └─ aspect               # 操作日志切面
└─ modules
   ├─ identity             # B：认证、用户、角色、权限
   │  ├─ auth
   │  ├─ user
   │  ├─ role
   │  └─ permission
   └─ content              # A：分类、文章、审核与发布
      ├─ category
      ├─ article
      └─ publication
```

资源目录按模块分开放置：B 的 Mapper XML 使用 `mapper/identity`，A 使用 `mapper/content`；数据库迁移脚本统一放在仓库 `sql` 目录。`common` 中的类型由 B 维护，但任何破坏兼容性的调整必须由 A、B 共同确认。

### 1.2 公共类型完整类名

以下完整类名属于跨模块编译契约，编码时不得自行改名或移动：

| 完整类名 | 所有者 |
| --- | --- |
| `com.zachery.cms.common.api.ApiResponse` | B |
| `com.zachery.cms.common.api.PageResponse` | B |
| `com.zachery.cms.common.api.FieldViolation` | B |
| `com.zachery.cms.common.api.ValidationErrorData` | B |
| `com.zachery.cms.common.exception.BusinessException` | B |
| `com.zachery.cms.common.context.CurrentUser` | B |
| `com.zachery.cms.common.context.UserStatus` | B |
| `com.zachery.cms.common.context.CurrentUserContext` | B |
| `com.zachery.cms.modules.identity.user.service.UserReader` | B |
| `com.zachery.cms.modules.identity.user.service.UserSummary` | B |
| `com.zachery.cms.security.annotation.RequirePermission` | B |
| `com.zachery.cms.logging.annotation.OperationLog` | B |

确需调整完整类名时，必须先修改本契约并由 A、B 共同确认，再同步双方代码；不能只在某一方代码中移动。

### 1.3 时间与时区规则

- JVM 和数据库连接的业务时区统一为 UTC；MySQL 连接初始化时设置会话时区 `+00:00`。
- `DATETIME(3)` 中保存 UTC 时间，禁止一部分表保存本地时间、另一部分保存 UTC。
- 接口响应使用 ISO 8601 UTC 格式，例如 `2026-09-15T08:30:00.123Z`。
- 接口接收带偏移量的时间后先转换为 UTC；无偏移量的时间参数返回 400。
- 页面负责根据用户所在时区显示，服务端数据库值不随部署机器时区变化。

## 2 模块所有权

| B 负责 | A 负责 |
| --- | --- |
| `sys_user`、`sys_role`、`sys_permission` | `cms_category`、`cms_article` |
| `sys_user_role`、`sys_role_permission` | `cms_article_status_history` |
| `sys_operation_log` | 分类和文章全部状态规则 |
| 登录、当前用户、权限 AOP、操作日志 | 审核、发布、下架和公开查询 |
| 统一响应、异常、请求标识 | A 模块 DTO、VO 和领域异常调用 |

B 不向 A 暴露 UserMapper，A 不直接更新 B 的身份权限表。跨模块读取通过稳定接口完成。

## 3 当前用户契约

固定公共模型：

```java
public record CurrentUser(
        Long userId,
        String username,
        String displayName,
        UserStatus status,
        Set<String> roleCodes,
        Set<String> permissionCodes
) {}
```

公共入口：

```java
public interface CurrentUserContext {
    Optional<CurrentUser> getCurrentUser();
    CurrentUser getRequiredUser();
}
```

规则：

- `getCurrentUser()` 允许在匿名接口返回空值。
- `getRequiredUser()` 在未登录或会话失效时抛出统一未认证异常。
- 用户 ID、用户名、角色和权限均由服务端会话及数据库获得。
- A 不接受客户端传入的作者、审核人、创建人或更新人作为可信身份。
- 账号被停用后，下一次后台请求必须失效。

B-10 已按以上类名和字段实现：同一请求复用不可变快照，下一请求重新读取；匿名入口及后台线程没有默认操作者。具体接入和验收边界见 [B-10 当前用户与用户读取](../api/B10当前用户与用户读取.md)。

## 4 跨模块用户读取契约

A 的文章列表需要批量展示作者，使用只读接口：

```java
public interface UserReader {
    Map<Long, UserSummary> findByIds(Collection<Long> userIds);
}

public record UserSummary(
        Long id,
        String username,
        String displayName,
        UserStatus status
) {}
```

规则：

- 输入自动去重；空集合直接返回空 Map。
- 不返回密码摘要、角色关系、会话或用户管理字段。
- 找不到的用户 ID 不生成伪造记录，由调用方使用明确的“未知用户”展示策略。
- A 不在文章循环中逐次调用单用户查询。

B-10 批量上限为原始输入 500 项，null 集合、null 元素和非正 ID 拒绝。停用账号仍返回展示字段以保留历史作者；逻辑删除账号按找不到处理。返回 Map 不可修改，不提供额外的 HTTP 批量枚举接口。

## 5 统一响应契约

```java
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String requestId
) {}
```

```java
public record PageResponse<T>(
        List<T> records,
        long total,
        long page,
        long size
) {}
```

字段校验失败时，`ApiResponse.data` 固定为以下结构；其他错误响应的 `data` 默认使用 `null`：

```java
public record FieldViolation(
        String field,
        String message
) {}

public record ValidationErrorData(
        List<FieldViolation> errors
) {}
```

规则：

- 成功业务码统一为 `OK`。
- `message` 用于简明说明结果，不返回异常堆栈。
- 空列表使用成功响应和空集合，不能返回 `null` 或 404。
- 页码从 1 开始；页大小默认 20，最大 100。
- 所有响应体包含 `requestId`，响应头同时返回 `X-Request-Id`。

## 6 异常与状态码契约

| HTTP 状态 | 业务码 | 使用场景 |
| --- | --- | --- |
| 400 | `COMMON_VALIDATION_ERROR` | 字段、分页、批量数量和格式错误 |
| 401 | `AUTH_UNAUTHENTICATED` | 未登录或会话失效 |
| 403 | `AUTH_FORBIDDEN` | 已登录但缺少权限 |
| 404 | `COMMON_NOT_FOUND` | 对象不存在或对调用方不可见 |
| 409 | `COMMON_CONFLICT` | 唯一约束、状态或乐观锁冲突 |
| 500 | `COMMON_INTERNAL_ERROR` | 未预期服务端错误 |

A 可以定义更具体的业务码，但必须保持 HTTP 状态语义。例如文章状态冲突可以使用 `ARTICLE_STATUS_CONFLICT`，HTTP 状态仍为 409。

固定公共异常基类：

```java
public class BusinessException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final String code;
}
```

该异常由全局异常处理器转换为统一响应。新增领域异常可以继承它，但不能另建一套相同语义的异常体系。

## 7 请求标识契约

- 请求头名称：`X-Request-Id`。
- 合法客户端请求标识最大 64 个字符，仅允许字母、数字、下划线和连字符。
- 缺失或格式非法时由服务端生成 UUID。
- 同一标识进入日志上下文、操作日志和 `ApiResponse.requestId`。
- 请求结束后必须清理线程上下文。

## 8 权限注解契约

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
```

当前阶段每个方法声明一个核心权限。需要多个权限的复杂表达式不进入课程 MVP。

权限切面顺序：

1. 获取当前用户；不存在时返回 401。
2. 检查账号状态；不可用时使会话失效并返回 401。
3. 检查有效权限集合；缺少权限时返回 403。
4. 允许进入业务方法；业务模块继续校验对象和状态。

## 9 权限编码

| ID | 权限编码 | 用途 |
| --- | --- | --- |
| 1 | `cms:user:read` | 查询用户 |
| 2 | `cms:user:manage` | 启停和管理用户 |
| 3 | `cms:role:assign` | 分配用户角色 |
| 4 | `cms:permission:manage` | 配置角色权限 |
| 5 | `cms:category:read` | 查询分类 |
| 6 | `cms:category:manage` | 维护分类 |
| 7 | `cms:article:read` | 后台查询文章 |
| 8 | `cms:article:create` | 新增文章 |
| 9 | `cms:article:edit` | 编辑文章 |
| 10 | `cms:article:delete` | 删除文章 |
| 11 | `cms:article:submit` | 提交审核 |
| 12 | `cms:article:audit` | 审核通过或退回 |
| 13 | `cms:article:publish` | 发布文章 |
| 14 | `cms:article:offline` | 下架文章 |
| 15 | `cms:log:read` | 查询操作日志 |

权限 ID 仅用于初始化脚本，业务代码只使用稳定权限编码。

## 10 角色编码

| ID | 角色编码 | 初始权限范围 |
| --- | --- | --- |
| 1 | `ADMIN` | 全部权限 |
| 2 | `EDITOR` | 分类查看、文章查看、新增、编辑、删除、提交 |
| 3 | `REVIEWER` | 分类查看、文章查看、审核 |
| 4 | `PUBLISHER` | 分类查看、文章查看、发布、下架 |

业务代码不得写死“管理员自动通过一切权限”。管理员能力来自角色权限关系，便于演示权限配置和撤销。

## 11 会话契约

| 项目 | 约定 |
| --- | --- |
| Session 用户键 | `CMS_CURRENT_USER_ID` |
| 登录成功 | 重新生成 Session 标识并写入用户 ID |
| 退出 | 使当前 Session 失效 |
| 当前用户 | 每次受保护请求根据用户 ID 检查账号和有效权限 |
| 权限变更 | 下一次请求生效 |
| 账号停用 | 下一次请求返回 401 并使 Session 失效 |
| 空闲超时 | 30 分钟 |

Session Cookie 固定使用 `HttpOnly`、`SameSite=Lax` 和 `Path=/`；生产 HTTPS 环境必须启用 `Secure`。默认只允许同源管理端访问，不配置携带凭据的宽泛 CORS。

使用 Cookie Session 时采用同步令牌防护 CSRF：

- `GET /api/auth/csrf-token` 允许匿名访问，创建或读取当前 Session 中的 256 位随机令牌，并通过响应体返回令牌。
- `POST`、`PUT`、`PATCH`、`DELETE` 请求必须在 `X-CSRF-Token` 请求头携带与 Session 一致的令牌，包括注册和登录。
- 登录成功后更换 Session ID 并轮换 CSRF 令牌；退出时同时销毁令牌。
- 普通 `GET`、`HEAD`、`OPTIONS` 请求不改变业务状态，不要求 CSRF 令牌。
- ApiFox 用例必须先获取令牌并保留 Cookie，再执行写请求。

## 12 密码摘要契约

当前演示数据使用 PBKDF2-HMAC-SHA256：

| 项目 | 值 |
| --- | --- |
| 迭代次数 | 600000 |
| 盐长度 | 16 字节随机值 |
| 输出长度 | 32 字节 |
| 存储格式 | `{pbkdf2-sha256}迭代次数$Base64盐$Base64摘要` |

B-08 必须按照相同格式实现注册和登录校验，并使用常量时间比较摘要。后续如果引入统一密码编码器，可以迁移算法，但不能让初始化账号与登录实现使用不同格式。

## 13 操作日志注解契约

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {
    String module();
    String action();
    String targetType() default "";
    String targetIdExpression() default "";
}
```

`targetIdExpression` 使用 Spring Expression Language：可以引用方法参数，例如 `#articleId`；成功返回后可以引用 `#result`。切面在成功返回后计算包含 `#result` 的表达式，失败时只能使用方法参数；无法取得目标 ID 时记录为空，不能伪造 ID。分类、文章、审核、发布等存在明确业务对象的写操作必须填写目标类型和目标 ID 表达式。

日志切面记录真实执行结果，不记录密码、Session、Cookie、完整请求体或文章正文。A 使用注解，B 负责注解实现、表达式解析和持久化。

## 14 数据状态编码

| 对象 | 状态 |
| --- | --- |
| 用户 | `ACTIVE`、`DISABLED` |
| 角色 | `ENABLED`、`DISABLED` |
| 权限 | `ENABLED`、`DISABLED` |
| 日志结果 | `SUCCESS`、`FAILURE` |

逻辑删除字段独立于状态，统一为 0 有效、1 删除。

## 15 B-01 验收结果

- [x] 技术和根包名已确定。
- [x] 公共类型完整类名和变更流程已确定。
- [x] 用户 ID 的 Java 与 MySQL 类型已确定。
- [x] 当前用户和用户批量读取接口已确定。
- [x] 统一响应、分页、异常和状态码已确定。
- [x] 请求标识、权限注解和日志注解已确定。
- [x] 15 个权限编码和 4 个角色编码已确定。
- [x] Session 登录与权限变更生效规则已确定。
- [x] Session Cookie、超时和 CSRF 协议已确定。
- [x] UTC 存储与接口时间格式已确定。
- [x] 字段校验错误载荷和日志目标 ID 取值规则已确定。
- [x] 初始化密码摘要格式已确定。

B-01 已完成。公共类型的完整类名、字段含义、权限编码和错误语义均属于跨模块契约；任何破坏兼容性的修改都必须由 A、B 共同确认并同步文档与代码。
