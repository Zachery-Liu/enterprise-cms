# A 内容模块本地运行

后端使用 JDK 17、普通 Spring + Spring MVC、非 Boot MyBatis-Plus，打包为 WAR，部署到外部 Tomcat 10.1；数据库使用 MySQL 8.0。前端是 Vue 3 + TypeScript + Element Plus，随同一个 WAR 部署。

## 构建

准备 Maven 和满足 frontend/package.json 要求的 Node.js，在工程目录执行：

```powershell
cd frontend
npm ci
npm run build
cd ..
mvn clean package
```

也可使用 scripts/build.ps1。生成资源位于 src/main/webapp/content，不手动编辑；最终产物是 target/enterprise-cms.war。

## 数据库和 Tomcat

只在新空库中依次执行：

1. sql/001_identity_schema.sql
2. sql/002_content_schema.sql
3. sql/003_identity_seed.sql
4. sql/004_registration_default_role.sql

已有业务库应审查增量迁移，不重跑空库建表/种子脚本。002 已包含有效同级唯一约束，不重复执行 005。

向 **Tomcat 进程**提供 CMS_DB_URL、CMS_DB_USER、CMS_DB_PASSWORD。URL 使用 jdbc:mysql://127.0.0.1:端口/库名?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true。默认注册角色保持 USER；本地 HTTP 的 CMS_SESSION_COOKIE_SECURE 为 false，HTTPS 使用 true。密码通过本机安全配置提供，不写入提交文件。

将 WAR 复制为 webapps/cms-a.war，使用 Tomcat 的 bin/catalina.bat run 启动。若端口为 8080：

- 后台页面：http://127.0.0.1:8080/cms-a/content/index.html
- 公开页面：http://127.0.0.1:8080/cms-a/content/public.html
- API 前缀：http://127.0.0.1:8080/cms-a/api

账号和角色由部署者在本机准备，不猜测种子密码。普通注册用户没有后台权限；分类管理、文章编辑、审核和发布均以实际权限为准。登录后重新获取 CSRF，写请求使用新令牌，版本使用上次成功响应的值。

## 测试入口

普通 Maven 回归通过 mvn test 执行。真实数据库/外部容器测试为显式选择的 IT；自动隔离环境入口是 scripts/verify_content_mysql80.py，需要设置对应本机程序目录，并使用新的专用数据库目录和回环端口。

Apifox 本地原生场景及变量要求见[接口集合说明](../api/collections/README.md)。测试代码可以复现检查，运行结果、截图及验收记录保存在本机，不作为源码依赖提交。
