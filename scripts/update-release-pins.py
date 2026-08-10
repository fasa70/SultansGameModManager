"""Stage the frozen loader template and every checked-in digest pin."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from release_pin_contracts import (
    PIN_CONTRACTS,
    read_utf8,
    validate_digest,
    write_utf8,
)

TEMPLATE_PATH = "android/manager/app/src/main/assets/release/modloader-template-10005.apk"


def stage_release(
    root: Path,
    template: Path,
    stage: Path,
    template_sha256: str,
    native_sha256: str,
) -> None:
    validate_digest(template_sha256, "template SHA-256")
    validate_digest(native_sha256, "native SHA-256")
    if not template.is_file():
        raise SystemExit(f"Missing candidate template: {template}")
    if stage.exists() and any(stage.iterdir()):
        raise SystemExit(f"Release staging directory is not empty: {stage}")
    stage.mkdir(parents=True, exist_ok=True)

    staged_template = stage / TEMPLATE_PATH
    staged_template.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(template, staged_template)

    for relative_path in sorted({item.relative_path for item in PIN_CONTRACTS}):
        source = root / relative_path
        if not source.is_file():
            raise SystemExit(f"Missing release pin file: {source}")
        text = read_utf8(source)
        for item in (entry for entry in PIN_CONTRACTS if entry.relative_path == relative_path):
            value = template_sha256 if item.digest_kind == "template" else native_sha256
            try:
                text = item.update(text, value)
            except ValueError as error:
                raise SystemExit(str(error)) from error
        write_utf8(stage / relative_path, text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--template", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--template-sha256", required=True)
    parser.add_argument("--native-sha256", required=True)
    args = parser.parse_args()
    stage_release(
        args.root.resolve(),
        args.template.resolve(),
        args.stage.resolve(),
        args.template_sha256,
        args.native_sha256,
    )
    print(f"staged={args.stage} template={args.template_sha256} native={args.native_sha256}")


if __name__ == "__main__":
    main()
