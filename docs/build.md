# Build Guide

## Environment

The Manager uses Chaquopy 17.0.0 with CPython 3.11, `minSdk 24`, and only
`arm64-v8a`. The primary Windows build uses JDK 21, NDK 27.0.12077973, and
Android SDK CMake 3.22.1. This is the authoritative place for tool versions —
other documents link here instead of repeating them:

| Setting | Value |
|---|---|
| `compileSdk` | 37 — Manager compilation |
| `targetSdk` | 36 |
| `minSdk` | 24 (Android 7.0); the loader split's own manifest stays at 21 |
| SDK platforms needed | **both 37 and 35** — 35 provides the `android.jar` used to build the loader split template |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 (Android SDK) |
| JDK | 21 |

Dobby is pulled in as a git submodule under `native/third_party/dobby`, so
right after cloning run:

```bash
git submodule update --init --recursive
```

`bash scripts/check-env.sh` from the repository root verifies most
prerequisites, but note that it only checks for Platform 35 — Platform 37 must
be present as well or Gradle will fail even with a green check-env.

## Build the Chaquopy Android wheel

The Manager's upstream C++ JSON and `json_ops` bridge is packaged as a native
Chaquopy wheel. A host `win_amd64` or `linux_x86_64` wheel is not valid for the
Android app. The wheel must contain an AArch64 ELF extension and use the tag
`cp311-cp311-android_24_arm64_v8a`.

Install `nanobind` into the CPython 3.11 interpreter used by the script, then
run this from the repository root in Git Bash on Windows:

```bash
PYTHON="C:/Path/To/Python311/python.exe" \
  ANDROID_HOME="C:/Users/Admin/AppData/Local/Android/Sdk" \
  ANDROID_NDK_HOME="C:/Users/Admin/AppData/Local/Android/Sdk/ndk/27.0.12077973" \
  bash scripts/build-sultan-core-wheel.sh
```

The script downloads and caches Chaquopy target `3.11.14-0` from Maven
Central, extracts matching `Python.h` and `libpython3.11.so`, cross-compiles
the nanobind module with the Android NDK, and writes the wheel to
`android/manager/tools/sultan-core-wheel/dist/`. The cache and build
directories are ignored. Set `CHAQUOPY_TARGET_VERSION` only when deliberately
selecting another compatible Chaquopy target.

WSL2 Ubuntu is optional. It is useful for the official Chaquopy wheel builder,
which requires Linux x86-64, but the repository script directly uses the
Windows Android SDK CMake/Ninja and NDK and does not require WSL. Never copy a
host wheel into the Android app.

The wheel metadata uses the PEP 440 version `1.4.4.post1`; Chaquopy's pip
rejects the historical `1.4.4.android1` version string even when the platform
tag is correct.

## Release pipeline

Run `bash scripts/build-release.sh` from the repository root after configuring
the JDK, Android SDK and NDK. The pipeline builds the native loader, validates
its ELF/ABI/16 KB alignment contract, builds the Bootstrap AAR and unsigned
loader split, builds the Chaquopy Android wheel, stages the template and
structural metadata, assembles the signed Manager APK, and verifies the final
APK. Release credentials remain local and untracked under `/release/`.

The script passes `--no-configuration-cache` and a fixed Gradle JVM heap to
every Gradle invocation. `RELEASE_GRADLE_JVMARGS` overrides the heap settings
(default `-Xmx4g -Dfile.encoding=UTF-8`):

```bash
RELEASE_GRADLE_JVMARGS="-Xmx6g -Dfile.encoding=UTF-8" bash scripts/build-release.sh
```

A direct `:app:assembleRelease` invocation is not the complete release pipeline:
it requires `-PreleaseTemplate=<generated template APK path>` and a generated
Chaquopy wheel in `android/manager/tools/sultan-core-wheel/dist/`. Prefer
`scripts/build-release.sh` so native, template, and wheel generation are
validated together. Manual `:app:assembleRelease` runs do not pass the script's
JVM args — add the same `-Dorg.gradle.jvmargs` yourself if the default 2 GiB
heap from `gradle.properties` is not enough.

## Loader template checks

The unsigned template must contain `AndroidManifest.xml`, `resources.arsc`,
`classes.dex`, `assets/modloader/arm64-v8a/modloader.bin`, and
`assets/modloader/revision`. It must be a readable ZIP with no duplicate
entries or APK signature entries, and the native and revision entries must be
non-empty and `ZIP_STORED`. Package name, split name, version and provider
contract must match the supported profile. The manifest also declares the
exported `Theme.NoDisplay` `ModServiceKickstartActivity` in the `:modstorage`
process — the Manager's no-game-UI wake-up entry for the stopped-package
state — and `validate_bootstrap_manifest` in `build_split_template.py`
enforces both the provider and activity contracts exactly against the
bootstrap source manifest.

The Manager performs structural checks before signing. It then signs the
template with the device key and verifies v1/v2 signatures, payload
preservation, certificate identity, and the final split set. The release
verifier compares embedded template bytes with the staged template; it does
not compare a fixed native/template digest.

## Loader revision

