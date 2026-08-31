#!/usr/bin/env python3
"""Build or verify an unsigned same-package loader split template."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.gametree.sultan.pd"
    split="modloader"
    android:versionCode="{version_code}"
    android:versionName="{version_name}">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35" />
    <application>
        <provider android:name="com.gametree.sultan.pd.mod.ModLoaderProvider" android:authorities="com.gametree.sultan.pd.modloader" android:exported="false" />
        <provider android:name="com.gametree.sultan.pd.mod.ModStorageProvider" android:authorities="com.gametree.sultan.pd.modstorage" android:exported="true" android:process=":modstorage" />
    </application>
</manifest>
"""

REVISION_ENTRY = "assets/modloader/revision"
# 冻结 loader split 的显式修订号。仅当 loader 侧引入需要重新注入到已修补
# 游戏的变更时手动 +1；判据见 docs/build.md 的 "Loader revision" 小节。
LOADER_REVISION = 1
REVISION_FORMAT = re.compile(r"[1-9][0-9]{0,8}")


def run(*args: str) -> None:
    subprocess.run(args, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_info(info: zipfile.ZipInfo, compression: int) -> zipfile.ZipInfo:
    normalized = zipfile.ZipInfo(info.filename, (1980, 1, 1, 0, 0, 0))
    normalized.compress_type = compression
    normalized.create_system = 0
    normalized.create_version = 20
    normalized.extract_version = max(info.extract_version, 20)
    normalized.flag_bits = info.flag_bits & 0x800
    normalized.external_attr = 0
    normalized.comment = b""
    normalized.extra = b""
    return normalized


def validate_bootstrap_manifest(path: Path) -> None:
    root = ET.parse(path).getroot()
    providers = {}
    for provider in root.findall(".//provider"):
        name = provider.get(f"{{{ANDROID_NS}}}name", "")
        if name.startswith("."):
            name = "com.gametree.sultan.pd.mod" + name
        providers[name] = {
            "authority": provider.get(f"{{{ANDROID_NS}}}authorities"),
            "exported": provider.get(f"{{{ANDROID_NS}}}exported"),
            "process": provider.get(f"{{{ANDROID_NS}}}process"),
        }
    expected = {
        "com.gametree.sultan.pd.mod.ModLoaderProvider": {"authority": "com.gametree.sultan.pd.modloader", "exported": "false", "process": None},
        "com.gametree.sultan.pd.mod.ModStorageProvider": {"authority": "com.gametree.sultan.pd.modstorage", "exported": "true", "process": ":modstorage"},
    }
    if providers != expected:
        raise SystemExit(f"Bootstrap manifest does not match frozen split contract: {path}")


def validate_template(path: Path, revision: int) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing template: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise SystemExit(f"Corrupt template ZIP: {path}")
            infos = archive.infolist()
            names = [info.filename for info in infos]
            required = {"AndroidManifest.xml", "resources.arsc", "classes.dex", "assets/modloader/arm64-v8a/modloader.bin", REVISION_ENTRY}
            if len(names) != len(set(names)):
                raise SystemExit(f"Template contains duplicate entries: {path}")
            if not required.issubset(names):
                raise SystemExit(f"Template is missing entries: {sorted(required - set(names))}")
            native_entry = archive.getinfo("assets/modloader/arm64-v8a/modloader.bin")
            if native_entry.compress_type != zipfile.ZIP_STORED or native_entry.file_size <= 0:
                raise SystemExit("Template native entry must be non-empty ZIP_STORED")
            revision_entry = archive.getinfo(REVISION_ENTRY)
            if revision_entry.compress_type != zipfile.ZIP_STORED:
                raise SystemExit("Template revision entry must be ZIP_STORED")
            declared = archive.read(REVISION_ENTRY).decode("ascii", errors="replace").strip()
            if REVISION_FORMAT.fullmatch(declared) is None:
                raise SystemExit(f"Template revision must be a positive decimal integer: {declared!r}")
            if int(declared) != revision:
                raise SystemExit(f"Template revision {declared} does not match expected {revision}")
            if any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC|SF|MF)", name, re.IGNORECASE) for name in names):
                raise SystemExit("Template must be unsigned")
    except zipfile.BadZipFile as error:
        raise SystemExit(f"Invalid template ZIP: {path}: {error}") from error
    return sha256(path)


def build(args: argparse.Namespace) -> None:
    for path in (args.bootstrap_aar, args.android_jar, args.aapt2, args.d8):
        if not path.is_file():
            raise SystemExit(f"Missing input: {path}")
    if args.bootstrap_manifest is not None:
        validate_bootstrap_manifest(args.bootstrap_manifest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fd, candidate_name = tempfile.mkstemp(prefix=f".{args.output.name}.", suffix=".partial", dir=args.output.parent)
    os.close(fd)
    candidate = Path(candidate_name)
    try:
        with tempfile.TemporaryDirectory(prefix="sultan-split-") as temporary:
            root = Path(temporary)
            extracted = root / "aar"
            with zipfile.ZipFile(args.bootstrap_aar) as archive:
                archive.extractall(extracted)
            classes = extracted / "classes.jar"
            asset = extracted / "assets" / "modloader" / "arm64-v8a" / "modloader.bin"
            if not classes.is_file() or not asset.is_file():
                raise SystemExit("Bootstrap AAR is missing classes.jar or modloader.bin")
            manifest = root / "AndroidManifest.xml"
            manifest.write_text(MANIFEST.format(version_code=args.version_code, version_name=args.version_name), encoding="utf-8")
            resource_apk = root / "resources.apk"
            run(str(args.aapt2), "link", "--manifest", str(manifest), "-I", str(args.android_jar), "--min-sdk-version", "21", "--target-sdk-version", "35", "-o", str(resource_apk))
            dex = root / "dex"
            dex.mkdir()
            run(str(args.d8), "--min-api", "21", "--lib", str(args.android_jar), "--output", str(dex), str(classes))
            classes_dex = dex / "classes.dex"
            if not classes_dex.is_file():
                raise SystemExit("D8 did not produce classes.dex")
            with zipfile.ZipFile(resource_apk) as source, zipfile.ZipFile(candidate, "w", compression=zipfile.ZIP_DEFLATED) as destination:
                for item in source.infolist():
                    destination.writestr(normalize_info(item, item.compress_type), source.read(item.filename))
                # revision 必须写在 native 负载之前：管理器用 ZipInputStream
                # 从 AssetManager 顺序扫描该 entry。
                destination.writestr(
                    normalize_info(zipfile.ZipInfo(REVISION_ENTRY), zipfile.ZIP_STORED),
                    f"{args.revision}\n".encode("ascii"),
                )
                destination.write(classes_dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
                destination.writestr(normalize_info(zipfile.ZipInfo("assets/modloader/arm64-v8a/modloader.bin"), zipfile.ZIP_STORED), asset.read_bytes())
        template = validate_template(candidate, args.revision)
        os.replace(candidate, args.output)
        print(f"template={template}")
    finally:
        candidate.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap-aar", type=Path)
    parser.add_argument("--bootstrap-manifest", type=Path)
    parser.add_argument("--android-jar", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--d8", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version-code", type=int, default=10005)
    parser.add_argument("--version-name", default="1.0.5")
    parser.add_argument("--revision", type=int, default=LOADER_REVISION)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        print(f"template={validate_template(args.output, args.revision)}")
        return
    required = (args.bootstrap_aar, args.android_jar, args.aapt2, args.d8)
    if any(path is None for path in required):
        parser.error("build mode requires --bootstrap-aar, --android-jar, --aapt2 and --d8")
    build(args)


if __name__ == "__main__":
    main()
