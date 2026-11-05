"""Opt-in HTTP acceptance of the production WAR on an external local Tomcat.

Writes only to the explicitly selected cms_a_verify_YYYYMMDD database.
Creates disposable accounts through B's real registration API, never changes seed passwords.
"""
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
from content_mysql_target import target, evidence_name


def main():
    root = Path(__file__).resolve().parents[3]
    base = os.environ["CMS_EXTERNAL_BASE"].rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    assert parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and parsed.port
    assert parsed.path not in ("", "/") and not parsed.query and not parsed.fragment
    schema, mysql_port = target()
    assert os.environ.get("CMS_EXTERNAL_VERIFY") == "true"
    suffix = uuid.uuid4().hex[:12]
    request_id = "a-external-" + suffix
    checks = []
    password = os.environ["CMS_MYSQL_TEST_PASSWORD"]
    mysql_env = os.environ.copy()
    mysql_env["MYSQL_PWD"] = password

    def sql(statement):
        command = [os.environ["CMS_MYSQL_CLIENT"], "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                   "--port=" + str(mysql_port), "--user=" + os.environ["CMS_MYSQL_TEST_USER"], "--database=" + schema,
                   "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]
        # mysql CLI splits at semicolons, unlike JDBC's single-statement trigger execution.
        script = "DELIMITER $$\n" + statement + "$$\nDELIMITER ;\n" if statement.startswith("CREATE TRIGGER ") else statement
        result = subprocess.run(command, input=script, env=mysql_env, capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=15)
        if result.returncode:
            raise RuntimeError(result.stderr.replace(password, "[redacted]") if password else result.stderr)
        return result.stdout.strip()

    assert sql("SELECT DATABASE();") == schema
    version = sql("SELECT VERSION();")
    assert version.startswith(os.environ.get("CMS_MYSQL_TEST_VERSION_PREFIX", "8.0."))

    def passed(label):
        checks.append(label)
        print("PASS " + label, flush=True)

    class Session:
        def __init__(self):
            self.cookies = http.cookiejar.CookieJar()
            self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
            self.csrf = None

        def request(self, method, path, body=None, status=200, csrf=True, raw=False):
            headers = {"X-Request-Id": request_id}
            if body is not None:
                headers["Content-Type"] = "application/json"
            if csrf and self.csrf is not None:
                headers["X-CSRF-Token"] = self.csrf
            request = urllib.request.Request(base + path, method=method, headers=headers,
                                            data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None)
            try:
                response = self.opener.open(request, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            text = response.read().decode("utf-8")
            assert response.status == status, f"{method} {path}: expected {status}, got {response.status}; {text[:300]}"
            if raw:
                return text, response.headers
            payload = json.loads(text)
            assert response.headers.get("X-Request-Id") == request_id
            assert payload["requestId"] == request_id
            return payload.get("data"), response.headers

        def data(self, method, path, body=None, status=200, csrf=True):
            return self.request(method, path, body, status, csrf)[0]

        def token(self):
            self.csrf = self.data("GET", "/api/auth/csrf-token")["token"]
            return self.csrf

        def login(self, account):
            before = self.token()
            old_cookie = next(cookie.value for cookie in self.cookies if cookie.name == "JSESSIONID")
            self.data("POST", "/api/auth/login", {"username": account["username"], "password": account["password"]})
            new_cookie = next(cookie.value for cookie in self.cookies if cookie.name == "JSESSIONID")
            assert old_cookie != new_cookie
            self.token()
            assert before != self.csrf
            return self.data("GET", "/api/auth/me")

    anonymous = Session()
    html, _ = anonymous.request("GET", "/content/index.html", raw=True)
    assert 'type="module"' in html and "/assets/" in html
    asset = re.search(r'src="([^"]+\.js)"', html).group(1)
    asset_path = urllib.parse.urlsplit(urllib.parse.urljoin(base + "/content/index.html", asset)).path
    assert asset_path.startswith(parsed.path + "/content/assets/")
    asset_body, _ = anonymous.request("GET", asset_path[len(parsed.path):], raw=True)
    assert len(asset_body) > 100
    passed("WAR serves the built component-library page and hashed assets at its real context path")
    anonymous.data("GET", "/api/articles", status=401)
    cookie_response = anonymous.request("GET", "/api/auth/csrf-token")
    cookie = cookie_response[1].get("Set-Cookie", "").lower()
    assert "httponly" in cookie and "samesite=lax" in cookie and "path=" + parsed.path in cookie
    assert len(cookie_response[0]["token"]) == 43
    passed("Anonymous backend request is 401; cookie path, HttpOnly, SameSite, requestId and CSRF are correct")

    accounts = {}
    roles = {"admin": "ADMIN", "editor": "EDITOR", "reviewer": "REVIEWER", "publisher": "PUBLISHER", "browser": "ADMIN", "plain": "USER"}
    for label, role in roles.items():
        account = {"username": "ext_" + label + "_" + suffix, "password": secrets.token_urlsafe(24)}
        registrar = Session()
        registrar.token()
        account["id"] = registrar.data("POST", "/api/auth/register", account, status=201)["id"]
        if role != "USER":
            sql("INSERT INTO sys_user_role(user_id,role_id,created_by) SELECT " + str(account["id"])
                + ",id,1 FROM sys_role WHERE code='" + role + "';")
        accounts[label] = account
    passed("Six disposable users are registered with the real password hasher and assigned real isolated test roles")
    sessions = {label: Session() for label in roles if label != "browser"}
    for label, session in sessions.items():
        me = session.login(accounts[label])
        assert me["userId"] == accounts[label]["id"]
        if label != "plain":
            assert roles[label] in me["roleCodes"]
    passed("Real role logins rotate session IDs and CSRF tokens and restore current permissions")
    admin, editor, reviewer, publisher = (sessions[name] for name in ("admin", "editor", "reviewer", "publisher"))
    sessions["plain"].data("GET", "/api/articles", status=403)
    editor.data("POST", "/api/categories", {"name": "denied", "sortOrder": 0}, status=403)
    passed("An ordinary user cannot read backend articles; EDITOR cannot infer category-manage permission from its role name")
    category = admin.data("POST", "/api/categories", {"name": "external-" + suffix, "sortOrder": 0})["id"]
    body = {"title": "external-" + suffix, "content": "<p onclick='bad()'>body<script>bad()</script></p>", "categoryId": category,
            "authorId": 1, "status": "PUBLISHED"}
    editor.data("POST", "/api/articles", body, status=403, csrf=False)
    assert sql("SELECT COUNT(*) FROM cms_article WHERE category_id=" + str(category)) == "0"
    passed("Missing CSRF rejects a content write before persistence")
    article = editor.data("POST", "/api/articles", body)
    article_id = article["id"]
    article_path = "/api/articles/" + str(article_id)
    assert article["authorId"] == accounts["editor"]["id"] and article["status"] == "DRAFT"
    assert article["content"] == "<p>body</p>"
    anonymous.data("GET", "/api/public/articles/" + str(article_id), status=404)
    passed("Draft creation uses the server-side author and strips scripts; anonymous readers cannot see the draft")
    editor.data("POST", article_path + "/approve", {"version": 0}, status=403)
    assert sql("SELECT COUNT(*) FROM cms_article_status_history WHERE article_id=" + str(article_id)) == "1"
    passed("EDITOR approval is forbidden without creating status history")
    editor.data("POST", article_path + "/submit", {"version": 0})
    reviewer.data("POST", article_path + "/reject", {"version": 1, "comment": "revise"})
    editor.data("PUT", article_path, {"title": "revised-" + suffix, "content": "<p>revised</p>", "categoryId": category, "version": 2})
    editor.data("PUT", article_path, {"title": "stale", "content": "body", "categoryId": category, "version": 2}, status=409)
    editor.data("POST", article_path + "/submit", {"version": 3})
    queue = reviewer.data("GET", "/api/articles/review-queue?categoryId=" + str(category))
    assert queue["total"] == 1 and queue["records"][0]["id"] == article_id
    reviewer.data("POST", article_path + "/approve", {"version": 4})
    publisher.data("POST", article_path + "/publish", {"version": 5})
    public = anonymous.data("GET", "/api/public/articles/" + str(article_id))
    assert public["content"] == "<p>revised</p>" and "reviewerId" not in public and "authorId" not in public
    passed("Separate editor, reviewer and publisher complete reject/revise/resubmit/approve/publish with stale-version rejection")
    publisher.data("POST", article_path + "/offline", {"version": 6})
    anonymous.data("GET", "/api/public/articles/" + str(article_id), status=404)
    editor.data("POST", article_path + "/reopen", {"version": 7})
    history = editor.data("GET", article_path + "/history")
    assert history["total"] == 8
    assert sql("SELECT GROUP_CONCAT(action ORDER BY created_at,id) FROM cms_article_status_history WHERE article_id=" + str(article_id)) == "CREATE,SUBMIT,REJECT,SUBMIT,APPROVE,PUBLISH,OFFLINE,REOPEN"
    passed("Offline immediately hides the public body; reopen preserves the full eight-event history")

    ready = []
    for index in range(2):
        row = editor.data("POST", "/api/articles", {"title": "batch-" + suffix + str(index), "content": "body", "categoryId": category})
        editor.data("POST", "/api/articles/" + str(row["id"]) + "/submit", {"version": 0})
        reviewer.data("POST", "/api/articles/" + str(row["id"]) + "/approve", {"version": 1})
        ready.append(row["id"])
    trigger = "external_history_" + suffix
    sql("CREATE TRIGGER " + trigger + " BEFORE INSERT ON cms_article_status_history FOR EACH ROW BEGIN IF NEW.article_id="
        + str(ready[1]) + " AND NEW.action='PUBLISH' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='external rollback fixture'; END IF; END")
    command = {"items": [{"id": item, "version": 2} for item in reversed(ready)]}
    try:
        failed = publisher.data("POST", "/api/articles/batch-publish", command, status=409)
        assert failed["failures"][0]["id"] == ready[1]
    finally:
        sql("DROP TRIGGER " + trigger)
    ids = ",".join(map(str, ready))
    assert sql("SELECT COUNT(*) FROM cms_article WHERE id IN (" + ids + ") AND status='APPROVED' AND version=2") == "2"
    assert sql("SELECT COUNT(*) FROM cms_article_status_history WHERE article_id IN (" + ids + ") AND action='PUBLISH'") == "0"
    assert sql("SELECT COUNT(*) FROM sys_operation_log WHERE request_id='" + request_id + "' AND action='ARTICLE_BATCH_PUBLISH' AND result='SUCCESS'") == "0"
    assert sql("SELECT COUNT(*) FROM sys_operation_log WHERE request_id='" + request_id + "' AND action='ARTICLE_BATCH_PUBLISH' AND result='FAILURE'") == "1"
    passed("A real MySQL history failure rolls back both published states and histories and records failure without false success")
    published = publisher.data("POST", "/api/articles/batch-publish", command)
    assert published["count"] == 2 and [item["id"] for item in published["items"]] == list(reversed(ready))
    passed("The rolled-back batch can retry successfully and returns the original request order")

    user_role = int(sql("SELECT id FROM sys_role WHERE code='USER'"))
    publisher_role = int(sql("SELECT id FROM sys_role WHERE code='PUBLISHER'"))
    admin.data("PUT", "/api/users/" + str(accounts["publisher"]["id"]) + "/roles", {"roleIds": [user_role]})
    publisher.data("POST", "/api/articles/" + str(ready[0]) + "/offline", {"version": 3}, status=403)
    admin.data("PUT", "/api/users/" + str(accounts["publisher"]["id"]) + "/roles", {"roleIds": [user_role, publisher_role]})
    sql("UPDATE sys_user SET status='DISABLED',version=version+1 WHERE id=" + str(accounts["publisher"]["id"]))
    publisher.data("GET", "/api/articles", status=401)
    passed("Revoking roles affects an existing session immediately; disabling the test account invalidates its session")
    logs = admin.data("GET", "/api/operation-logs?module=ARTICLE&size=100&operatorId=" + str(accounts["editor"]["id"]))
    assert logs["total"] > 0 and all(row["operatorId"] == accounts["editor"]["id"] for row in logs["records"])
    admin.data("GET", "/api/articles?size=101", status=400)
    passed("Real operation logs retain the actual editor identity; pagination rejects oversized pages")
    for label, session in sessions.items():
        if label == "publisher":
            continue
        session.data("POST", "/api/auth/logout")
        session.data("GET", "/api/articles", status=401)
    passed("Logout invalidates every remaining test session")

    if os.environ.get("CMS_EXTERNAL_BROWSER_VERIFY") == "true":
        child_env = os.environ.copy()
        child_env.update(CMS_CONTENT_LIVE_BASE=base, CMS_CONTENT_LIVE_USER=accounts["browser"]["username"],
                         CMS_CONTENT_LIVE_PASSWORD=accounts["browser"]["password"], CMS_CONTENT_LIVE_DATABASE="MySQL " + version,
                         CMS_CONTENT_LIVE_SERVER="external Apache Tomcat 10.1.60", CMS_CONTENT_LIVE_SCHEMA=schema,
                         CMS_CONTENT_LIVE_OUTPUT_PREFIX="content-" + os.environ.get("CMS_VERIFY_LABEL", "external").lower())
        browser = subprocess.run([os.environ["CMS_NODE_EXECUTABLE"], "src/test/browser/content-live.cjs"], cwd=root, env=child_env,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", timeout=180)
        print(browser.stdout, flush=True)
        browser.check_returncode()
        passed("The component-library browser flow also passes against the external WAR and the real MySQL schema")
    if os.environ.get("CMS_APIFOX_CLI_EXECUTABLE"):
        from apifox_native import verify
        verify(root, accounts, Session, sql, base, version, schema, category)
        passed("The official Apifox CLI reads and executes all sixteen planned cases from local native scenario files")
    assert sql("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()") == "0"
    result = {"baseUrl": base, "database": "MySQL " + version, "schema": schema,
              "server": "external Apache Tomcat 10.1.60", "productionWar": True, "fixtureOnly": False,
              "targetMysql8Acceptance": version.startswith("8.0."), "mysqlPort": mysql_port,
              "requestId": request_id, "passed": len(checks), "checks": checks,
              "testAccountIds": {label: item["id"] for label, item in accounts.items()},
              "categoryId": category, "lifecycleArticleId": article_id, "batchArticleIds": ready,
              "testTriggersRemaining": 0}
    (root / "docs/verification" / evidence_name("外部Tomcat内容HTTP结果.json")).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("External WAR acceptance passed: " + str(len(checks)) + " checks", flush=True)


if __name__ == "__main__":
    main()
