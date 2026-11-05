"""Run target-MySQL acceptance using an existing 8.0 binary and a NEW local data directory.

Never uses the installed server's data directory or Windows service. Credentials are ephemeral.
The dedicated schema name stays cms_a_verify_20261030; evidence is separate from the 9.6 run.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    if os.environ.get("CMS_MYSQL80_VERIFY") != "true":
        raise RuntimeError("Set CMS_MYSQL80_VERIFY=true to opt into the isolated target-environment checks")
    root = Path(__file__).resolve().parents[1]
    home = Path(os.environ["CMS_MYSQL80_HOME"]).resolve()
    server = home / "bin/mysqld.exe"
    mysql = home / "bin/mysql.exe"
    hidden = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    binary = subprocess.run([str(server), "--no-defaults", "--version"], capture_output=True, text=True, check=True)
    if "Ver 8.0." not in binary.stdout:
        raise RuntimeError("Expected a MySQL 8.0 binary")
    port = 33080
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    base = root.parent / ("mysql80-a-verify-" + stamp)
    if base.exists() or not base.resolve().is_relative_to(root.parent.resolve()):
        raise RuntimeError("Refusing to reuse a data directory")
    base.mkdir()
    data = base / "data"
    data.mkdir()
    initialized = subprocess.run([str(server), "--no-defaults", "--initialize-insecure", "--basedir=" + str(home),
                                  "--datadir=" + str(data), "--console"], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", timeout=120, creationflags=hidden)
    (base / "initialize.log").write_text(initialized.stdout + initialized.stderr, encoding="utf-8")
    initialized.check_returncode()
    print("Initialized new, isolated MySQL 8.0 data directory", flush=True)
    env = os.environ.copy()
    env.update(PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
    password = secrets.token_urlsafe(32)
    schema = "cms_a_verify_20261030"
    env.update(CMS_MYSQL_TEST_SCHEMA=schema,
               CMS_MYSQL_TEST_URL=f"jdbc:mysql://127.0.0.1:{port}/{schema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
               CMS_MYSQL_TEST_USER="root", CMS_MYSQL_TEST_PASSWORD=password, CMS_MYSQL_TEST_VERSION_PREFIX="8.0.",
               CMS_MYSQL_CLIENT=str(mysql), CMS_VERIFY_LABEL="MySQL80", CMS_MYSQL_CONTENT_VERIFY="true",
               CMS_EXTERNAL_VERIFY="true", CMS_EXTERNAL_BROWSER_VERIFY="true")
    command = [str(mysql), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=" + str(port),
               "--user=root", "--connect-timeout=2", "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]

    def query(sql, authenticated=True, database=None):
        child_env = env.copy()
        child_env.pop("MYSQL_PWD", None)
        if authenticated:
            child_env["MYSQL_PWD"] = password
        result = subprocess.run(command + (["--database=" + database] if database else []), input=sql,
                                env=child_env, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                timeout=15, creationflags=hidden)
        if result.returncode:
            raise RuntimeError(result.stderr.replace(password, "[redacted]"))
        return result.stdout.strip()

    def execute(args, logfile):
        result = subprocess.run(args, cwd=root, env=env, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=480, creationflags=hidden)
        safe = (result.stdout + result.stderr).replace(password, "[redacted]")
        (root.parent / logfile).write_text(safe, encoding="utf-8")
        print("\n".join(safe.splitlines()[-24:]), flush=True)
        result.check_returncode()

    process = None
    finished = False
    authenticated = False
    started = datetime.datetime.now().astimezone().isoformat()
    version = None
    try:
        with (base / "console.log").open("w", encoding="utf-8") as output:
            process = subprocess.Popen([str(server), "--no-defaults", "--basedir=" + str(home), "--datadir=" + str(data),
                                        "--port=" + str(port), "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                                        "--log-error=" + str(base / "server.log"), "--pid-file=" + str(base / "server.pid")],
                                       env=env, stdout=output, stderr=subprocess.STDOUT, creationflags=hidden)
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Owned MySQL server exited during startup; inspect the isolated server log")
                try:
                    version = query("SELECT VERSION();", authenticated=False)
                    break
                except (RuntimeError, subprocess.TimeoutExpired):
                    time.sleep(0.2)
            if not version or not version.startswith("8.0."):
                raise RuntimeError("The owned MySQL 8.0 instance did not become ready")
            query("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "';", authenticated=False)
            authenticated = True
            print("Owned MySQL " + version + " ready on 127.0.0.1:" + str(port), flush=True)
            execute([sys.executable, "-u", "scripts/init_content_mysql.py", "--initialize"], "a-mysql80-initialization.log")
            maven = os.environ["CMS_MAVEN_EXECUTABLE"]
            settings = str(root.parent / "test-settings.xml")
            env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env["PATH"]
            execute([maven, "-o", "-B", "-ntp", "-gs", settings, "-s", settings,
                     "-Dtest=MysqlContentVerificationIT", "test"], "a-mysql80-verification.log")
            suite = ET.parse(root / "target/surefire-reports/TEST-com.zachery.cms.modules.content.MysqlContentVerificationIT.xml").getroot()
            if any(suite.attrib[key] != "0" for key in ["failures", "errors", "skipped"]) or suite.attrib["tests"] != "16":
                raise RuntimeError("Target MySQL service test results are not all passing")
            test_result = {"serverVersion": version, "port": port, "schema": schema, "targetMysql8Acceptance": True,
                           "suite": suite.attrib["name"], "tests": 16, "failures": 0, "errors": 0, "skipped": 0,
                           "durationSeconds": float(suite.attrib["time"]),
                           "testCases": [item.attrib["name"] for item in suite.findall("testcase")],
                           "sqlSha256": {name: hashlib.sha256((root / "sql" / name).read_bytes()).hexdigest() for name in
                                         ["001_identity_schema.sql", "002_content_schema.sql", "003_identity_seed.sql", "004_registration_default_role.sql"]}}
            (root / "docs/verification/MySQL80专项测试结果.json").write_text(json.dumps(test_result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            execute([sys.executable, "-u", "scripts/verify_content_tomcat.py"], "a-mysql80-external-tomcat.log")
            browser = root / "output/playwright/content-mysql80-results.json"
            (root / "docs/verification/MySQL80浏览器结果.json").write_bytes(browser.read_bytes())
            checks = {
                "triggersRemaining": "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()",
                "orphanHistory": "SELECT COUNT(*) FROM cms_article_status_history h LEFT JOIN cms_article a ON a.id=h.article_id WHERE a.id IS NULL",
                "liveArticlesOnDeletedCategories": "SELECT COUNT(*) FROM cms_article a JOIN cms_category c ON c.id=a.category_id WHERE a.deleted=0 AND c.deleted=1"
            }
            post = {name: int(query(sql, database=schema)) for name, sql in checks.items()}
            if any(post.values()):
                raise RuntimeError("Post-verification integrity checks failed")
            counts = {name: int(query("SELECT COUNT(*) FROM " + name, database=schema)) for name in
                      ["sys_user", "sys_role", "sys_permission", "cms_category", "cms_article", "cms_article_status_history", "sys_operation_log"]}
            plans = {}
            for name, sql in {
                "adminPage": "SELECT a.id,a.title,c.name FROM cms_article a LEFT JOIN cms_category c ON c.id=a.category_id AND c.deleted=0 WHERE a.deleted=0 ORDER BY a.updated_at DESC,a.id DESC LIMIT 20",
                "publicPage": "SELECT id,title FROM cms_article WHERE status='PUBLISHED' AND deleted=0 ORDER BY published_at DESC,id DESC LIMIT 20",
                "historyTimeline": "SELECT id,action,created_at FROM cms_article_status_history WHERE article_id=1 ORDER BY created_at,id LIMIT 20"
            }.items():
                plans[name] = {"sql": sql, "plan": json.loads(query("EXPLAIN FORMAT=JSON " + sql, database=schema))}
            result = {"serverVersion": version, "port": port, "schema": schema, "rowCounts": counts,
                      "postChecks": post, "queryPlans": plans, "note": "Small real target-environment verification dataset; not a production-scale benchmark."}
            (root / "docs/verification/MySQL80收尾与执行计划.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            finished = True
    finally:
        if process is not None and process.poll() is None:
            try:
                child_env = env.copy()
                child_env.pop("MYSQL_PWD", None)
                if authenticated:
                    child_env["MYSQL_PWD"] = password
                subprocess.run([str(home / "bin/mysqladmin.exe"), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                                "--port=" + str(port), "--user=root", "shutdown"], env=child_env, capture_output=True,
                               timeout=20, creationflags=hidden)
                process.wait(timeout=30)
            except (subprocess.TimeoutExpired, OSError):
                process.terminate()
                process.wait(timeout=10)
        with socket.socket() as probe:
            probe.settimeout(1)
            closed = probe.connect_ex(("127.0.0.1", port)) != 0
        evidence = {"startedAt": started, "finishedAt": datetime.datetime.now().astimezone().isoformat(),
                    "serverVersion": version, "binaryVersion": binary.stdout.strip(), "basedir": str(home),
                    "ownedDatadir": str(data), "port": port, "schema": schema, "targetMysql8Acceptance": finished,
                    "ownedProcessStopped": process is None or process.poll() is not None, "portClosed": closed,
                    "existingWindowsServicesChanged": False, "existingDatabaseCredentialsReused": False}
        (root / "docs/verification/MySQL80隔离实例结果.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Owned MySQL stopped; target verification passed=" + str(finished) + "; port closed=" + str(closed), flush=True)
    if not finished or not closed:
        raise RuntimeError("Target MySQL acceptance did not finish successfully")


if __name__ == "__main__":
    main()
