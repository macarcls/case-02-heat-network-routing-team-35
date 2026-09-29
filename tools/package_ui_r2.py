"""Refresh the bundled Spring Boot JAR after a frontend-only UI revision."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "server/src/main/resources/static"
JAR = ROOT / "release/teplotrassa-server-1.9.3-objectives.jar"
MANIFEST = ROOT / "release/manifest.json"
BUILD_ID = "objectives-20260928-ui-r2"
ASSETS = ("tree-1.9.3.html", "tree-1.9.3.css", "tree-1.9.3.js")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_digest() -> str:
    h = hashlib.sha256()
    folder = ROOT / "server/src/main"
    for file in sorted(p for p in folder.rglob("*") if p.is_file()):
        h.update(str(file.relative_to(folder)).encode())
        h.update(b"\0")
        h.update(file.read_bytes())
        h.update(b"\0")
    return h.hexdigest()


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="teplotrassa-ui-", dir=ROOT.parent) as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        subprocess.run(
            ["java", "--module", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11",
             "-d", str(classes), str(ROOT / "server/src/main/java/ru/teplotrassa/api/BuildInfo.java")],
            check=True,
        )
        compiled = (classes / "ru/teplotrassa/api/BuildInfo.class").read_bytes()
        if struct.unpack(">H", compiled[6:8])[0] != 55 or BUILD_ID.encode() not in compiled:
            raise ValueError("BuildInfo is not the expected Java 11 UI release")
        replacement = {"BOOT-INF/classes/ru/teplotrassa/api/BuildInfo.class": compiled}
        for asset in ASSETS:
            replacement[f"BOOT-INF/classes/static/{asset}"] = (STATIC / asset).read_bytes()
        output = Path(temp) / "updated.jar"
        with ZipFile(JAR, "r") as original, ZipFile(output, "w", allowZip64=True) as updated:
            names = set(original.namelist())
            if not set(replacement).issubset(names):
                raise ValueError("The current Spring Boot JAR is missing its UI assets")
            updated.comment = original.comment
            for info in original.infolist():
                updated.writestr(info, replacement.get(info.filename, original.read(info.filename)))
        with ZipFile(output) as checked:
            if checked.testzip() is not None:
                raise ValueError("Updated JAR failed ZIP integrity check")
            for name, expected in replacement.items():
                if checked.read(name) != expected:
                    raise ValueError("Bundled asset does not match source: " + name)
        os.replace(output, JAR)

    data = json.loads(MANIFEST.read_text())
    data.update(
        buildId=BUILD_ID,
        jarSha256=sha256(JAR.read_bytes()),
        sourceMainSha256=source_digest(),
        jarIntegrityPassed=True,
        uiCardSmokeTest="validation/ui-r2/check-ui.mjs",
    )
    MANIFEST.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
    print("Bundled JAR updated:", BUILD_ID, data["jarSha256"])


if __name__ == "__main__":
    main()
