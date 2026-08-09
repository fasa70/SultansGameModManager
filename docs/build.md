# Build Guide

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | Required for Kotlin compilation |
| Android SDK | 35 | API level for compilation |
| Android NDK | 27.0+ | ARM64 cross-compilation toolchain |
| CMake | 3.22+ | Native build system |
| Ninja | (any) | Build executor (bundled with CMake) |
| Gradle | (via wrapper) | `./gradlew` in `android/manager/` |

## Quick Start

### 1. Clone

```bash
git clone --recurse-submodules https://github.com/example/SultansGameModManager.git
cd SultansGameModManager
```

### 2. Build Manager APK (debug)

```bash
cd android/manager
./gradlew :app:assembleDebug
```

Output: `android/manager/app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Native Library

The bundled release loader uses the official backend with the complete UI, URI/texture, and TMP compatibility combination. Pass every release-critical option explicitly so an old CMake cache cannot silently produce a diagnostic variant:

```bash
# Configure
cmake -B native/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-35 \
  -DCMAKE_BUILD_TYPE=Release \
  -DMODLOADER_BACKEND_MODE=1 \
  -DMODLOADER_OFFICIAL_URI_HOOKS=ON \
  -DMODLOADER_OFFICIAL_URI_TEXTURE_HOOK=ON \
  -DMODLOADER_OFFICIAL_TMP_GLYPH_HOOKS=ON

# Build
cmake --build native/build-android
```

Output: `native/build-android/libmodloader.so`

### 4. Verify the Native Artifact

The loader requires 16KB page alignment for compatibility with the game's packing system. It must also remain an AArch64 ELF without text relocations:

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(host)/bin/llvm-readelf \
  -hW -lW -dW native/build-android/libmodloader.so
sha256sum native/build-android/libmodloader.so
```

Require ELF64/AArch64, `Align 0x4000` on every `PT_LOAD`, and no `TEXTREL` entry.

### 5. Build Host Tests

```bash
cmake -B native/build-host -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DMODLOADER_BUILD_HOST_TESTS=ON \
  -DMODLOADER_BACKEND_MODE=1
cmake --build native/build-host
ctest --test-dir native/build-host --output-on-failure
```

Both `modloader_core_tests` and `modloader_il2cpp_runtime_tests` must pass.

## Building the Loader Split Template

The loader split APK is a pre-built artifact bundled with the Manager. It must be rebuilt whenever the native library, bootstrap Java code, or bootstrap manifest/security contract changes. The frozen template must remain unsigned; the Manager signs it later with the same device identity used for the base and original splits.

After rebuilding, regenerate the template with `android/bootstrap/build_split_template.py`, replace `android/manager/app/src/main/assets/release/modloader-template-10005.apk`, and update both digest classes atomically:

- **template SHA-256**: hash of the complete unsigned APK;
- **native SHA-256**: hash of `assets/modloader/arm64-v8a/modloader.bin` inside it.

The bootstrap input `managerCertificateSha256` is the public DER certificate digest recorded in `release/manager-release-manifest.md`. Never commit or print the release JKS or its password.

```bash
# 1. Build native library when it changed (see above).
# 2. Build the bootstrap AAR with the release Manager certificate pin.
cd android/manager
./gradlew :bootstrap:assembleRelease \
  -PmanagerCertificateSha256=<64-hex-characters> \
  -PmodloaderBinary=../../native/build-android/libmodloader.so

# 3. Generate the unsigned frozen split template from that AAR.
python ../bootstrap/build_split_template.py \
  --bootstrap-aar ../bootstrap/build/outputs/aar/bootstrap-release.aar \
  --android-jar $ANDROID_HOME/platforms/android-35/android.jar \
  --aapt2 $ANDROID_HOME/build-tools/<version>/aapt2 \
  --d8 $ANDROID_HOME/build-tools/<version>/d8 \
  --output app/src/main/assets/release/modloader-template-10005.apk \
  --version-code 10005 \
  --version-name 1.0.5

# 4. Verify the frozen artifact, then calculate both digests.
# The native entry must be ZIP_STORED and the APK must be unsigned.
apksigner verify app/src/main/assets/release/modloader-template-10005.apk || true
unzip -lv app/src/main/assets/release/modloader-template-10005.apk
sha256sum app/src/main/assets/release/modloader-template-10005.apk
unzip -p app/src/main/assets/release/modloader-template-10005.apk \
  assets/modloader/arm64-v8a/modloader.bin | sha256sum

# 5. Update every pin listed below, then build Manager.
```

After generating the template, update every matching digest in the same change:

- `GameProfileRegistry.kt`
  - `OFFICIAL_10005.nativeLoaderSha256`
  - `OFFICIAL_10005.loaderTemplateSha256`
- `AndroidLoaderSplitArtifactFactory.kt`
  - `TEMPLATE_SHA256`
- `DeviceSigningKeyStoreTest.kt`
  - factory expected native SHA
  - `LoaderSplitRequest.loaderTemplateSha256`

The package (`com.gametree.sultan.pd`), split name (`modloader`), version code (`10005`), version name (`1.0.5`), and provider protocol remain frozen unless the target game profile or protocol changes.

## Running Tests

```bash
# JVM unit tests
cd android/manager
./gradlew :core:model:test :core:apk:test :core:storage:test :core:workshop:test

# Android instrumentation tests (requires emulator/device)
./gradlew :app:connectedDebugAndroidTest

# Native host tests
cmake --build native/build-host && ./native/build-host/modloader_core_tests
```

## Key Build Constraints

- **ARM64 only** — the native library is `arm64-v8a` only. x86/x86_64 emulators require Houdini translation.
- **16KB page alignment** — `PT_LOAD p_align=0x4000` is mandatory for compatibility with the game's Ano shell.
- **No base APK modification** — the build system never modifies or repackages the game's base APK.
- **Miuix theme** — the UI uses the Miuix Compose Multiplatform theme library (`top.yukonga.miuix.kmp:miuix-ui:0.9.3`).
