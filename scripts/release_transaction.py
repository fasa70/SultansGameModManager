"""Stage and recover the multi-file frozen loader release transaction."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile

from release_pin_contracts import PIN_CONTRACTS


TEMPLATE = "android/manager/app/src/main/assets/release/modloader-template-10005.apk"
TARGETS = (TEMPLATE,) + tuple(
    dict.fromkeys(item.relative_path for item in PIN_CONTRACTS)
)


def atomic_copy(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".partial", dir=destination.parent
    )
    os.close(fd)
    temporary = Path(temporary_name)
    try:
        shutil.copy2(source, temporary)
        with temporary.open("rb") as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def marker_data(marker: Path) -> dict:
    with marker.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def apply(root: Path, stage: Path, marker: Path) -> None:
    backup = stage / "backup"
    backup.mkdir(parents=True, exist_ok=False)
    files = []
    for relative in TARGETS:
        source = root / relative
        staged = stage / relative
        if not source.is_file() or not staged.is_file():
            raise SystemExit(f"Transaction file missing: {relative}")
        backup_path = backup / relative
        backup_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, backup_path)
        files.append(relative)
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text(
        json.dumps(
            {"root": str(root), "backup": str(backup), "files": files},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    for relative in files:
        os.replace(stage / relative, root / relative)


def rollback(marker: Path, keep_stage: bool) -> None:
    data = marker_data(marker)
    root = Path(data["root"])
    backup = Path(data["backup"])
    for relative in data["files"]:
        source = backup / relative
        if source.is_file():
            atomic_copy(source, root / relative)
    marker.unlink(missing_ok=True)
    if not keep_stage:
        shutil.rmtree(backup.parent, ignore_errors=True)


def cleanup(marker: Path) -> None:
    data = marker_data(marker)
    shutil.rmtree(Path(data["backup"]).parent, ignore_errors=True)
    marker.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("apply", "rollback", "cleanup"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--stage", type=Path)
    parser.add_argument("--marker", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    marker = args.marker.resolve()
    if args.action == "apply":
        if args.stage is None:
            parser.error("apply requires --stage")
        apply(root, args.stage.resolve(), marker)
    elif args.action == "rollback":
        rollback(marker, keep_stage=True)
    else:
        cleanup(marker)


if __name__ == "__main__":
    main()
