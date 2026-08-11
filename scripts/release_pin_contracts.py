"""Shared release template structure contracts."""

from __future__ import annotations

import json
from pathlib import Path

TEMPLATE_PATH = "android/manager/app/src/main/assets/release/modloader-template-10005.apk"
METADATA_PATH = "release/loader-template-10005.json"
REQUIRED_METADATA = (
    "packageName",
    "splitName",
    "versionCode",
    "versionName",
    "providerProtocolVersion",
)
LEGACY_DIGEST_KEYS = ("templateSha256", "nativeSha256")


def read_utf8(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as stream:
        return stream.read()


def write_utf8(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        stream.write(text)


def read_metadata(path: Path) -> dict:
    value = json.loads(read_utf8(path))
    if not isinstance(value, dict):
        raise ValueError(f"Release metadata must be an object: {path}")
    return value


def clean_metadata(value: dict) -> dict:
    return {key: value[key] for key in REQUIRED_METADATA if key in value}


def target_paths() -> tuple[str, ...]:
    return (TEMPLATE_PATH, METADATA_PATH)


__all__ = [
    "LEGACY_DIGEST_KEYS",
    "METADATA_PATH",
    "REQUIRED_METADATA",
    "TEMPLATE_PATH",
    "clean_metadata",
    "read_metadata",
    "read_utf8",
    "target_paths",
    "write_utf8",
]
