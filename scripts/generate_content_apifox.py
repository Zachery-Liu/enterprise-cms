"""Generate credential-free, locally runnable Apifox CLI native scenarios.

These are locally authored scenarios, not an export from a cloud project.
The official CLI reads the native envelope and runs the standard request/script steps.
"""
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/api/collections"


def scenario(name, steps):
    return {"apifoxCli": "1.1", "name": name, "info": {"name": name,
            "description": "Local CMS acceptance scenario; supply disposable test variables with --variables. No cloud project export is claimed."},
            "item": [{"id": "cms-content", "name": name, "item": steps}],
            "environment": {"id": 1, "name": "Dedicated local CMS", "variable": {"values": [
                {"key": key, "value": "", "type": "string"} for key in [
                    "baseUrl", "requestId", "runName", "adminUser", "adminPassword", "editorUser", "editorPassword",
                    "reviewerUser", "reviewerPassword", "publisherUser", "publisherPassword", "fixtureArticleId",
                    "publisherCookie", "publisherCsrf"]]}},
            "globals": {"variable": {"values": []}}, "reportExtra": {}, "dataSchemas": {},
            "projectOptions": {"responseValidateApiTest": False}}


def main():
    steps = []

    def add(name, method, path, status=200, body=None, script="", csrf=True, cookie=None):
        headers = [{"key": "X-Request-Id", "value": "{{requestId}}"}]
        if csrf:
            headers.append({"key": "X-CSRF-Token", "value": "{{csrf}}"})
        if cookie:
            headers.append({"key": "Cookie", "value": cookie})
        request = {"method": method, "url": "{{baseUrl}}" + path, "header": headers}
        if body is not None:
            headers.append({"key": "Content-Type", "value": "application/json"})
            raw = json.dumps(body, ensure_ascii=False)
            # Numeric variables must be JSON numbers after Apifox substitutes their values.
            for key in ["articleId", "version", "categoryId", "batch1", "batch2", "draftProbeId", "adminId", "editorId"]:
                raw = raw.replace('"{{' + key + '}}"', "{{" + key + "}}")
            request["body"] = {"mode": "raw", "raw": raw, "options": {"raw": {"language": "json"}}}
        tests = [f"pm.test({json.dumps(name + ': status')}, function () {{ pm.expect(pm.response.code).to.eql({status}); }});",
                 "pm.test('requestId', function () { pm.expect(pm.response.json().requestId).to.eql(pm.environment.get('requestId')); });"]
        if script:
            tests.append(script)
        step = {"id": "cms-step-" + str(len(steps) + 1), "name": name, "metaInfo": {"type": "http"},
                "request": request, "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": tests}}]}
        steps.append(step)

    def token():
        add("CSRF token", "GET", "/api/auth/csrf-token", script="pm.environment.set('csrf', pm.response.json().data.token);", csrf=False)

    def login(role):
        token()
        add("Login " + role, "POST", "/api/auth/login", body={"username": "{{" + role + "User}}", "password": "{{" + role + "Password}}"})
        token()
        add("Current user " + role, "GET", "/api/auth/me", script="pm.environment.set('" + role + "Id', pm.response.json().data.userId);")

    def logout():
        add("Logout", "POST", "/api/auth/logout")

    def save_article():
        return "const d=pm.response.json().data; pm.environment.set('articleId',d.id); pm.environment.set('version',d.version);"

    def act(action, comment=None, status=200, label=None):
        command = {"version": "{{version}}"}
        if comment is not None:
            command["comment"] = comment
        add(label or action, "POST", "/api/articles/{{articleId}}/" + action, status, command,
            save_article() if status == 200 else "")

    add("A-HTTP-01 anonymous backend denied", "GET", "/api/articles", 401, csrf=False)
    login("admin")
    add("Create category", "POST", "/api/categories", body={"name": "{{runName}}", "sortOrder": 0},
        script="pm.environment.set('categoryId',pm.response.json().data.id);")
    add("A-HTTP-08 create a draft for invalid publication", "POST", "/api/articles",
        body={"title": "draft-{{runName}}", "content": "body", "categoryId": "{{categoryId}}"},
        script=save_article() + "pm.environment.set('draftProbeId',pm.response.json().data.id);")
    act("publish", status=409, label="A-HTTP-08 draft cannot publish directly")
    add("A-HTTP-09 draft is not public", "GET", "/api/public/articles/{{articleId}}", 404)
    logout()
    login("editor")
    add("A-HTTP-04 invalid page size", "GET", "/api/articles?size=101", 400)
    add("A-HTTP-05 sort injection rejected", "GET", "/api/articles?sort=updatedAt%3BDROP%20TABLE", 400)
    article = {"title": "{{runName}}", "content": "<p onclick='bad()'>body<script>bad()</script><a href='javascript:bad()'>link</a></p>",
               "categoryId": "{{categoryId}}", "authorId": 1, "status": "PUBLISHED"}
    add("A-HTTP-03 missing CSRF", "POST", "/api/articles", 403, article, csrf=False)
    add("A-HTTP-15 sanitized server-owned draft", "POST", "/api/articles", body=article,
        script=save_article() + "pm.test('server identity and clean body',function(){const d=pm.response.json().data;pm.expect(d.authorId).to.eql(Number(pm.environment.get('editorId')));pm.expect(d.status).to.eql('DRAFT');pm.expect(d.content).to.include('body');pm.expect(d.content).not.to.include('<script');pm.expect(d.content).not.to.include('onclick');pm.expect(d.content).not.to.include('javascript:');});")
    add("Edit draft", "PUT", "/api/articles/{{articleId}}",
        body={"title": "edited-{{runName}}", "content": "body", "categoryId": "{{categoryId}}", "version": "{{version}}"}, script=save_article())
    add("A-HTTP-06 stale update", "PUT", "/api/articles/{{articleId}}", 409,
        {"title": "stale", "content": "body", "categoryId": "{{categoryId}}", "version": 0})
    act("submit")
    act("approve", status=403, label="A-HTTP-02 editor cannot approve")
    logout()
    login("reviewer")
    act("reject", " ", 400, "A-HTTP-07 blank rejection reason")
    act("reject", "revise")
    logout()
    login("editor")
    add("Revise rejected content", "PUT", "/api/articles/{{articleId}}",
        body={"title": "revised-{{runName}}", "content": "<p>revised</p>", "categoryId": "{{categoryId}}", "version": "{{version}}"}, script=save_article())
    act("submit")
    logout()
    login("reviewer")
    add("Review queue", "GET", "/api/articles/review-queue?categoryId={{categoryId}}",
        script="pm.test('one pending article',function(){pm.expect(pm.response.json().data.total).to.eql(1);});")
    act("approve")
    logout()
    login("publisher")
    act("publish")
    logout()
    add("Anonymous published detail", "GET", "/api/public/articles/{{articleId}}", script="pm.test('safe public projection',function(){const d=pm.response.json().data;pm.expect(d.content).to.eql('<p>revised</p>');pm.expect(d).not.to.have.property('reviewerId');});", csrf=False)
    add("Anonymous published list", "GET", "/api/public/articles?categoryId={{categoryId}}", csrf=False,
        script="pm.test('one public article',function(){pm.expect(pm.response.json().data.total).to.eql(1);});")
    login("publisher")
    act("offline")
    add("A-HTTP-10 offline detail hidden", "GET", "/api/public/articles/{{articleId}}", 404)
    logout()
    login("editor")
    act("reopen")
    add("Full history", "GET", "/api/articles/{{articleId}}/history",
        script="pm.test('eight lifecycle events',function(){pm.expect(pm.response.json().data.total).to.eql(8);});")
    for index in [1, 2]:
        add("Create batch article " + str(index), "POST", "/api/articles",
            body={"title": "batch" + str(index) + "-{{runName}}", "content": "body", "categoryId": "{{categoryId}}"},
            script="pm.environment.set('batch" + str(index) + "',pm.response.json().data.id);")
        add("Submit batch article " + str(index), "POST", "/api/articles/{{batch" + str(index) + "}}/submit", body={"version": 0})
    logout()
    login("reviewer")
    for index in [1, 2]:
        add("Approve batch article " + str(index), "POST", "/api/articles/{{batch" + str(index) + "}}/approve", body={"version": 1})
    logout()
    login("publisher")
    add("A-HTTP-11 duplicate batch IDs", "POST", "/api/articles/batch-publish", 400,
        {"items": [{"id": "{{batch1}}", "version": 2}, {"id": "{{batch1}}", "version": 2}]})
    add("A-HTTP-12 draft item rejects the complete batch", "POST", "/api/articles/batch-publish", 409,
        {"items": [{"id": "{{batch1}}", "version": 2}, {"id": "{{draftProbeId}}", "version": 0}]})
    for index in [1, 2]:
        add("Batch row remains approved " + str(index), "GET", "/api/articles/{{batch" + str(index) + "}}",
            script="pm.test('no partial commit',function(){pm.expect(pm.response.json().data.status).to.eql('APPROVED');pm.expect(pm.response.json().data.version).to.eql(2);});")
    add("A-HTTP-16 publish complete batch", "POST", "/api/articles/batch-publish",
        body={"items": [{"id": "{{batch2}}", "version": 2}, {"id": "{{batch1}}", "version": 2}]},
        script="pm.test('two results in request order',function(){const d=pm.response.json().data;pm.expect(d.count).to.eql(2);pm.expect(d.items[0].id).to.eql(Number(pm.environment.get('batch2')));});")
    logout()
    OUT.mkdir(parents=True, exist_ok=True)
    files = {"content.apifox-cli.json": scenario("A content lifecycle and boundary acceptance", steps)}
    for name, status, method, path in [
        ("revoked", 403, "POST", "/api/articles/{{fixtureArticleId}}/publish"),
        ("disabled", 401, "GET", "/api/articles")
    ]:
        steps = []
        add("A-HTTP-" + ("13 revoked existing session" if name == "revoked" else "14 disabled existing session"), method, path,
            status, {"version": 2} if method == "POST" else None, cookie="{{publisherCookie}}", csrf=False)
        if name == "revoked":
            steps[0]["request"]["header"].append({"key": "X-CSRF-Token", "value": "{{publisherCsrf}}"})
        files["content-" + name + ".apifox-cli.json"] = scenario("A existing session " + name, steps)
    for name, data in files.items():
        (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Generated " + str(len(files)) + " credential-free Apifox CLI scenario files; main steps=" + str(len(files["content.apifox-cli.json"]["item"][0]["item"])))


if __name__ == "__main__":
    main()
