"""Create a local review bundle without committing, publishing or copying ignored credentials."""
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    raw = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root)
    names = sorted(set(name for name in raw.decode("utf-8").split("\0") if name))
    names = [name for name in names if name != "docs/delivery/A交付包校验.json"]
    forbidden_names = {".env", "cms.local.properties", "db.properties"}
    sources = []
    for name in names:
        path = root / name
        if path.name in forbidden_names:
            raise RuntimeError("Credential configuration must not enter the source bundle")
        if not path.exists():
            continue
        if not path.resolve().is_relative_to(root.resolve()) or not path.is_file():
            raise RuntimeError("Source path escaped the active workspace")
        sources.append(("source/" + name, path))
    war = root / "target/enterprise-cms.war"
    war_hash = hashlib.sha256(war.read_bytes()).hexdigest()
    expected = json.loads((root / "docs/verification/MySQL80外部Tomcat部署结果.json").read_text(encoding="utf-8"))
    if war_hash != expected["warSha256"] or not expected["targetMysql8Acceptance"]:
        raise RuntimeError("WAR must match the artifact actually verified in the target environment")
    sources.append(("dist/enterprise-cms.war", war))
    screenshot = root / "output/playwright/content-mysql80-published.png"
    if screenshot.exists():
        sources.append(("evidence/content-mysql80-published.png", screenshot))
    created = datetime.datetime.now().astimezone().isoformat()
    manifest = {"createdAt": created,
                "branch": subprocess.check_output(["git", "branch", "--show-current"], cwd=root, text=True).strip(),
                "baseCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                "uncommittedSourceSnapshot": True, "warSha256": war_hash,
                "files": {name: {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                          for name, path in sources}}
    output = root / "target/handoff"
    output.mkdir(exist_ok=True)
    archive = output / ("cms-a-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + ".zip")
    if archive.exists():
        raise RuntimeError("Refusing to overwrite an existing review archive")
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for name, path in sources:
            bundle.write(path, name)
        bundle.writestr("MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:
            raise RuntimeError("Archive integrity check failed")
        for name, metadata in manifest["files"].items():
            if hashlib.sha256(bundle.read(name)).hexdigest() != metadata["sha256"]:
                raise RuntimeError("Archived content differs from its manifest")
    result = {"createdAt": created, "archive": str(archive), "bytes": archive.stat().st_size,
              "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "payloadFiles": len(sources),
              "sourceFiles": len(sources) - 1 - int(screenshot.exists()), "warSha256": war_hash,
              "baseCommit": manifest["baseCommit"], "uncommittedSourceSnapshot": True,
              "archiveIntegrityChecked": True, "allPayloadHashesChecked": True,
              "ignoredRuntimeCredentialsAndCachesIncluded": False}
    metadata = root / "docs/delivery/A交付包校验.json"
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
