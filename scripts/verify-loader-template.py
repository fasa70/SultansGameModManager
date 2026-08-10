#!/usr/bin/env python3
"""Verify the frozen loader template and every checked-in digest pin."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_template(path: Path, expected: dict) -> None:
    if digest_file(path) != expected["templateSha256"]:
        raise SystemExit(f"Template SHA-256 mismatch: {path}")
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise SystemExit(f"Template ZIP is corrupt: {path}")
        names = [info.filename for info in archive.infolist()]
        if len(names) != len(set(names)):
            raise SystemExit("Template contains duplicate ZIP entries")
        native_name = "assets/modloader/arm64-v8a/modloader.bin"
        native = archive.read(native_name)
        if archive.getinfo(native_name).compress_type != zipfile.ZIP_STORED:
            raise SystemExit("Template native entry is not ZIP_STORED")
        if any(re.match(r"META-INF/[^/]+\.(RSA|DSA|EC)$", name, re.I) for name in names):
            raise SystemExit("Template contains a signing certificate")
        if digest_bytes(native) != expected["nativeSha256"]:
            raise SystemExit("Embedded native SHA-256 mismatch")


def verify_pins(root: Path, expected: dict) -> None:
    template_paths = [
        root / "android/manager/app/src/main/java/com/sultansgame/modmanager/platform/patch/GameProfileRegistry.kt",
        root / "android/manager/app/src/main/java/com/sultansgame/modmanager/platform/patch/AndroidLoaderSplitArtifactFactory.kt",
        root / "android/manager/app/src/androidTest/java/com/sultansgame/modmanager/platform/patch/DeviceSigningKeyStoreTest.kt",
    ]
    native_paths = [
        root / "android/manager/app/src/main/java/com/sultansgame/modmanager/platform/patch/GameProfileRegistry.kt",
        root / "android/manager/app/src/androidTest/java/com/sultansgame/modmanager/platform/patch/DeviceSigningKeyStoreTest.kt",
    ]
    for path in template_paths:
        text = path.read_text(encoding="utf-8")
        if expected["templateSha256"] not in text:
            raise SystemExit(f"Template pin missing from {path}")
    for path in native_paths:
        text = path.read_text(encoding="utf-8")
        if expected["nativeSha256"] not in text:
            raise SystemExit(f"Native pin missing from {path}")
    models = root / "android/manager/core/model/src/main/kotlin/com/sultansgame/modmanager/model/ModStorageModels.kt"
    if f"MOD_STORAGE_PROTOCOL_VERSION = {expected['providerProtocolVersion']}" not in models.read_text(encoding="utf-8"):
        raise SystemExit("Manager ModStorage protocol pin mismatch")


def verify_manager_apk(path: Path, expected: dict) -> None:
    with zipfile.ZipFile(path) as archive:
        template = archive.read("assets/release/modloader-template-10005.apk")
    if digest_bytes(template) != expected["templateSha256"]:
        raise SystemExit("Manager APK contains a different loader template")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--manager-apk", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    metadata_path = root / "release/loader-template-10005.json"
    expected = json.loads(metadata_path.read_text(encoding="utf-8"))
    verify_template(root / "android/manager/app/src/main/assets/release/modloader-template-10005.apk", expected)
    verify_pins(root, expected)
    if args.manager_apk is not None:
        verify_manager_apk(args.manager_apk, expected)
    print(
        f"template={expected['templateSha256']} native={expected['nativeSha256']} "
        f"protocol={expected['providerProtocolVersion']}"
    )


if __name__ == "__main__":
    main()
