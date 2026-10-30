# A 内容接口与本地 Apifox 场景

| 文件 | 用途 |
| --- | --- |
| content.openapi.json | OpenAPI 3.0.3 接口定义 |
| content.postman_collection.json | Postman 请求模板，不能直接按目录顺序运行 |
| content.http-cases.json | 16 项正常/边界/权限用例定义，不包含本机执行结果 |
| content.apifox-cli.json | Apifox CLI 的生命周期、CRUD、权限和批量主场景 |
| content-revoked.apifox-cli.json | 撤权后沿用已有会话的负例 |
| content-disabled.apifox-cli.json | 停用后沿用已有会话的负例 |

原生文件的账号、密码、Cookie 和 CSRF 值为空，通过本机临时变量文件提供。完整运行入口是 scripts/verify_content_mysql80.py，需要配置 JDK、Maven、MySQL 8.0、Tomcat 10.1、Node、Playwright/Chromium 和官方 Apifox CLI 路径。运行配置见[本地部署说明](../../deployment/A内容模块本地运行.md)。

单独运行主场景：

```text
apifox run content.apifox-cli.json --variables <本机临时变量文件> -r cli,json
```

变量包含 baseUrl、requestId、runName 和 admin/editor/reviewer/publisher 的 User、Password。后两份场景必须准备真实会话以及撤权/停用前提；完整测试夹具负责准备，不能没有前提就直接串行运行三个文件。

baseUrl 应包含外部 Tomcat 上下文，例如 http://127.0.0.1:8080/cms-a。取 CSRF 后登录，登录后重新取令牌；权限由 auth/me 获取。写请求携带 X-CSRF-Token 和当前 version，批量最多 50 条且不允许重复 ID。

接口和场景生成：

```powershell
python scripts/generate_content_apifox.py
python scripts/generate_content_api.py
python scripts/generate_content_api.py --check
```

生成的用例索引始终描述待执行的定义，不依赖本机结果文件。运行日志、截图、结果快照和验收记录仅在本地保存，不提交真实凭据。
