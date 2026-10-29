"""Initialize an explicitly selected, new local CMS verification database.

Credentials come only from CMS_MYSQL_TEST_USER/PASSWORD; no credentials are logged.
Default mode is read-only. Pass --initialize to apply the existing installation SQL.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from content_mysql_target import target, evidence_name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--initialize", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    schema, port = target()
    client = os.environ.get("CMS_MYSQL_CLIENT") or shutil.which("mysql")
    if not client:
        raise RuntimeError("Set CMS_MYSQL_CLIENT to the local mysql executable")
    password = os.environ["CMS_MYSQL_TEST_PASSWORD"]
    child_env = os.environ.copy()
    child_env["MYSQL_PWD"] = password
    command = [client, "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=" + str(port),
               "--user=" + os.environ["CMS_MYSQL_TEST_USER"], "--connect-timeout=5",
               "--default-character-set=utf8mb4", "--batch", "--skip-column-names"]

    def query(sql, database=None, required=True):
        process = subprocess.run(command + (["--database=" + database] if database else []),
                                 input=sql, env=child_env, text=True, encoding="utf-8",
                                 errors="replace", capture_output=True)
        if required and process.returncode:
            error = process.stderr.replace(password, "[redacted]") if password else process.stderr
            raise RuntimeError(error)
        return process

    version = query("SELECT VERSION();").stdout.strip()
    expected = os.environ.get("CMS_MYSQL_TEST_VERSION_PREFIX", "8.0.")
    if not version.startswith(expected):
        raise RuntimeError("Server version differs from the explicitly expected version prefix")
    print("Server version: " + version)
    exists = query("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + schema + "';").stdout.strip()
    if not args.initialize:
        print("Selected schema: " + schema + (" (exists)" if exists else " (absent)"))
        return
    if exists:
        raise RuntimeError("Refusing initialization: dedicated schema already exists; no existing schema is cleared")
    names = ["001_identity_schema.sql", "002_content_schema.sql", "003_identity_seed.sql",
             "004_registration_default_role.sql"]
    scripts = [(name, (root / "sql" / name).read_text(encoding="utf-8-sig")) for name in names]
    for _, sql in scripts:
        if re.search(r"(?im)^\s*(?:USE\s|(?:CREATE|DROP)\s+DATABASE\b)", sql):
            raise RuntimeError("Installation scripts must not change database scope")
    query("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    assert query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();", schema).stdout.strip() == "0"
    for name, sql in scripts:
        query(sql, schema)
        print("Applied " + name)
    repeated = query(scripts[0][1], schema, required=False)
    assert repeated.returncode != 0 and "1050" in repeated.stderr, "Fresh DDL must refuse an existing installation"
    query(scripts[-1][1], schema)
    counts = {}
    for table in ["sys_user", "sys_role", "sys_permission"]:
        counts[table] = int(query("SELECT COUNT(*) FROM " + table + ";", schema).stdout.strip())
    table_count = int(query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();", schema).stdout.strip())
    assert counts == {"sys_user": 4, "sys_role": 5, "sys_permission": 15}
    assert table_count == 9
    result = {"serverVersion": version, "schema": schema, "port": port, "targetMysql8Acceptance": False,
              "initializedScripts": names, "initialization": "passed", "initialTables": table_count,
              "seedCounts": counts, "freshInstallRepeatError": 1050, "defaultRoleRepeat": "passed"}
    # This result reports initialization only; formal acceptance still requires all target-environment checks.
    filename = evidence_name("初始化结果.json") if os.environ.get("CMS_VERIFY_LABEL") else "MySQL初始化结果.json"
    (root / "docs/verification" / filename).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
