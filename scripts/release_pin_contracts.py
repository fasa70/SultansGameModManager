"""Shared release template structure contracts."""

from __future__ import annotations

import json
from pathlib import Path

TEMPLATE_NAME = "modloader-template-10005.apk"
METADATA_NAME = "loader-template-10005.json"
REQUIRED_METADATA = (
    "packageName",
    "splitName",
    "versionCode",
    "versionName",
    "providerProtocolVersion",
)


def read_utf8(path: Path) -> str:
    with path.open("r", encoding="utf-8", newline="") as stream:
        return stream.read()


def read_metadata(path: Path) -> dict:
    value = json.loads(read_utf8(path))
    if not isinstance(value, dict):
        raise ValueError(f"Release metadata must be an object: {path}")
    return value


__all__ = [
    "METADATA_NAME",
    "REQUIRED_METADATA",
    "TEMPLATE_NAME",
    "read_metadata",
    "read_utf8",
]
