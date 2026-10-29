"""Opt-in, loopback-only verification of a built WAR in a separate full Tomcat process.

Requires a preinitialized dedicated local MySQL schema and explicit environment credentials.
Leaves deployment files and logs for review; always stops only the process it starts.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile
from content_mysql_target import target, evidence_name


def main():
    root = Path(__file__).resolve().parents[1]
    assert os.environ.get("CMS_EXTERNAL_VERIFY") == "true", "Explicit opt-in is required"
    schema, mysql_port = target()
    home = Path(os.environ["CMS_TOMCAT_VERIFY_HOME"]).resolve()
    with zipfile.ZipFile(home / "lib/catalina.jar") as jar:
        assert "server.number=10.1.60.0" in jar.read("org/apache/catalina/util/ServerInfo.properties").decode("utf-8")
    java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True, check=True)
    assert re.search(r'version "17\.', version.stderr + version.stdout), "Use the verified JDK 17 baseline"
    war = root / "target/enterprise-cms.war"
    war_hash = hashlib.sha256(war.read_bytes()).hexdigest()
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    base = root.parent / ("tomcat-a-verify-" + stamp)
    assert base.resolve().is_relative_to(root.parent.resolve()) and not base.exists()
    port, shutdown_port = 18081, 18082
    for item in (port, shutdown_port):
        with socket.socket() as test_socket:
            test_socket.bind(("127.0.0.1", item))
    for name in ("logs", "temp", "webapps", "work"):
        (base / name).mkdir(parents=True, exist_ok=False)
    shutil.copytree(home / "conf", base / "conf")
    shutdown = secrets.token_hex(24)
    (base / "conf/server.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<Server port="{shutdown_port}" address="127.0.0.1" shutdown="{shutdown}">\n'
        '  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener" />\n'
        '  <Service name="Catalina">\n'
        f'    <Connector address="127.0.0.1" port="{port}" protocol="HTTP/1.1" connectionTimeout="20000" />\n'
        '    <Engine name="Catalina" defaultHost="localhost">\n'
        '      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false" deployOnStartup="true" />\n'
        '    </Engine>\n'
        '  </Service>\n'
        '</Server>\n', encoding="utf-8")
    shutil.copy2(war, base / "webapps/cms-a.war")
    assert hashlib.sha256((base / "webapps/cms-a.war").read_bytes()).hexdigest() == war_hash
    env = os.environ.copy()
    env.update(CMS_DB_URL=env["CMS_MYSQL_TEST_URL"], CMS_DB_USER=env["CMS_MYSQL_TEST_USER"],
               CMS_DB_PASSWORD=env["CMS_MYSQL_TEST_PASSWORD"], CMS_SESSION_COOKIE_SECURE="false",
               CMS_REGISTRATION_DEFAULT_ROLE="USER", CMS_EXTERNAL_BASE=f"http://127.0.0.1:{port}/cms-a")
    # Match the module openings and URL handler used by the distribution's catalina.bat.
    openings = ["java.base/java.lang", "java.base/java.lang.reflect", "java.base/java.io",
                "java.base/java.util", "java.base/java.util.concurrent", "java.rmi/sun.rmi.transport"]
    command = [str(java), "-XX:-UsePerfData"] + ["--add-opens=" + name + "=ALL-UNNAMED" for name in openings] + [
               "-Djava.protocol.handler.pkgs=org.apache.catalina.webresources", "-Dsun.io.useCanonCaches=false",
               "-Dcatalina.home=" + str(home), "-Dcatalina.base=" + str(base),
               "-Djava.io.tmpdir=" + str(base / "temp"), "-Dfile.encoding=UTF-8",
               "-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager",
               "-Djava.util.logging.config.file=" + str(base / "conf/logging.properties"),
               "-classpath", str(home / "bin/bootstrap.jar") + os.pathsep + str(home / "bin/tomcat-juli.jar"),
               "org.apache.catalina.startup.Bootstrap"]
    hidden = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    finished = False
    process = None
    started = datetime.datetime.now().astimezone().isoformat()
    try:
        with (base / "logs/console.log").open("w", encoding="utf-8") as output:
            process = subprocess.Popen(command + ["start"], cwd=base, env=env, stdout=output,
                                       stderr=subprocess.STDOUT, creationflags=hidden)
            print("Started independent Apache Tomcat process " + str(process.pid), flush=True)
            ready = False
            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError("External Tomcat exited during startup; inspect its local console log")
                try:
                    with urllib.request.urlopen(env["CMS_EXTERNAL_BASE"] + "/api/public/articles?size=1", timeout=1) as response:
                        ready = response.status == 200 and "data" in json.load(response)
                    if ready:
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(1)
            assert ready, "External WAR did not become ready"
            print("Production WAR ready at " + env["CMS_EXTERNAL_BASE"], flush=True)
            result = subprocess.run([sys.executable, "-u", "src/test/http/content_external.py"], cwd=root, env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                    encoding="utf-8", errors="replace", timeout=300, creationflags=hidden)
            safe = result.stdout.replace(env["CMS_MYSQL_TEST_PASSWORD"], "[redacted]") if env["CMS_MYSQL_TEST_PASSWORD"] else result.stdout
            print(safe, flush=True)
            result.check_returncode()
            finished = True
    finally:
        if process is not None and process.poll() is None:
            with (base / "logs/shutdown.log").open("w", encoding="utf-8") as output:
                subprocess.run(command + ["stop"], cwd=base, env=env, stdout=output,
                               stderr=subprocess.STDOUT, timeout=20, creationflags=hidden)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
        stopped = process is None or process.poll() is not None
        with socket.socket() as probe:
            probe.settimeout(1)
            closed = probe.connect_ex(("127.0.0.1", port)) != 0
        http_result = root / "docs/verification" / evidence_name("外部Tomcat内容HTTP结果.json")
        target8 = finished and http_result.exists() and json.loads(http_result.read_text(encoding="utf-8")).get("targetMysql8Acceptance", False)
        evidence = {"startedAt": started, "finishedAt": datetime.datetime.now().astimezone().isoformat(),
                    "tomcatVersion": "10.1.60", "javaVersion": (version.stderr + version.stdout).strip(),
                    "externalProcess": True, "productionWar": str(war), "warSha256": war_hash,
                    "catalinaHome": str(home), "catalinaBase": str(base), "schema": schema, "mysqlPort": mysql_port,
                    "baseUrl": env["CMS_EXTERNAL_BASE"], "httpAcceptancePassed": finished,
                    "ownedProcessStopped": stopped, "httpPortClosed": closed,
                    "targetMysql8Acceptance": target8}
        (root / "docs/verification" / evidence_name("外部Tomcat部署结果.json")).write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Owned Tomcat stopped: " + str(stopped) + "; HTTP port closed: " + str(closed), flush=True)
    assert finished and stopped and closed


if __name__ == "__main__":
    main()
