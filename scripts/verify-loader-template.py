#!/usr/bin/env python3
"""Verify loader template structure and release metadata."""

from __future__ import annotations

import argparse
import re
import zipfile
from pathlib import Path

from release_pin_contracts import METADATA_PATH, REQUIRED_METADATA, TEMPLATE_PATH, read_metadata, target_paths

NATIVE_ENTRY = "assets/modloader/arm64-v8a/modloader.bin"
REQUIRED_ENTRIES = {"AndroidManifest.xml", "resources.arsc", "classes.dex", NATIVE_ENTRY}
SIGNATURE_ENTRY = re.compile(r"META-INF/[^/]+\.(RSA|DSA|EC|SF|MF)$", re.IGNORECASE)


def verify_template(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"Missing template: {path}")
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
            if any(SIGNATURE_ENTRY.fullmatch(name) for name in names):
                raise SystemExit("Template contains APK signature entries")
            native_info = archive.getinfo(NATIVE_ENTRY)
            if native_info.compress_type != zipfile.ZIP_STORED:
                raise SystemExit("Template native entry is not ZIP_STORED")
            if native_info.file_size <= 0:
                raise SystemExit("Template native entry is empty")
            for name in REQUIRED_ENTRIES:
                if not archive.read(name):
                    raise SystemExit(f"Template entry is empty: {name}")
    except zipfile.BadZipFile as error:
        raise SystemExit(f"Invalid template ZIP: {path}: {error}") from error


def verify_metadata(path: Path) -> dict:
    metadata = read_metadata(path)
    missing = [key for key in REQUIRED_METADATA if key not in metadata]
    if missing:
        raise SystemExit(f"Release metadata is missing: {', '.join(missing)}")
    return metadata


def verify_manager_apk(path: Path, template_path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive, template_path.open("rb") as expected:
            actual = archive.open(f"assets/release/{Path(TEMPLATE_PATH).name}")
            with actual:
                if actual.read() != expected.read():
                    raise SystemExit("Manager APK contains a different loader template")
    except (KeyError, zipfile.BadZipFile, OSError) as error:
        raise SystemExit(f"Manager APK does not contain a valid loader template: {error}") from error


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
    metadata = verify_metadata(root / METADATA_PATH)
    verify_template(root / TEMPLATE_PATH)
    if args.manager_apk is not None:
        verify_manager_apk(args.manager_apk.resolve(), root / TEMPLATE_PATH)
    print(f"package={metadata['packageName']} split={metadata['splitName']} protocol={metadata['providerProtocolVersion']}")


if __name__ == "__main__":
    main()
