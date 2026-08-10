# Sultan's Game Mod Manager

[English](#english) | [中文](#chinese)

---

<a id="chinese"></a>

## 苏丹的游戏 Mod 管理器

为《苏丹的游戏》Android 版（IL2CPP）提供与 Windows 版**官方 Mod 格式和加载机制兼容**的开源 Android 应用。

> 本项目绝大部分代码来自 AI 辅助编程（vibe coding）

### 概述

Windows 版《苏丹的游戏》提供官方 Mod 支持，而 Android 版没有可直接使用的入口。本项目通过同签名 loader split，为 Android 版实现兼容的 Mod 加载流程。

**不需要 root。** 所有修补、安装和 Mod 管理操作均由用户在自己的设备上确认完成。

当前仅支持1.0.5游戏版本

### 功能与限制

- 从已安装游戏，或本地 APK / APKS 文件导入受支持的游戏安装包并准备修补。
- 导入 ZIP Mod；一个 ZIP 可包含一个或多个 Mod。
- 浏览 Steam 创意工坊公开内容、查看详情并管理下载队列；需要时可登录 Steam 并完成 Steam Guard 验证。
- 将校验通过的 Mod 同步到已修补游戏的 Mod 目录；加载、热开关和排序由游戏内官方 Mod 面板负责。
- 当前只会继续处理通过应用内兼容性检测的游戏版本；不匹配的安装包会被拒绝，而不会尝试修补。
- 本项目不提供或分发游戏 APK、游戏资源或 Mod 内容，也不绕过 DRM、付费、账号、地区或平台限制。

> **修补前请备份存档。** 修补后的游戏使用设备本地生成的新签名，通常需要先卸载原游戏再安装 Mod 支持版；卸载可能清除游戏数据。

### 使用流程

#### 1. 准备游戏

在“开始”页面选择已安装的游戏，或从本地选择 APK / APKS。应用会先检查输入是否属于受支持的游戏版本，再准备修补产物。

修补会对 base APK、原始 split APK 和 loader split 使用同一设备本地签名重新签名，并通过一次系统安装会话安装。根据系统状态，应用会引导你：

1. 确认已备份存档；
2. 卸载原游戏；
3. 授予本应用安装未知来源应用的系统权限；
4. 在系统安装器中确认安装。

准备完成但尚未安装的事务可以在下次打开应用时继续处理；也可以导出准备好的 APKS，或清理中断修补留下的临时文件。设备签名密钥不可导出：如果它在迁移后丢失，需要卸载此前迁移的游戏并重新修补。

#### 2. 获取 Mod

你可以从本地选择 ZIP 导入 Mod，也可以在“创意工坊”中浏览公开内容、搜索和筛选条目、查看详情并加入下载队列。下载任务支持暂停、继续、重试和删除；下载完成后，按应用提示检查并添加到本地 Mod 列表。

部分创意工坊内容的下载需要 Steam 登录。应用会在界面中处理 Steam Guard 验证；是否可下载仍取决于 Steam 和内容本身的访问条件。

#### 3. 管理并同步 Mod

新导入的 Mod 默认会同步到游戏的 `Mod` 目录。Manager 中的“同步给游戏”只控制文件是否存在于该目录；游戏内官方 Mod 面板负责刷新列表、热加载、启用或停用以及排序。

删除 Manager 中的 Mod 会同时安排删除游戏目录中的对应 Manager 管理项；清理缓存也遵循此规则。直接放入游戏 `Mod` 目录的 Mod 会在 Manager 中显示为“游戏中的其他 Mod”，但不会被 Manager 修改或删除。

### Mod ZIP 基本要求

- 从文件选择器导入 `.zip` 文件。
- Mod 根目录需要包含有效的 `info.json`；一个 ZIP 可以包含多个顶层 Mod 目录。
- 导入前会检查目录结构和路径安全性；不安全、重复或不符合 Mod 格式的内容会被拒绝。
- Mod 格式、配置目录和合并规则详见 [架构文档](docs/architecture.md#mod-format)。

### 工作原理

1. **Extract** — 从已安装游戏或用户选择的本地安装包读取 APK。
2. **Sign** — 使用本机 Android KeyStore 生成的 RSA-4096 密钥，对 base APK、原始 splits 和 loader split 重新签名。
3. **Install** — 通过 Android `PackageInstaller` 在单个多 APK 会话中安装。
4. **Load** — 同签名 loader split 中的原生 `libmodloader.so` 在游戏启动时自动加载，并启用 Mod 支持。

### 开发者：从源码构建

#### 项目结构

```
├── android/                    # Android 应用
│   ├── bootstrap/              # Loader split（ContentProvider + libmodloader.so 载体）
│   ├── manager/                # Manager 应用
│   │   ├── app/                # 主应用（Compose UI + 平台实现）
│   │   └── core/               # 平台无关核心库
│   │       ├── apk/            # 只读 APK 检查
│   │       ├── game-bridge/    # 游戏通信桥
│   │       ├── loader-split/   # Loader split 工厂接口
│   │       ├── model/          # 数据模型
│   │       ├── steam-protocol/ # Steam CM 协议客户端
│   │       ├── storage/        # 本地 Mod 存储抽象
│   │       ├── workshop/       # 创意工坊浏览与元数据
│   │       └── workshop-download/ # 创意工坊下载与完整性校验
├── native/                     # C++ 原生 loader
│   ├── include/modloader/      # 头文件
│   ├── src/                    # 实现
│   └── tests/                  # 单元测试
├── docs/                       # 构建与架构文档
└── release/                    # 发布身份信息（密钥文件不入库）
```

#### 构建要求

- **JDK 21** — Kotlin/JVM 编译
- **Android SDK Platform 37** — 当前 `compileSdk`
- **Android NDK 27.0+**
- **CMake 3.22+** 和 Ninja — 原生库构建
- **Gradle** — 通过 `android/manager/gradlew`

开始前可在仓库根目录运行：

```bash
bash scripts/check-env.sh
```

#### 构建 Manager debug APK

```bash
cd android/manager
./gradlew :app:assembleDebug
```

输出：`android/manager/app/build/outputs/apk/debug/app-debug.apk`。

#### 构建原生库

发布 loader 必须显式启用 official backend 的全部兼容门禁，避免复用旧 CMake cache 构建出诊断变体：

```bash
cmake -S native -B native/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-35 \
  -DCMAKE_BUILD_TYPE=Release \
  -DMODLOADER_BACKEND_MODE=1 \
  -DMODLOADER_OFFICIAL_URI_HOOKS=ON \
  -DMODLOADER_OFFICIAL_URI_TEXTURE_HOOK=ON \
  -DMODLOADER_OFFICIAL_TMP_GLYPH_HOOKS=ON
cmake --build native/build-android
```

输出：`native/build-android/libmodloader.so`。发布构建还必须满足 ELF64/AArch64、所有 `PT_LOAD` 为 `0x4000` 对齐且没有 `TEXTREL`。完整的模板重建、hash pin、Manager release 和测试流程见 [构建指南](docs/build.md)。

### 技术栈

- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) 主题
- **原生**: C++17（ARM64）、JNI、IL2CPP 运行时交互
- **构建**: Gradle（Kotlin DSL）、CMake、Ninja
- **签名**: Android KeyStore、APK Signature Scheme v1+v2
- **创意工坊**: Steam Community 浏览、Steam CM 协议、Protobuf

### 许可

本项目以 GNU General Public License v3.0 发布。详见 [LICENSE](LICENSE)。

本应用是独立的社区工具，不隶属、且未被《苏丹的游戏》权利人、发行商或 steam 认可。游戏名称、商标及内容权利归其各自权利人所有。

### 致谢

- [Dobby](https://github.com/jmpews/Dobby) — ARM64 动态二进制插桩框架
- [apksig](https://android.googlesource.com/platform/tools/apksig) — Android APK 签名库
- Workshop-Native — [创意工坊适配来源](https://github.com/cjtestuse/Workshop-Native)

---

<a id="english"></a>

## Sultan's Game Mod Manager

An **open-source Android application** that makes the Android version of *Sultan's Game* (IL2CPP) compatible with the official Windows Mod format and loading mechanism.

### Overview

The Windows version of *Sultan's Game* has official Mod support, while the Android version has no directly usable entry point. This project provides a compatible Mod-loading path on Android through a co-signed loader split.

**No root is required.** Patching, installation, and Mod-management actions are confirmed by the user on their own device.

### Features and limitations

- Import a supported game installation from the installed game, or from local APK / APKS files, then prepare it for patching.
- Import ZIP Mods; one ZIP may contain one or more Mods.
- Browse public Steam Workshop content, view details, and manage download tasks. Steam sign-in and Steam Guard are available when required.
- Synchronize validated Mods to the patched game's Mod directory; the in-game official Mod panel controls loading, hot toggles, and order.
- The app proceeds only with game versions that pass its in-app compatibility check. Mismatched packages are rejected rather than patched.
- This project does not provide or distribute game APKs, game assets, or Mod content, and does not bypass DRM, payment, account, regional, or platform restrictions.

> **Back up your saves before patching.** A patched game uses a new device-local signature, so the original game normally has to be uninstalled before the Mod-enabled version can be installed. Uninstalling may erase game data.

### Workflow

#### 1. Prepare the game

On the **Start** page, select the installed game or choose a local APK / APKS file. The app first checks whether the input is a supported game version, then prepares the patch artifacts.

Patching re-signs the base APK, original split APKs, and loader split with one device-local signing identity, then installs them through one system installation session. Depending on system state, the app guides you to:

1. confirm that your saves are backed up;
2. uninstall the original game;
3. allow this app to install unknown apps in system settings;
4. confirm installation in the system installer.

A prepared but unfinished installation can be resumed the next time the app opens. You can also export the prepared APKS or clean up temporary files left by an interrupted patch. The device signing key cannot be exported: if it is lost after migration, uninstall the previously migrated game and patch it again.

#### 2. Get Mods

Import Mods from local ZIP files, or use the **Workshop** page to browse public content, search and filter items, view details, and add them to the download queue. Tasks can be paused, resumed, retried, or removed. After a download completes, follow the app prompt to inspect and add it to the local Mod list.

Some Workshop downloads require Steam sign-in. Steam Guard is handled in the app; whether an item can be downloaded still depends on Steam and the content's own access conditions.

#### 3. Manage and synchronize Mods

Newly imported Mods are synchronized to the game's `Mod` directory by default. The Manager's **Sync to game** setting controls only whether files are present in that directory; the in-game official Mod panel refreshes the list and controls hot loading, enabling, disabling, and ordering.

Removing a Mod from the Manager also schedules removal of the corresponding Manager-owned game directory. Clearing the cache follows the same rule. Mods placed directly in the game's `Mod` directory appear as **Other Mods in game** in the Manager, but are not modified or deleted.

### Mod ZIP requirements

- Import `.zip` files through the file picker.
- Each Mod root must contain a valid `info.json`; one ZIP may contain multiple top-level Mod directories.
- The import process checks directory structure and path safety. Unsafe, duplicate, or invalid Mod contents are rejected.
- See the [architecture document](docs/architecture.md#mod-format) for Mod formats, configuration directories, and merge rules.

### How it works

1. **Extract** — Read APKs from the installed game or a user-selected local installation package.
2. **Sign** — Re-sign the base APK, original splits, and loader split with an RSA-4096 key generated in the device's Android KeyStore.
3. **Install** — Install through one multi-APK Android `PackageInstaller` session.
4. **Load** — The native `libmodloader.so` in the co-signed loader split loads automatically when the game starts and enables Mod support.

### Developers: build from source

#### Project layout

```
├── android/                    # Android applications
│   ├── bootstrap/              # Loader split (ContentProvider + libmodloader.so carrier)
│   ├── manager/                # Manager application
│   │   ├── app/                # Main app (Compose UI + platform implementations)
│   │   └── core/               # Platform-independent core libraries
│   │       ├── apk/            # Read-only APK inspection
│   │       ├── game-bridge/    # Game communication bridge
│   │       ├── loader-split/   # Loader split factory interfaces
│   │       ├── model/          # Domain models
│   │       ├── steam-protocol/ # Steam CM protocol client
│   │       ├── storage/        # Local Mod storage abstractions
│   │       ├── workshop/       # Workshop browsing and metadata
│   │       └── workshop-download/ # Workshop downloads and integrity checks
├── native/                     # C++ native loader
│   ├── include/modloader/      # Headers
│   ├── src/                    # Implementation
│   └── tests/                  # Unit tests
├── docs/                       # Build and architecture documents
└── release/                    # Release identity information (key files are untracked)
```

#### Prerequisites

- **JDK 21** — Kotlin/JVM compilation
- **Android SDK Platform 37** — current `compileSdk`
- **Android NDK 27.0+**
- **CMake 3.22+** and Ninja — native builds
- **Gradle** — through `android/manager/gradlew`

Before building, run this from the repository root:

```bash
bash scripts/check-env.sh
```

#### Build the Manager debug APK

```bash
cd android/manager
./gradlew :app:assembleDebug
```

Output: `android/manager/app/build/outputs/apk/debug/app-debug.apk`.

#### Build the native library

Release loader builds must explicitly enable the official backend and every compatibility gate so an old CMake cache cannot silently produce a diagnostic variant:

```bash
cmake -S native -B native/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-35 \
  -DCMAKE_BUILD_TYPE=Release \
  -DMODLOADER_BACKEND_MODE=1 \
  -DMODLOADER_OFFICIAL_URI_HOOKS=ON \
  -DMODLOADER_OFFICIAL_URI_TEXTURE_HOOK=ON \
  -DMODLOADER_OFFICIAL_TMP_GLYPH_HOOKS=ON
cmake --build native/build-android
```

Output: `native/build-android/libmodloader.so`. Release artifacts must be ELF64/AArch64, have `0x4000` alignment on every `PT_LOAD`, and contain no `TEXTREL`. See the [build guide](docs/build.md) for template regeneration, hash pins, Manager release builds, and tests.

#### Release build

The tracked release entry rebuilds the native loader, protocol v2 Bootstrap AAR, frozen split template, digest pins, and signed Manager APK in that order. It requires the local, untracked release keystore/password files and environment variables for the SDK, NDK, and JDK:

```bash
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<ndk-version>"
bash scripts/build-release.sh
```

Do not run only `:app:assembleRelease -PmodloaderBinary=...`: that task consumes the already-frozen template and does not rebuild it. See the [build guide](docs/build.md) for the staged native → Bootstrap → template pipeline, digest closure, and tests.

### Technology stack

- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) theme
- **Native**: C++17 (ARM64), JNI, IL2CPP runtime integration
- **Build**: Gradle (Kotlin DSL), CMake, Ninja
- **Signing**: Android KeyStore, APK Signature Scheme v1+v2
- **Workshop**: Steam Community browsing, Steam CM protocol, Protobuf

### License

This project is released under the GNU General Public License v3.0. See [LICENSE](LICENSE).

This application is an independent community tool and is not affiliated with or endorsed by the rights holders of *Sultan's Game*, its publishers, or Valve. Game names, trademarks, and content rights belong to their respective owners.

### Acknowledgments

- [Dobby](https://github.com/jmpews/Dobby) — ARM64 dynamic binary instrumentation framework
- [apksig](https://android.googlesource.com/platform/tools/apksig) — Android APK signing library
- [Workshop-Native](https://github.com/cjtestuse/Workshop-Native) — Workshop adaptation source
