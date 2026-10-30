"""Execute local native scenarios with the unmodified official Apifox CLI.

Only sanitized statistics are retained. Temporary account variables and raw reports are removed.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

from content_mysql_target import evidence_name


def verify(root, accounts, Session, sql, base, version, schema, category):
    cli = os.environ["CMS_APIFOX_CLI_EXECUTABLE"]
    node = os.environ["CMS_NODE_EXECUTABLE"]
    cli_version = subprocess.run([node, cli, "--version"], capture_output=True, text=True, encoding="utf-8", check=True).stdout.strip()
    identifier = "a-apifox-" + uuid.uuid4().hex[:12]
    variables = {"baseUrl": base, "runName": identifier, "requestId": identifier}
    for role in ["admin", "editor", "reviewer", "publisher"]:
        variables[role + "User"] = accounts[role]["username"]
        variables[role + "Password"] = accounts[role]["password"]
    child_env = os.environ.copy()
    for key in ["CMS_MYSQL_TEST_PASSWORD", "CMS_DB_PASSWORD", "MYSQL_PWD"]:
        child_env.pop(key, None)
    results = []
    secrets = [account["password"] for account in accounts.values()]

    def redact(value):
        for secret in secrets:
            if secret:
                value = value.replace(secret, "[redacted]")
        return value

    def save_result():
        coverage = sorted(set(case for row in results for case in row["caseIds"]))
        evidence = {"cliVersion": cli_version, "serverVersion": version, "schema": schema,
                    "nativeLocalScenarioFiles": True, "cloudProjectExport": False, "cloudUpload": False,
                    "allPassed": len(results) == 3 and all(row["passed"] for row in results),
                    "caseIds": coverage, "scenarios": results,
                    "temporaryCredentialsAndRawReportsRetained": False}
        (root / "docs/verification" / evidence_name("ApiFox原生结果.json")).write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # The directory is new and owned by this invocation; verify containment before its recursive cleanup.
    with tempfile.TemporaryDirectory(prefix="apifox-content-", dir=root.parent) as temporary:
        temporary = Path(temporary)
        if not temporary.resolve().is_relative_to(root.parent.resolve()):
            raise RuntimeError("Temporary credential directory escaped the workspace runtime")

        def run(name):
            source = root / "docs/api/collections" / name
            data = json.loads(source.read_text(encoding="utf-8"))
            variable_file = temporary / "variables.json"
            variable_file.write_text(json.dumps({"environments": [{"id": 1, "variable": {"values": [
                {"key": key, "value": value, "type": "string"} for key, value in variables.items()]}}],
                "globals": {"variable": {"values": []}}}), encoding="utf-8")
            reports = temporary / source.stem
            args = [node, cli, "run", str(source), "--variables", str(variable_file), "--timeout-request", "15000",
                    "--color", "off", "-r", "json", "--out-dir", str(reports), "--out-file", "result"]
            execution = subprocess.run(args, cwd=root, env=child_env, capture_output=True, text=True,
                                       encoding="utf-8", errors="replace", timeout=180)
            reports_json = list(reports.glob("*.json"))
            report = json.loads(reports_json[0].read_text(encoding="utf-8-sig")) if reports_json else {}
            stats = report.get("result", {}).get("stats", {})
            passed = execution.returncode == 0 and bool(stats) and not report.get("result", {}).get("failures")
            passed = passed and all(item.get("failed", 0) == 0 for item in stats.values() if isinstance(item, dict))
            cases = sorted(set(case for step in data["item"][0]["item"] for case in re.findall(r"A-HTTP-\d{2}", step["name"])))
            result = {"file": name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                      "passed": passed, "exitCode": execution.returncode, "stats": stats, "caseIds": cases,
                      "assertionNames": sorted(set(assertion.get("assertion", "") for group in
                                               report.get("result", {}).get("stepTree", []) for assertion in group.get("assertions", [])))}
            results.append(result)
            save_result()
            if not passed:
                # Do not persist raw request/response bodies, cookies or the CLI's credential variable file.
                print(redact((execution.stdout + execution.stderr)[-5000:]), flush=True)
                failures = report.get("result", {}).get("failures", [])
                for failure in failures[:4]:
                    error = failure.get("error", {})
                    print(redact(json.dumps({"name": error.get("name"), "test": error.get("test"), "message": error.get("message")}, ensure_ascii=False)), flush=True)
                raise RuntimeError("Official Apifox CLI native scenario failed: " + name)
            print("PASS Apifox " + name + ": " + str(stats.get("requests", {}).get("total", 0)) + " requests, "
                  + str(stats.get("assertions", {}).get("total", 0)) + " assertions", flush=True)

        publisher_id = accounts["publisher"]["id"]
        sql("UPDATE sys_user SET status='ACTIVE',version=version+1 WHERE id=" + str(publisher_id))
        run("content.apifox-cli.json")
        sessions = {}
        try:
            for role in ["admin", "editor", "reviewer", "publisher"]:
                session = Session()
                session.login(accounts[role])
                sessions[role] = session
            article = sessions["editor"].data("POST", "/api/articles", {"title": identifier + "-revoke", "content": "body", "categoryId": category})
            article_id = article["id"]
            sessions["editor"].data("POST", "/api/articles/" + str(article_id) + "/submit", {"version": 0})
            sessions["reviewer"].data("POST", "/api/articles/" + str(article_id) + "/approve", {"version": 1})
            cookie = next(cookie.value for cookie in sessions["publisher"].cookies if cookie.name == "JSESSIONID")
            variables.update(fixtureArticleId=article_id, publisherCookie="JSESSIONID=" + cookie, publisherCsrf=sessions["publisher"].csrf)
            secrets.extend([cookie, sessions["publisher"].csrf])
            user_role = int(sql("SELECT id FROM sys_role WHERE code='USER'"))
            publisher_role = int(sql("SELECT id FROM sys_role WHERE code='PUBLISHER'"))
            path = "/api/users/" + str(publisher_id) + "/roles"
            sessions["admin"].data("PUT", path, {"roleIds": [user_role]})
            try:
                run("content-revoked.apifox-cli.json")
            finally:
                sessions["admin"].data("PUT", path, {"roleIds": [user_role, publisher_role]})
            sql("UPDATE sys_user SET status='DISABLED',version=version+1 WHERE id=" + str(publisher_id))
            run("content-disabled.apifox-cli.json")
        finally:
            for role, session in sessions.items():
                if role != "publisher":
                    session.data("POST", "/api/auth/logout")
    save_result()
    expected = {"A-HTTP-" + str(index).zfill(2) for index in range(1, 17)}
    if expected != {case for item in results for case in item["caseIds"]}:
        raise RuntimeError("Native scenario coverage does not match the 16 planned HTTP cases")