`assets/modloader/revision` inside the loader split carries one decimal
integer stored as `ZIP_STORED`, written before the native payload so the
Manager can stream-scan it from its embedded template asset.
`android/bootstrap/build_split_template.py` writes it from `LOADER_REVISION`
(`--revision` overrides), the release metadata pins it as `loaderRevision`,
and `scripts/verify-loader-template.py` fails when the two disagree. The
Manager compares the revision in its embedded template with the revision in
the installed `split_modloader.apk`; a readable split without the entry counts
as revision 0. `ModStorageProvider` gates bridge calls on the same revision
instead of a separate protocol number: the Manager sends
`expectedRevision` with each call, and the provider compares it against the
revision packaged in its own split. There is no separate protocol version.

This section is the single authoritative statement of the revision mechanism
and its increment criteria; other documents reference it rather than
restating it.

Increment `LOADER_REVISION`, `RELEASE_METADATA["loaderRevision"]` in
`scripts/update-release-pins.py`, and the `--revision` argument in
`scripts/build-release.sh` together, by one, only when a change must be
re-injected into an already patched game:

- `native/` changes the `libmodloader.so` contract: hooks, profile gate, mod
  root/path/index layout, resource override behaviour, ABI or 16 KB alignment.
- `android/bootstrap/` changes `ModLoaderProvider`, `ModStorageProvider`, the
  bridge call contract, or `ModLoaderBootstrap` copy/load behaviour.
- `MANIFEST` in `build_split_template.py` changes: authorities, process,
  exported flags, min/target SDK, or the component set (providers/activities),
  such as the `:modstorage` kickstart trampoline activity.
- The split gains or loses a ZIP entry the loader reads at runtime.

Do not increment it for Manager-only work (UI, Workshop, mod cache, save
editor), for release plumbing, or for a rebuild that yields an equivalent
loader. A needless increment tells every patched user to re-patch; a missing
one leaves them on a stale loader.

## Manual template build

```bash
cd android/manager
bash ./gradlew :bootstrap:assembleRelease \
  -PmanagerCertificateSha256=<64-lowercase-hex-characters> \
  -PmodloaderBinary=../../native/build-android-release/libmodloader.so

# android-35 below is pinned by the loader split's own manifest
# (minSdkVersion=21, targetSdkVersion=35); it is unrelated to the Manager's
# compileSdk 37 and to the native build's -DANDROID_PLATFORM=android-35.
python ../bootstrap/build_split_template.py \
  --bootstrap-aar ../bootstrap/build/outputs/aar/bootstrap-release.aar \
  --bootstrap-manifest ../bootstrap/src/main/AndroidManifest.xml \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --aapt2 "$ANDROID_HOME/build-tools/<version>/aapt2.exe" \
  --d8 "$ANDROID_HOME/build-tools/<version>/d8.bat" \
  --output app/build/release-stage/modloader-template-10005.apk \
  --version-code 10005 --version-name 1.0.5 --revision 2

python ../bootstrap/build_split_template.py --verify \
  --output app/build/release-stage/modloader-template-10005.apk \
  --version-code 10005 --version-name 1.0.5 --revision 2
```

## Verification and tests

```bash
# verify-loader-template.py imports shared contract constants from
# scripts/release_pin_contracts.py, hence PYTHONPATH=scripts.
PYTHONPATH=scripts python -X utf8 scripts/verify-loader-template.py \
  --stage android/manager/app/build/release-stage/publish

cd android/manager
# :core:merge:test is listed separately because it does not tolerate the
# configuration cache enabled in gradle.properties.
bash ./gradlew --no-configuration-cache :core:merge:test
python -m py_compile app/src/main/python/android_merge_id_remap.py \
  app/src/main/python/android_merge_worker.py
bash ./gradlew :core:model:test :core:storage:test :core:apk:test \
  :core:workshop:test :core:steam-protocol:test \
  :core:workshop-download:test :app:testDebugUnitTest
```

The Mod merge reuses the upstream merger and reports catalog/version
mismatches and ID conflicts as best-effort warnings. Operational errors still
fail the merge and do not import partial output. APK patch/install checks are
independent and remain fail-closed.

Build native host tests with `-DMODLOADER_BUILD_HOST_TESTS=ON
-DMODLOADER_BACKEND_MODE=1`; run `ctest --test-dir native/build-host
--output-on-failure` on a compatible host.

## Constraints

- ARM64 only; every loader native `PT_LOAD` uses `p_align=0x4000` and has no
  `TEXTREL`.
- The base APK is never modified; only signing is allowed.
- The unsigned loader split is signed later with the same device identity as
  the base and original splits.
- Release artifacts, Chaquopy target archives, wheel caches and wheel files
  are local outputs and must not be committed.
- Do not commit game-original JSON, device Mod caches, release keys or password
  files.

## Official references

- [Chaquopy Android documentation](https://chaquo.com/chaquopy/doc/current/android.html)
- [Chaquopy wheel build instructions](https://github.com/chaquo/chaquopy/blob/master/server/pypi/README-old.md)
- [Chaquopy Python target artifacts](https://repo.maven.apache.org/maven2/com/chaquo/python/target/)
