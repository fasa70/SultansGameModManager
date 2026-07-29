#!/usr/bin/env python3
"""Build an unsigned same-package loader split template from bootstrap AAR."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.gametree.sultan.pd"
    split="modloader"
    android:versionCode="{version_code}"
    android:versionName="{version_name}">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35" />
    <permission
        android:name="com.gametree.sultan.pd.mod.permission.MANAGE_MODS"
        android:protectionLevel="normal" />
    <application>
        <provider
            android:name="com.gametree.sultan.pd.mod.ModLoaderProvider"
            android:authorities="com.gametree.sultan.pd.modloader"
            android:exported="false" />
        <provider
            android:name="com.gametree.sultan.pd.mod.ModStorageProvider"
            android:authorities="com.gametree.sultan.pd.modstorage"
            android:exported="true"
            android:permission="com.gametree.sultan.pd.mod.permission.MANAGE_MODS"
            android:process=":modstorage" />
    </application>
</manifest>
"""


def run(*args: str) -> None:
    subprocess.run(args, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap-aar", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--d8", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    args = parser.parse_args()

    for path in (args.bootstrap_aar, args.android_jar, args.aapt2, args.d8):
        if not path.is_file():
            raise SystemExit(f"Missing input: {path}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sultan-split-") as temporary:
        root = Path(temporary)
        extracted = root / "aar"
        with zipfile.ZipFile(args.bootstrap_aar) as archive:
            archive.extractall(extracted)
        classes = extracted / "classes.jar"
        if not classes.is_file():
            raise SystemExit("Bootstrap AAR does not contain classes.jar")
        asset = extracted / "assets" / "modloader" / "arm64-v8a" / "modloader.bin"
        if not asset.is_file():
            raise SystemExit("Bootstrap AAR does not contain modloader.bin")

        manifest = root / "AndroidManifest.xml"
        manifest.write_text(
            MANIFEST.format(version_code=args.version_code, version_name=args.version_name),
            encoding="utf-8",
        )
        resource_apk = root / "resources.apk"
        run(
            str(args.aapt2),
            "link",
            "--manifest",
            str(manifest),
            "-I",
            str(args.android_jar),
            "--min-sdk-version",
            "21",
            "--target-sdk-version",
            "35",
            "-o",
            str(resource_apk),
        )
        dex = root / "dex"
        dex.mkdir()
        run(
            str(args.d8),
            "--min-api",
            "21",
            "--lib",
            str(args.android_jar),
            "--output",
            str(dex),
            str(classes),
        )
        classes_dex = dex / "classes.dex"
        if not classes_dex.is_file():
            raise SystemExit("D8 did not produce classes.dex")

        with zipfile.ZipFile(resource_apk) as source, zipfile.ZipFile(
            args.output, "w", compression=zipfile.ZIP_DEFLATED
        ) as destination:
            for item in source.infolist():
                destination.writestr(item, source.read(item.filename))
            destination.write(classes_dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
            destination.write(
                asset,
                "assets/modloader/arm64-v8a/modloader.bin",
                compress_type=zipfile.ZIP_STORED,
            )


if __name__ == "__main__":
    main()
