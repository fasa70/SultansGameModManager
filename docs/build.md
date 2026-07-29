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

```bash
# Configure
cmake -B native/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release

# Build
cmake --build native/build-android
```

Output: `native/build-android/libmodloader.so`

### 4. Verify ELF Alignment

The loader requires 16KB page alignment for compatibility with the game's packing system:

```bash
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(host)/bin/llvm-readelf -lW native/build-android/libmodloader.so
```

All `PT_LOAD` segments must show `Align 0x4000`.

### 5. Build Host Tests

```bash
cmake -B native/build-host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-host
./native/build-host/modloader_core_tests
```

## Building the Loader Split Template

The loader split APK is a pre-built artifact bundled with the Manager. It must be rebuilt only when the native library changes.

```bash
# 1. Build native library (see above)
# 2. Build template
cd android/manager
./gradlew :app:assembleRelease \
  -PmanagerCertificateSha256=<64-hex-characters> \
  -PmodloaderBinary=../../native/build-android/libmodloader.so
```

After building, update the SHA-256 pin in:
- `GameProfileRegistry.kt` — `OFFICIAL_10005.nativeLoaderSha256` and `loaderTemplateSha256`
- `AndroidLoaderSplitArtifactFactory.kt` — `TEMPLATE_SHA256` and `expectedNativeSha256` (caller)

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
