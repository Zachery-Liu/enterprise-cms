"""Generate reviewable API handoff artifacts without contacting any application."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/api/collections"
ref = lambda name: {"$ref": "#/components/schemas/" + name}
integer = lambda minimum=0: {"type": "integer", "format": "int64", "minimum": minimum}
text = lambda maximum=None: {"type": "string", **({"maxLength": maximum} if maximum else {})}
date = {"type": "string", "format": "date-time"}
array = lambda value: {"type": "array", "items": value}
obj = lambda properties, required=(): {"type": "object", "properties": properties, **({"required": list(required)} if required else {})}
nullable = lambda value: {**value, "nullable": True}
schemas = {}
schemas["Error"] = obj({"code": text(), "message": text(), "data": {"nullable": True}, "requestId": text()}, ["code", "message", "requestId"])
schemas["Category"] = obj({"id": integer(1), "parentId": nullable(integer(1)), "name": text(64), "sortOrder": integer(),
    "status": {"type": "string", "enum": ["ENABLED", "DISABLED"]}, "version": integer()}, ["id", "name", "sortOrder", "status", "version"])
schemas["CategoryTree"] = obj({**schemas["Category"]["properties"], "children": array(ref("CategoryTree"))}, ["id", "name", "children"])
schemas["CategoryCreate"] = obj({"parentId": nullable(integer(1)), "name": {**text(64), "minLength": 1}, "sortOrder": integer()}, ["name"])
schemas["Version"] = obj({"version": integer()}, ["version"])
for name, field, spec in [("CategoryRename", "name", {**text(64), "minLength": 1}), ("CategoryMove", "parentId", nullable(integer(1))),
                          ("CategorySort", "sortOrder", integer()), ("CategoryStatus", "status", {"type": "string", "enum": ["ENABLED", "DISABLED"]})]:
    schemas[name] = obj({field: spec, "version": integer()}, [field, "version"])
article_fields = {"title": {**text(200), "minLength": 1}, "summary": nullable(text(500)), "content": {**text(100000), "minLength": 1}, "categoryId": integer(1)}
schemas["ArticleCreate"] = obj(article_fields, ["title", "content", "categoryId"])
schemas["ArticleUpdate"] = obj({**article_fields, "version": integer()}, ["title", "content", "categoryId", "version"])
states = ["DRAFT", "PENDING", "REJECTED", "APPROVED", "PUBLISHED", "OFFLINE"]
listing = {"id": integer(1), "title": text(), "summary": nullable(text()), "categoryId": integer(1), "categoryName": text(),
    "authorId": integer(1), "authorDisplayName": text(), "status": {"type": "string", "enum": states}, "version": integer(),
    "createdAt": date, "updatedAt": date, "publishedAt": nullable(date)}
schemas["ArticleList"] = obj(listing)
schemas["ArticleDetail"] = obj({**listing, "content": text(), "reviewerId": nullable(integer(1)), "reviewerDisplayName": nullable(text()),
    "reviewComment": nullable(text(500)), "reviewedAt": nullable(date)})
schemas["ReviewArticle"] = obj({**listing, "submittedAt": date})
public_fields = {key: listing[key] for key in ["id", "title", "summary", "categoryId", "categoryName", "authorDisplayName", "publishedAt"]}
schemas["PublicArticle"] = obj(public_fields)
schemas["PublicDetail"] = obj({**public_fields, "content": text()})
schemas["History"] = obj({"id": integer(1), "articleId": integer(1), "action": text(), "fromStatus": nullable(text()), "toStatus": text(),
    "reason": nullable(text(500)), "operatorId": integer(1), "operatorDisplayName": text(), "createdAt": date})
schemas["Transition"] = obj({key: listing[key] for key in ["id", "status", "version", "updatedAt"]})
schemas["Approval"] = obj({"version": integer(), "comment": nullable(text(500))}, ["version"])
schemas["Rejection"] = obj({"version": integer(), "comment": {**text(500), "minLength": 1}}, ["version", "comment"])
schemas["BatchItem"] = obj({"id": integer(1), "version": integer()}, ["id", "version"])
schemas["BatchPublish"] = obj({"items": {**array(ref("BatchItem")), "minItems": 1, "maxItems": 50,
    "description": "按 id 拒绝重复，含不同版本的重复 id 也拒绝。"}}, ["items"])
schemas["BatchResult"] = obj({"count": integer(1), "items": array(ref("Transition"))}, ["count", "items"])
schemas["Csrf"] = obj({"token": text()}, ["token"])
schemas["Login"] = obj({"username": text(64), "password": text(128)}, ["username", "password"])
schemas["LoginUser"] = obj({"userId": integer(1), "username": text(), "displayName": text(), "status": text()})
schemas["CurrentUser"] = obj({"userId": integer(1), "username": text(), "displayName": text(), "status": text(),
    "roleCodes": array(text()), "permissionCodes": array(text())})
for item in ["ArticleList", "ReviewArticle", "PublicArticle", "History"]:
    schemas[item + "Page"] = obj({"records": array(ref(item)), "total": integer(), "page": integer(1), "size": integer(1)}, ["records", "total", "page", "size"])

def envelope(data):
    return obj({"code": text(), "message": text(), "data": data, "requestId": text()}, ["code", "message", "data", "requestId"])

def param(name, schema, required=False, where="query"):
    return {"name": name, "in": where, "required": required, "schema": schema}

paging = [param("page", {**integer(1), "default": 1}), param("size", {**integer(1), "maximum": 100, "default": 20})]
paths, requests = {}, []

def operation(method, path, title, output=None, body=None, permission="", anonymous=False, params=(), implemented=False):
    write = method not in ("GET", "HEAD", "OPTIONS")
    security = {} if anonymous else {"SessionCookie": []}
    if write: security["CsrfToken"] = []
    parameters = [param(name, integer(1), True, "path") for name in re.findall(r"\{(\w+)\}", path)] + list(params)
    success = ref(output) if isinstance(output, str) else output or {"nullable": True}
    entry = {"operationId": method.lower() + re.sub(r"[^a-zA-Z0-9]", "_", path), "summary": title,
        "tags": ["认证准备" if implemented else "A内容（本地HTTP已验证）"], "security": [security] if security else [],
        "parameters": parameters, "responses": {"200": {"description": "成功", "content": {"application/json": {"schema": envelope(success)}}}},
        "x-required-permission": permission, "x-implementation-status": "existing-http" if implemented else "local-http-tested-h2"}
    for code in [400, 401, 403, 404, 409, 500]:
        entry["responses"][str(code)] = {"description": {400:"参数错误",401:"未登录",403:"权限或CSRF拒绝",404:"不存在或不公开",409:"业务冲突",500:"内部错误"}[code],
            "content": {"application/json": {"schema": ref("Error")}}}
    if body: entry["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(body)}}}
    paths.setdefault(path, {})[method.lower()] = entry
    requests.append((method, path, title, body, parameters, implemented))

operation("GET", "/api/auth/csrf-token", "获取CSRF令牌（登录后须重取）", "Csrf", anonymous=True, implemented=True)
operation("POST", "/api/auth/login", "登录（令牌及Session轮换）", "LoginUser", "Login", anonymous=True, implemented=True)
operation("GET", "/api/auth/me", "当前身份", "CurrentUser", implemented=True)
operation("POST", "/api/auth/logout", "退出", anonymous=True, implemented=True)
operation("GET", "/api/categories/tree", "分类树", array(ref("CategoryTree")), permission="cms:category:read", params=[param("rootId", integer(1))])
operation("GET", "/api/categories/{id}", "分类详情", "Category", permission="cms:category:read")
operation("POST", "/api/categories", "新增分类", "Category", "CategoryCreate", "cms:category:manage")
operation("PUT", "/api/categories/{id}", "修改分类名称", "Category", "CategoryRename", "cms:category:manage")
for segment, schema, label in [("parent", "CategoryMove", "移动分类"), ("sort", "CategorySort", "分类排序"), ("status", "CategoryStatus", "分类启停")]:
    operation("PATCH", "/api/categories/{id}/" + segment, label, "Category", schema, "cms:category:manage")
operation("DELETE", "/api/categories/{id}", "删除分类", permission="cms:category:manage", params=[param("version", integer(), True)])
filters = paging + [param("title", text(200)), param("categoryId", integer(1)), param("status", {"type":"string","enum":states}), param("authorId", integer(1))]
filters += [param(field, date) for field in ["createdFrom","createdTo","updatedFrom","updatedTo"]]
filters += [param("sort", {"type":"string","enum":["createdAt","updatedAt","publishedAt","title"]}),param("direction", {"type":"string","enum":["ASC","DESC"]})]
operation("GET", "/api/articles", "文章分页筛选", "ArticleListPage", permission="cms:article:read", params=filters)
operation("POST", "/api/articles", "新增草稿", "ArticleDetail", "ArticleCreate", "cms:article:create")
operation("GET", "/api/articles/{id}", "后台文章详情", "ArticleDetail", permission="cms:article:read")
operation("PUT", "/api/articles/{id}", "编辑文章", "ArticleDetail", "ArticleUpdate", "cms:article:edit")
operation("DELETE", "/api/articles/{id}", "逻辑删除文章", permission="cms:article:delete", params=[param("version", integer(), True)])
for action, scope, schema in [("submit","submit","Version"),("approve","audit","Approval"),("reject","audit","Rejection"),
                              ("publish","publish","Version"),("offline","offline","Version"),("reopen","edit","Version")]:
    operation("POST", "/api/articles/{id}/" + action, "文章动作：" + action, "Transition", schema, "cms:article:" + scope)
operation("POST", "/api/articles/batch-publish", "批量发布（全成全败）", "BatchResult", "BatchPublish", "cms:article:publish")
operation("GET", "/api/articles/review-queue", "待审队列", "ReviewArticlePage", permission="cms:article:audit",
    params=paging + [param("title",text(200)),param("categoryId",integer(1)),param("authorId",integer(1)),param("submittedFrom",date),param("submittedTo",date)])
operation("GET", "/api/articles/{id}/history", "状态历史", "HistoryPage", permission="cms:article:read", params=paging)
operation("GET", "/api/public/articles", "公开文章分页", "PublicArticlePage", anonymous=True, params=paging + [param("title",text(200)),param("categoryId",integer(1))])
operation("GET", "/api/public/articles/{id}", "公开文章详情", "PublicDetail", anonymous=True)

spec = {"openapi":"3.0.3", "info":{"title":"CMS A 内容接口交接基线", "version":"1.0.0",
    "description":"A 内容模块接口定义，复用 B 的 Spring/MVC、Session/CSRF、权限与日志。项目包含本地 Apifox CLI 原生场景；运行方法和环境变量见集合 README。"},
    "servers":[{"url":"http://localhost:8080/enterprise-cms", "description":"本机示例，按部署上下文调整"}], "paths":paths,
    "components":{"schemas":schemas,"securitySchemes":{"SessionCookie":{"type":"apiKey","in":"cookie","name":"JSESSIONID"},
    "CsrfToken":{"type":"apiKey","in":"header","name":"X-CSRF-Token"}}}}

examples = {"Login":{"username":"{{username}}","password":"{{password}}"},"CategoryCreate":{"name":"联调分类","sortOrder":0},
    "CategoryRename":{"name":"分类新名称","version":"{{categoryVersion}}"},"CategoryMove":{"parentId":None,"version":"{{categoryVersion}}"},
    "CategorySort":{"sortOrder":1,"version":"{{categoryVersion}}"},"CategoryStatus":{"status":"DISABLED","version":"{{categoryVersion}}"},
    "ArticleCreate":{"title":"联调草稿","content":"<p>联调正文</p>","categoryId":"{{categoryId}}"},
    "ArticleUpdate":{"title":"修改后的草稿","content":"<p>修改正文</p>","categoryId":"{{categoryId}}","version":"{{articleVersion}}"},
    "Version":{"version":"{{articleVersion}}"},"Approval":{"version":"{{articleVersion}}","comment":"通过"},
    "Rejection":{"version":"{{articleVersion}}","comment":"请补充材料"},
    "BatchPublish":{"items":[{"id":"{{articleId}}","version":"{{articleVersion}}"}]}}

def postman_item(method, path, title, body, params, implemented):
    category = path.startswith("/api/categories")
    url = "{{baseUrl}}" + path.replace("{id}", "{{categoryId}}" if category else "{{articleId}}")
    required = [p for p in params if p["in"] == "query" and p["required"]]
    if required: url += "?version={{categoryVersion}}" if category else "?version={{articleVersion}}"
    headers = [{"key":"Content-Type","value":"application/json"}]
    if method not in ("GET","HEAD","OPTIONS"): headers.append({"key":"X-CSRF-Token","value":"{{csrfToken}}"})
    request = {"method":method,"url":url,"header":headers,"description":("现有认证接口。" if implemented else "本地真实 Controller 已验证；ApiFox 场景仍需实际导入运行。") + "Cookie 使用客户端 Cookie Jar；不导出实际 Cookie。"}
    if body:
        raw = json.dumps(examples[body],ensure_ascii=False)
        raw = re.sub(r'"(\{\{(?:categoryId|categoryVersion|articleVersion|articleId)\}\})"',r'\1',raw)
        request["body"] = {"mode":"raw","raw":raw,"options":{"raw":{"language":"json"}}}
    scripts = ["pm.test('HTTP 200', function () { pm.response.to.have.status(200); });",
        "if (pm.response.code === 200) {", "  const body = pm.response.json();",
        "  pm.test('统一响应', () => { pm.expect(body.code).to.eql('OK'); pm.expect(body.requestId).to.be.a('string').and.not.empty; });"]
    if path.endswith("csrf-token"): scripts.append("  pm.collectionVariables.set('csrfToken', body.data.token);")
    elif path.endswith("login"): scripts.append("  pm.collectionVariables.set('csrfToken', ''); // 登录后先重新获取令牌")
    elif path in ("/api/categories","/api/articles") and method == "POST":
        prefix = "category" if category else "article"
        scripts += [f"  pm.collectionVariables.set('{prefix}Id', body.data.id);",f"  pm.collectionVariables.set('{prefix}Version', body.data.version);"]
    elif "{id}" in path and method not in ("GET","DELETE"):
        scripts.append("  if (body.data && body.data.version !== undefined) pm.collectionVariables.set('" + ("categoryVersion" if category else "articleVersion") + "', body.data.version);")
    scripts.append("}")
    return {"name":title,"request":request,"event":[{"listen":"test","script":{"type":"text/javascript","exec":scripts}}]}

collection = {"info":{"name":"CMS A 接口请求基线（SSM本地HTTP已接入）","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    "description":"请求目录，不可按全部条目的目录顺序批量运行。可执行的本地 Apifox 原生场景见 content.apifox-cli.json 及两份会话负例文件；运行证据单独记录，本请求模板不等同于原生场景执行报告。"},
    "variable":[{"key":key,"value":value} for key,value in {"baseUrl":"http://localhost:8080/enterprise-cms","username":"","password":"","csrfToken":"","categoryId":"","categoryVersion":"","articleId":"","articleVersion":""}.items()],
    "item":[postman_item(*request) for request in requests]}
cases = []
for name, method, path, status, setup in [
    ("匿名后台拒绝","GET","/api/articles",401,"清空 Cookie"), ("编辑越权审核","POST","/api/articles/{id}/approve",403,"编辑角色、有效 CSRF、PENDING 文章"),
    ("缺少CSRF","POST","/api/articles",403,"登录后删除 X-CSRF-Token"), ("非法分页","GET","/api/articles",400,"size=101"),
    ("排序注入拒绝","GET","/api/articles",400,"sort=title;DROP TABLE cms_article"), ("旧版本覆盖","PUT","/api/articles/{id}",409,"先保存，再使用旧 version"),
    ("退回原因空白","POST","/api/articles/{id}/reject",400,"PENDING，comment=空白"), ("草稿直接发布","POST","/api/articles/{id}/publish",409,"DRAFT"),
    ("公开草稿隐藏","GET","/api/public/articles/{id}",404,"DRAFT"), ("公开下架隐藏","GET","/api/public/articles/{id}",404,"成功下架后"),
    ("批量重复ID","POST","/api/articles/batch-publish",400,"同 id 不同 version 也拒绝"), ("批量整批冲突","POST","/api/articles/batch-publish",409,"APPROVED 中混入 DRAFT；另查所有文章和历史均未变化"),
    ("权限撤销生效","POST","/api/articles/{id}/publish",403,"B 侧撤销发布权限后沿用旧 Session 和有效 CSRF"),
    ("账号停用失效","GET","/api/articles",401,"B 侧停用当前登录账号"), ("正文清洗","POST","/api/articles",200,"content 含可见文本、script、事件和危险链接；再查询安全正文"),
    ("批量成功","POST","/api/articles/batch-publish",200,"1～50 条 APPROVED，携带各自当前 version，检查发布时间/历史/通用日志")]:
    cases.append({"id":f"A-HTTP-{len(cases)+1:02d}","name":name,"method":method,"path":path,"expectedStatus":status,"setup":setup,"status":"defined"})

def validate():
    ids = set()
    for path, methods in paths.items():
        for method, entry in methods.items():
            assert entry["operationId"] not in ids; ids.add(entry["operationId"])
            assert set(re.findall(r"\{(\w+)\}", path)) == {p["name"] for p in entry["parameters"] if p["in"]=="path" and p["required"]}
            assert "200" in entry["responses"]
            if method not in ("get","head","options"): assert "CsrfToken" in entry["security"][0]
    def walk(node):
        if isinstance(node, dict):
            if "$ref" in node: assert node["$ref"].split("/")[-1] in schemas
            for value in node.values(): walk(value)
        elif isinstance(node, list):
            for value in node: walk(value)
    walk(spec)
    assert len(requests)==len(collection["item"])==len(ids)
    for case in cases: assert case["method"].lower() in paths[case["path"]]

validate()
# Definitions are portable source artifacts; execution evidence stays local and is not an input.
case_result = {"status":"ready-for-execution","cases":cases}
artifacts = {"content.openapi.json":spec,"content.postman_collection.json":collection,"content.http-cases.json":case_result}
for name, value in artifacts.items():
    data = json.dumps(value,ensure_ascii=False,indent=2)+"\n"
    path = OUT / name
    if "--check" in sys.argv:
        assert path.read_text(encoding="utf-8")==data, f"Stale artifact: {path}"
    else:
        OUT.mkdir(parents=True,exist_ok=True); path.write_text(data,encoding="utf-8")
print(f"Validated {len(requests)} operations, {len(schemas)} schemas, {len(cases)} HTTP cases ({case_result['status']}).")
