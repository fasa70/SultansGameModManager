# Build Guide

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Required for Kotlin compilation |
| Android SDK | 35 | API level for loader/template and compilation |
| Android NDK | 27.0+ | ARM64 cross-compilation toolchain |
| CMake | 3.22+ | Native build system |
| Ninja | (any) | Build executor |
| Gradle | (via wrapper) | `./gradlew` in `android/manager/` |

## Quick Start

Run the tracked release pipeline from the repository root after setting `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_NDK_HOME` to paths on your own machine and providing the ignored release keystore/password files:

```bash
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<ndk-version>"
bash scripts/build-release.sh
```

The pipeline always configures a clean release native build with the official backend and all UI/URI/Texture/TMP gates enabled, builds the protocol v2 Bootstrap AAR, generates a validated candidate template, atomically publishes the frozen template, updates its public identity pins, and finally assembles the signed Manager APK. Passing `-PmodloaderBinary` to `:app:assembleRelease` alone does not rebuild the loader template.

The native and template identities are recorded in `release/loader-template-10005.json`. Do not hand-edit only one digest pin: the frozen APK, metadata, production profile, factory, and Android test fixture must change together.

## Native release requirements

The release native configure must explicitly include:

```text
ANDROID_ABI=arm64-v8a
ANDROID_PLATFORM=android-35
MODLOADER_BACKEND_MODE=1
MODLOADER_OFFICIAL_URI_HOOKS=ON
MODLOADER_OFFICIAL_URI_TEXTURE_HOOK=ON
MODLOADER_OFFICIAL_TMP_GLYPH_HOOKS=ON
CMAKE_BUILD_TYPE=Release
```

The resulting `libmodloader.so` must be ELF64/AArch64, have `Align 0x4000` on every `PT_LOAD`, and contain no `TEXTREL`. Native source or CMake changes require rebuilding the Bootstrap AAR and frozen template before a Manager release.

## Building the loader split manually

Use this only when debugging a stage of the tracked pipeline:

```bash
cd android/manager
./gradlew :bootstrap:assembleRelease \
  -PmanagerCertificateSha256=<64-lowercase-hex-characters> \
  -PmodloaderBinary=../../native/build-android-release/libmodloader.so

python ../bootstrap/build_split_template.py \
  --bootstrap-aar ../bootstrap/build/outputs/aar/bootstrap-release.aar \
  --bootstrap-manifest ../bootstrap/src/main/AndroidManifest.xml \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --aapt2 "$ANDROID_HOME/build-tools/<version>/aapt2" \
  --d8 "$ANDROID_HOME/build-tools/<version>/d8" \
  --output app/build/release-stage/modloader-template-10005.apk \
  --version-code 10005 --version-name 1.0.5

python ../bootstrap/build_split_template.py --verify \
  --output app/build/release-stage/modloader-template-10005.apk \
  --expected-native-sha256=<native-sha256>
```

The generator validates the Bootstrap manifest contract, required APK entries, unsigned state, and `ZIP_STORED` native entry before atomically replacing its output path. Generate into a build-stage path first; the release pipeline is responsible for publishing the candidate and updating pins.

## Verification commands

```bash
unzip -t android/manager/app/src/main/assets/release/modloader-template-10005.apk
unzip -lv android/manager/app/src/main/assets/release/modloader-template-10005.apk
sha256sum android/manager/app/src/main/assets/release/modloader-template-10005.apk
unzip -p android/manager/app/src/main/assets/release/modloader-template-10005.apk \
  assets/modloader/arm64-v8a/modloader.bin | sha256sum
```

The template must remain unsigned and its native entry must be stored rather than deflated. Manager patching independently rechecks the full template and embedded native digest before signing.

## Running tests

```bash
cd android/manager
./gradlew :core:model:test :core:storage:test :core:apk:test :core:workshop:test \
  :core:steam-protocol:test :core:workshop-download:test :app:testDebugUnitTest
```

Build native host tests with `-DMODLOADER_BUILD_HOST_TESTS=ON -DMODLOADER_BACKEND_MODE=1`; run `ctest --test-dir native/build-host --output-on-failure` on a compatible host. On the primary Windows host the executables may compile but not run; report that limitation rather than treating it as a pass.

## Key build constraints

- **ARM64 only** — the native library is `arm64-v8a` only.
- **16KB page alignment** — every `PT_LOAD` must use `p_align=0x4000`.
- **No base APK modification** — the build system never modifies or repackages the game's base APK.
- **Unsigned frozen split** — the Manager signs the template later with the device identity used for the game APK set.
- **Protocol v2 closure** — the frozen Bootstrap and Manager ModStorage bridge must use protocol version 2.
