"""Stage the loader template and structure metadata for one release build."""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from release_pin_contracts import METADATA_NAME, TEMPLATE_NAME

RELEASE_METADATA = {
    "packageName": "com.gametree.sultan.pd",
    "splitName": "modloader",
    "versionCode": 10005,
    "versionName": "1.0.5",
    "loaderRevision": 2,
}


def stage_release(template: Path, stage: Path) -> None:
    if not template.is_file():
        raise SystemExit(f"Missing candidate template: {template}")
    if stage.exists() and any(stage.iterdir()):
        raise SystemExit(f"Release staging directory is not empty: {stage}")
    stage.mkdir(parents=True, exist_ok=True)

    shutil.copy2(template, stage / TEMPLATE_NAME)
    (stage / METADATA_NAME).write_text(
        json.dumps(RELEASE_METADATA, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    args = parser.parse_args()
    stage_release(args.template.resolve(), args.stage.resolve())
    print(f"staged={args.stage} template={args.template} metadata={METADATA_NAME}")


if __name__ == "__main__":
    main()
