"""Stage the loader template and structure metadata."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from release_pin_contracts import METADATA_PATH, TEMPLATE_PATH, clean_metadata, read_metadata, write_utf8


def stage_release(root: Path, template: Path, stage: Path) -> None:
    if not template.is_file():
        raise SystemExit(f"Missing candidate template: {template}")
    if stage.exists() and any(stage.iterdir()):
        raise SystemExit(f"Release staging directory is not empty: {stage}")
    stage.mkdir(parents=True, exist_ok=True)

    staged_template = stage / TEMPLATE_PATH
    staged_template.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(template, staged_template)

    metadata = clean_metadata(read_metadata(root / METADATA_PATH))
    missing = [key for key in ("packageName", "splitName", "versionCode", "versionName", "providerProtocolVersion") if key not in metadata]
    if missing:
        raise SystemExit(f"Release metadata is missing: {', '.join(missing)}")
    write_utf8(stage / METADATA_PATH, json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()
    stage_release(args.root.resolve(), args.template.resolve(), args.stage.resolve())
    print(f"staged={args.stage} template={args.template}")


if __name__ == "__main__":
    main()
