#!/usr/bin/env python3
"""Package a staged Chaquopy Android extension as a wheel."""

from __future__ import annotations

import argparse
import base64
import hashlib
import re
import tomllib
import zipfile
from pathlib import Path


def normalized(value: str) -> str:
    return re.sub(r"[-.]+", "_", value)


def digest(data: bytes) -> str:
    encoded = base64.urlsafe_b64encode(hashlib.sha256(data).digest())
    return encoded.rstrip(b"=").decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--install-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--python-tag", required=True)
    parser.add_argument("--abi-tag", required=True)
    parser.add_argument("--platform-tag", required=True)
    args = parser.parse_args()

    with args.project.open("rb") as stream:
        project = tomllib.load(stream)["project"]
    name = str(project["name"])
    version = str(project["version"])
    distribution = normalized(name)
    dist_info = f"{distribution}-{version}.dist-info"
    package_root = args.install_root / "sultan_core"
    files = sorted(path for path in package_root.rglob("*") if path.is_file())
    if not package_root.is_dir() or not files:
        raise SystemExit(f"staged package is empty: {package_root}")
    if any(path.is_symlink() for path in files):
        raise SystemExit("staged package contains a symlink")

    wheel_name = (
        f"{distribution}-{version}-{args.python_tag}-{args.abi_tag}-"
        f"{args.platform_tag}.whl"
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / wheel_name
    if output.exists():
        output.unlink()

    records: list[str] = []
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as wheel:
        for path in files:
            archive_name = path.relative_to(args.install_root).as_posix()
            data = path.read_bytes()
            wheel.writestr(archive_name, data)
            records.append(f"{archive_name},sha256={digest(data)},{len(data)}")

        metadata_name = f"{dist_info}/METADATA"
        metadata = (
            "Metadata-Version: 2.1\n"
            f"Name: {name}\n"
            f"Version: {version}\n"
            f"Requires-Python: {project.get('requires-python', '')}\n"
        ).encode()
        wheel.writestr(metadata_name, metadata)
        records.append(f"{metadata_name},sha256={digest(metadata)},{len(metadata)}")

        wheel_name_entry = f"{dist_info}/WHEEL"
        wheel_metadata = (
            "Wheel-Version: 1.0\n"
            "Generator: sultan-game-mod-manager\n"
            "Root-Is-Purelib: false\n"
            f"Tag: {args.python_tag}-{args.abi_tag}-{args.platform_tag}\n"
        ).encode()
        wheel.writestr(wheel_name_entry, wheel_metadata)
        records.append(
            f"{wheel_name_entry},sha256={digest(wheel_metadata)},{len(wheel_metadata)}"
        )

        record_name = f"{dist_info}/RECORD"
        records.append(f"{record_name},,")
        wheel.writestr(record_name, ("\n".join(records) + "\n").encode())

    print(output)


if __name__ == "__main__":
    main()
