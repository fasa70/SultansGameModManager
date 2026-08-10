#!/usr/bin/env python3
"""Verify the frozen loader template and every checked-in digest pin."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

from release_pin_contracts import PIN_CONTRACTS, read_utf8, target_paths, validate_digest

NATIVE_ENTRY = "assets/modloader/arm64-v8a/modloader.bin"
REQUIRED_ENTRIES = {"AndroidManifest.xml", "resources.arsc", "classes.dex", NATIVE_ENTRY}


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
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise SystemExit(f"Template ZIP is corrupt: {path}")
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                raise SystemExit("Template contains duplicate ZIP entries")
            missing = REQUIRED_ENTRIES.difference(names)
            if missing:
                raise SystemExit(f"Template is missing entries: {sorted(missing)}")
            native_info = archive.getinfo(NATIVE_ENTRY)
            if native_info.compress_type != zipfile.ZIP_STORED:
                raise SystemExit("Template native entry is not ZIP_STORED")
            if any(
                re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC|SF|MF)", name, re.IGNORECASE)
                for name in names
            ):
                raise SystemExit("Template contains APK signature entries")
            native = archive.read(NATIVE_ENTRY)
    except zipfile.BadZipFile as error:
        raise SystemExit(f"Invalid template ZIP: {path}: {error}") from error
    if digest_bytes(native) != expected["nativeSha256"]:
        raise SystemExit("Embedded native SHA-256 mismatch")


def verify_pins(root: Path, expected: dict) -> None:
    for item in PIN_CONTRACTS:
        path = root / item.relative_path
        try:
            item.read(read_utf8(path), expected[f"{item.digest_kind}Sha256"])
        except (OSError, ValueError) as error:
            raise SystemExit(str(error)) from error
    metadata = json.loads(read_utf8(root / "release/loader-template-10005.json"))
    for key in ("packageName", "splitName", "versionCode", "versionName", "providerProtocolVersion"):
        if key not in metadata:
            raise SystemExit(f"Release metadata is missing {key}")


def verify_manager_apk(path: Path, expected: dict) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            template = archive.read("assets/release/modloader-template-10005.apk")
    except (KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Manager APK does not contain a valid frozen template: {error}") from error
    if digest_bytes(template) != expected["templateSha256"]:
        raise SystemExit("Manager APK contains a different loader template")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--manager-apk", type=Path)
    parser.add_argument("--print-targets", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.print_targets:
        print("\n".join(target_paths()))
        return
    metadata_path = root / "release/loader-template-10005.json"
    expected = json.loads(read_utf8(metadata_path))
    validate_digest(expected["templateSha256"], "metadata template SHA-256")
    validate_digest(expected["nativeSha256"], "metadata native SHA-256")
    verify_template(root / "android/manager/app/src/main/assets/release/modloader-template-10005.apk", expected)
    verify_pins(root, expected)
    if args.manager_apk is not None:
        verify_manager_apk(args.manager_apk.resolve(), expected)
    print(
        f"template={expected['templateSha256']} native={expected['nativeSha256']} "
        f"protocol={expected['providerProtocolVersion']}"
    )


if __name__ == "__main__":
    main()
