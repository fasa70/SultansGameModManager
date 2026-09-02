# Sultan's Game Mod Manager

[English](#english) | [中文](#chinese)

---

<a id="chinese"></a>

## 苏丹的游戏 Mod 管理器

为《苏丹的游戏》Android 版（IL2CPP）提供与 Windows 版**官方 Mod 格式和加载机制兼容**的开源 Android 应用。

> 本项目绝大部分代码来自 AI 辅助编程（vibe coding）


### 下载

- 安装包在 [Releases](https://github.com/fasa70/SultansGameModManager/releases) 页面发布。
- **支持的游戏版本：仅 1.0.5**（版本号 10005）。

### 概述

Windows 版《苏丹的游戏》提供官方 Mod 支持，而 Android 版没有可直接使用的入口。本项目通过同签名 loader split，为 Android 版实现兼容的 Mod 加载流程。

**不需要 root。** 所有修补、安装和 Mod 管理操作均由用户在自己的设备上确认完成。

### 功能与限制

- 从已安装游戏，或本地 APK / APKS 文件导入受支持的游戏安装包并准备修补；开始页会显示当前状态（游戏未修补 / 修补版本需升级 / 游戏已就绪）。
- 导入 ZIP Mod；一个 ZIP 可包含一个或多个 Mod；支持加密 ZIP（导入时提示输入密码），也可以从文件管理器等其它应用直接分享或打开 ZIP 来导入。
- 管理本地 Mod：重命名、删除、同步到游戏，以及把选中的 Mod 打包导出或分享到其它应用（可选设置 ZIP 密码）。
- 按需在设置中开启创意工坊：浏览 Steam 公开内容、搜索/筛选条目、查看详情并管理下载队列；需要时可登录 Steam 并完成 Steam Guard 验证。
- 将校验通过的 Mod 同步到已修补游戏的 Mod 目录，同步过程显示进度；加载、热开关和排序由游戏内官方 Mod 面板负责。
- 管理器提供基于 MIT 许可上游 Mod 合并器的入口。由于 Android 版本限制无法提取游戏 Info，合并结果可能与上游项目有出入。
- 内置存档编辑器：读写已修补游戏的存档，覆盖保存与恢复前都会自动备份。
- 维护功能：清理本地 Mod、重置管理器状态、清理中断修补留下的临时文件、导出准备好的 APKS。
- 当前只会继续处理通过应用内兼容性检测的游戏版本；不匹配的安装包会被拒绝，而不会尝试修补。
- 除检查更新与创意工坊相关功能外，应用不联网；Mod、存档、下载数据和设备签名密钥都保存在本机。
- 本项目不提供或分发游戏 APK、游戏资源或 Mod 内容，也不绕过 DRM、付费、账号、地区或平台限制。

> **修补前请备份存档。** 首次修补会把游戏换成设备本地生成的新签名，因此通常需要先卸载原游戏再安装 Mod 支持版，卸载可能清除游戏数据。之后升级 loader 时应用会走覆盖更新，通常可以保留存档。

### 使用流程

#### 1. 准备游戏

在“开始”页面选择已安装的游戏，或从本地选择 APK / APKS。应用会先检查输入是否属于受支持的游戏版本，再准备修补产物。开始页会显示当前状态：

- **游戏未修补** — 还没打过补丁，或安装的是原版。
- **修补版本需升级** — 已修补，但游戏内的 loader 版本比本管理器自带的旧，需要重新修补一次。
- **游戏已就绪** — 可以同步 Mod 和编辑存档。

修补会对 base APK、原始 split APK 和 loader split 使用同一设备本地签名重新签名，并通过一次系统安装会话安装。根据系统状态，应用会引导你：

1. 确认已备份存档；
2. 卸载原游戏（仅首次修补需要；升级 loader 时走覆盖更新，通常可以保留存档）；
3. 授予本应用安装未知来源应用的系统权限；
4. 在系统安装器中确认安装。

小米（含红米、POCO）与 OPPO / 一加 机型会在修补前额外给出系统限制提示，按提示处理即可，详见[常见问题](#常见问题)。

准备完成但尚未安装的事务可以在下次打开应用时继续处理；也可以导出准备好的 APKS（导出时显示校验与写入进度），或清理中断修补留下的临时文件。设备签名密钥不可导出：如果它在迁移后丢失，需要卸载此前迁移的游戏并重新修补。

#### 2. 获取 Mod

**从本地 ZIP 导入。** 在“管理Mod”页面选择 ZIP 文件；也可以在文件管理器等其它应用里对 ZIP 选择“分享”或“打开方式”，直接送进管理器导入。加密 ZIP 会提示输入密码。

**从创意工坊获取。** 创意工坊默认不显示；如需使用，可在设置中开启。在创意工坊中可以浏览公开内容、搜索和筛选条目、查看详情并加入下载队列。下载任务支持暂停、继续、重试和删除；下载完成后，按应用提示检查并添加到本地 Mod 列表。

部分创意工坊内容的下载需要 Steam 登录。应用会在界面中处理 Steam Guard 验证；是否可下载仍取决于 Steam 和内容本身的访问条件。

#### 3. 管理并同步 Mod

新导入的 Mod 默认会同步到游戏的 `Mod` 目录，同步过程会显示进度；也可以随时手动触发同步。Manager 中的“同步给游戏”只控制文件是否存在于该目录；游戏内官方 Mod 面板负责刷新列表、热加载、启用或停用以及排序。

同步与存档编辑都需要已修补游戏的存档服务在线。如果应用提示服务未运行，点“启动游戏并保持在后台”即可；小米机型还可以开启游戏的自启动权限，之后就不必每次手动启动。

Mod 可以重命名（只改管理器中的显示名，不影响游戏目录里的内容）。在“导出/分享 Mod”里可以选择一个或多个 Mod 打包，分享到其它应用或导出到本地，并可选设置 ZIP 密码；ZIP 中每个 Mod 保留独立的顶层目录，因此可以原样重新导入。

删除 Manager 中的 Mod 会同时安排删除游戏目录中的对应 Manager 管理项；清理缓存也遵循此规则。直接放入游戏 `Mod` 目录的 Mod 会在 Manager 中显示为“游戏中的其他 Mod”，但不会被 Manager 修改或删除。

#### 4. 合并 Mod

在管理 Mod 页面进入“合并 Mod”，选择至少两个已导入并缓存的 Mod。列表顶部为低优先级，底部为高优先级；合并时后者覆盖前者。合并顺序只影响生成的 Mod，不改变普通 Mod 列表顺序或游戏内官方 Mod 顺序。

合并完成后，生成的 Mod 会作为普通缓存项重新导入同步系统，可以同步、加载、删除或再次参与合并。Manager 会单独保存其显示名称，并询问是否停止原始 Mod 的同步。

合并功能基于 MIT 许可的上游项目 [`sutan-game`](https://github.com/fentender/sutan-game)。由于无法提取游戏本体 JSON，Android 使用无本体 JSON overlay；合并在 Manager 端完成，不由 native loader 在游戏运行时执行。

#### 5. 编辑存档

存档编辑需要已修补的游戏正在后台运行。如果提示存档服务未运行，点“启动游戏并保持在后台”。

流程：选择游戏用户（对应游戏存档目录下的 `SAVEDATA/<用户号>`，用户 0 默认折叠）→ 选择存档文件（10 个读档槽位、自动存档 `auto_save.json`，以及默认折叠的其它存档文件）→ 在内置编辑器页面里修改 → 保存。

- **保存到游戏存档** 覆盖当前打开的那个文件。
- 在“槽位 / 备份”里可以把编辑器中的内容另存为某个读档槽位，应用会同时更新读档索引，当前正在编辑的文件本身不受影响。
- 每次覆盖保存或恢复备份之前，应用都会先把当前存档另存一份，每个文件最多保留最近 10 份，可以在“槽位 / 备份”里一键恢复或删除。
- 命运商城等账号级全局数据存放在 `global.json` 中，它走独立的保存与备份通道，同样最多保留 10 份。

建议改存档前先在游戏内正常保存并退出，恢复备份后重新进入游戏读档。

编辑界面来自上游的《苏丹的游戏》存档修改器网页版（GPLv3，作者 柳漪春涛）。本项目只负责 Android 集成与存档读写，未修改其编辑逻辑；详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 设置与维护

“设置”页面包含：

- **清理本地 Mod** — 删除管理器中所有已添加的 Mod，并安排从游戏 `Mod` 目录中移除对应内容。
- **重置管理器状态** — 导入或修补出现异常时使用。会保留已缓存的 Mod 与设备签名密钥，但会取消并清空创意工坊下载任务与暂存内容、放弃未完成的安装会话与修补事务、清理合并工作区、**退出 Steam 登录**，并重新弹出免责声明。
- **创意工坊** — 是否在导航栏显示创意工坊（默认关闭）。
- **应用更新** — 是否在启动时联网检查 GitHub 上的新版本（默认开启）。
- **开源许可** — 本项目以 GNU GPLv3 开源。

### Mod ZIP 基本要求

- Mod 目录需要包含有效的 `info.json`；一个 ZIP 可以包含多个顶层 Mod 目录。
- 导入前会检查目录结构和路径安全性；不安全或重复的内容会被拒绝。
- 如果 `info.json` 不在 ZIP 根目录或一级子目录，应用会提示改用深度扫描导入，确认后可以从更深的层级识别 Mod。
- 支持加密 ZIP，导入时会提示输入密码。
- 从本管理器导出的 ZIP 保留每个 Mod 独立的顶层目录，可以直接重新导入。
- Mod 格式、配置目录和合并规则详见 [架构文档](docs/architecture.md#mod-format)。

### 工作原理

1. **Extract** — 从已安装游戏或用户选择的本地安装包读取 APK。
2. **Sign** — 使用本机 Android KeyStore 生成的 RSA-4096 密钥，对 base APK、原始 splits 和 loader split 重新签名。
3. **Install** — 通过 Android `PackageInstaller` 在单个多 APK 会话中安装。
4. **Load** — 同签名 loader split 中的原生 `libmodloader.so` 在游戏启动时自动加载，并启用 Mod 支持。
5. **Sync / Edit** — 管理器通过 loader split 中同签名的 `ModStorageProvider` 同步 Mod、读写存档，全程不需要 root，也不改动 base APK。

### 开发者：从源码构建

#### 项目结构

```
├── android/                    # Android 应用
│   ├── bootstrap/              # Loader split（ContentProvider + libmodloader.so 载体）
│   ├── manager/                # Manager 应用
│   │   ├── app/                # 主应用（Compose UI + 平台实现 + Python 合并脚本）
│   │   ├── merge-native/       # 合并用 JNI JSON 修复库
│   │   ├── tools/              # Chaquopy Android wheel 暂存与构建
│   │   └── core/               # 平台无关核心库
│   │       ├── apk/            # 只读 APK 检查
│   │       ├── game-bridge/    # 游戏通信桥（Mod 同步 + 存档读写）
│   │       ├── loader-split/   # Loader split 工厂接口
│   │       ├── merge/          # Mod 合并编排
│   │       ├── model/          # 数据模型
│   │       ├── steam-protocol/ # Steam CM 协议客户端
│   │       ├── storage/        # 本地 Mod 存储抽象
│   │       ├── workshop/       # 创意工坊浏览与元数据
│   │       └── workshop-download/ # 创意工坊下载与完整性校验
├── native/                     # C++ 原生 loader
│   ├── include/modloader/      # 头文件
│   ├── src/                    # 实现
│   ├── tests/                  # 单元测试
│   └── third_party/dobby/      # Dobby（git 子模块）
├── scripts/                    # 环境检查、发布流水线与校验脚本
├── docs/                       # 构建与架构文档
```

#### 构建要求

- **JDK 21** — Kotlin/JVM 编译
- **Android SDK Platform 37 与 35** — 37 是当前 `compileSdk`；35 提供生成 loader split 模板所需的 `android.jar`，两者都要安装
- **Android NDK 27.0+**
- **CMake 3.22+** 和 Ninja — 原生库构建
- **Gradle** — 通过 `android/manager/gradlew`
- **Python 3.11** — 仅在构建 Mod 合并所需的 Chaquopy wheel 时用到，详见 [构建指南](docs/build.md)

克隆后先初始化子模块，否则原生库构建会失败（Dobby 以 git 子模块形式引入）：

```bash
git submodule update --init --recursive
```

之后可在仓库根目录运行环境检查（它只检查 Platform 35，Platform 37 需自行确认已安装）：

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

输出：`native/build-android/libmodloader.so`。发布构建还必须满足 ELF64/AArch64、所有 `PT_LOAD` 为 `0x4000` 对齐且没有 `TEXTREL`。完整的模板重建、Manager release 和测试流程见 [构建指南](docs/build.md)。

#### 发布构建

发布流程依次重新生成原生 loader、Bootstrap AAR、未签名的 split 模板、结构元数据、Chaquopy wheel 和签名后的 Manager APK。它需要本地未纳入版本控制的发布密钥库与密码文件，以及 SDK、NDK、JDK 的环境变量：

```bash
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<ndk-version>"
bash scripts/build-release.sh
```

模板与元数据生成在 `android/manager/app/build/` 下，不纳入版本控制。不要只运行 `:app:assembleRelease`：发布流程必须先生成并校验 loader 模板。分阶段的 native → Bootstrap → 模板流水线与测试见 [构建指南](docs/build.md)。

### 技术栈

- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) 主题
- **原生**: C++17（ARM64）、JNI、IL2CPP 运行时交互
- **Mod 合并**: Chaquopy + CPython 3.11（`arm64-v8a`）、JNI JSON 修复库
- **存档编辑**: WebView（AndroidX WebKit）+ 注入的 JavaScript 适配层
- **构建**: Gradle（Kotlin DSL）、CMake、Ninja
- **签名**: Android KeyStore、APK Signature Scheme v1+v2
- **创意工坊**: Steam Community 浏览、Steam CM 协议、Protobuf、OkHttp、Coil
- **依赖版本**: 见 [`android/manager/gradle/libs.versions.toml`](android/manager/gradle/libs.versions.toml)

### 许可

本项目以 GNU General Public License v3.0 发布。详见 [LICENSE](LICENSE)。

本应用是独立的社区工具，不隶属、且未被《苏丹的游戏》权利人、发行商或 steam 认可。游戏名称、商标及内容权利归其各自权利人所有。

### 致谢

- [苏游修改器 / suyou-save-editor](https://github.com/khb10533/suyou-save-editor) — 存档编辑器网页版（作者 柳漪春涛）
- [sutan-game](https://github.com/fentender/sutan-game) — Mod 合并器
- [Workshop-Native](https://github.com/cjtestuse/Workshop-Native) — 创意工坊浏览与登录交互适配来源
- [WorkshopAndroidDownloader](https://github.com/Apricityx/WorkshopAndroidDownloader) — Steam CM 协议与创意工坊下载引擎来源
- [Dobby](https://github.com/jmpews/Dobby) — ARM64 动态二进制插桩框架
- [apksig](https://android.googlesource.com/platform/tools/apksig) — Android APK 签名库
- [Chaquopy](https://chaquo.com/chaquopy/) — 在 Android 上运行 Mod 合并所需的 CPython 3.11
- [yyjson](https://github.com/ibireme/yyjson) 与 [rapidfuzz-cpp](https://github.com/rapidfuzz/rapidfuzz-cpp) — 合并使用的 C++ JSON 与模糊匹配库
- [Miuix](https://github.com/compose-miuix-ui/miuix) — Compose UI 主题

完整的许可与来源记录见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

<a id="english"></a>

## Sultan's Game Mod Manager

An **open-source Android application** that makes the Android version of *Sultan's Game* (IL2CPP) compatible with the official Windows Mod format and loading mechanism.

### Download

- Prebuilt APKs are published on the [Releases](https://github.com/fasa70/SultansGameModManager/releases) page.
- **Supported game version: 1.0.5 only** (version code 10005).

### Overview

The Windows version of *Sultan's Game* has official Mod support, while the Android version has no directly usable entry point. This project provides a compatible Mod-loading path on Android through a co-signed loader split.

**No root is required.** Patching, installation, and Mod-management actions are confirmed by the user on their own device.

> Most of this project's code comes from AI-assisted programming (vibe coding).

### Features and limitations

- Import a supported game installation from the installed game, or from local APK / APKS files, then prepare it for patching; the Start page shows the current state (game not patched / patch needs an upgrade / game ready).
- Import ZIP Mods; one ZIP may contain one or more Mods; encrypted ZIPs are supported (the app asks for the password), and ZIPs can be shared or opened into the Manager from other apps.
- Manage local Mods: rename, delete, synchronize to the game, and export or share selected Mods as a ZIP (optionally password-protected).
- When enabled in Settings, browse, search, filter, and view public Steam Workshop content, manage the download queue, and sign in to Steam with Steam Guard verification when required.
- Synchronize validated Mods to the patched game's Mod directory with progress display; the in-game official Mod panel controls loading, hot toggles, and order.
- The Manager provides a simple entry point for the MIT-licensed upstream Mod merger. Android cannot extract the game's original JSON, so the result may differ from the upstream project.
- Built-in save editor: read and write the patched game's saves, with automatic backups before every overwrite or restore.
- Maintenance: clear local Mods, reset Manager state, clean up files left by an interrupted patch, export prepared APKS.
- The app proceeds only with game versions that pass its in-app compatibility check. Mismatched packages are rejected rather than patched.
- Apart from the update check and Workshop features, the app stays offline; Mods, saves, downloaded data, and the device signing key never leave the device.
- This project does not provide or distribute game APKs, game assets, or Mod content, and does not bypass DRM, payment, account, regional, or platform restrictions.

> **Back up your saves before patching.** The first patch switches the game to a new device-local signature, so the original game normally has to be uninstalled first; uninstalling may erase game data. Later loader upgrades are applied as in-place updates that usually preserve saves.

### Workflow

#### 1. Prepare the game

On the **Start** page, select the installed game or choose a local APK / APKS file. The app first checks whether the input is a supported game version, then prepares the patch artifacts. The Start page shows the current state:

- **Game not patched** — not patched yet, or the stock game is installed.
- **Patch needs an upgrade** — patched, but the loader inside the game is older than the one this Manager ships; patch once more.
- **Game ready** — Mods can be synchronized and saves edited.

Patching re-signs the base APK, original split APKs, and loader split with one device-local signing identity, then installs them through one system installation session. Depending on system state, the app guides you to:

1. confirm that your saves are backed up;
2. uninstall the original game (first patch only; loader upgrades are in-place updates that usually preserve saves);
3. allow this app to install unknown apps in system settings;
4. confirm installation in the system installer.

Xiaomi (including Redmi and POCO) and OPPO / OnePlus devices get an extra warning about vendor-specific system limits before patching; see the [FAQ](#faq).

A prepared but unfinished installation can be resumed the next time the app opens. You can also export the prepared APKS (with verify/write progress display) or clean up temporary files left by an interrupted patch. The device signing key cannot be exported: if it is lost after migration, uninstall the previously migrated game and patch it again.

#### 2. Get Mods

**From a local ZIP.** Pick a ZIP on the Mod-management page, or share/open a ZIP from a file manager or other app to send it straight into the Manager. Encrypted ZIPs prompt for the password.

**From the Workshop.** The Workshop page is hidden by default. Enable it in Settings when needed. Browse public content, search and filter items, view details, and add them to the download queue. Tasks can be paused, resumed, retried, or removed. After a download completes, follow the app prompt to inspect and add it to the local Mod list.

Some Workshop downloads require Steam sign-in. Steam Guard is handled in the app; whether an item can be downloaded still depends on Steam and the content's own access conditions.

#### 3. Manage and synchronize Mods

Newly imported Mods are synchronized to the game's `Mod` directory by default, with a progress display; manual sync is always available. The Manager's **Sync to game** setting controls only whether files are present in that directory; the in-game official Mod panel refreshes the list and controls hot loading, enabling, disabling, and ordering.

Both synchronization and save editing need the patched game's storage service to be running. If the app says the service is not running, tap **Launch the game and keep it in the background**; on Xiaomi devices, enabling the game's autostart permission avoids having to do this every time.

Mods can be renamed (the display name in the Manager only; game-directory contents are unaffected). **Export/Share Mods** packs one or more selected Mods to share with other apps or save locally, optionally password-protected; each Mod keeps its own top-level directory in the ZIP, so it can be imported back as-is.

Removing a Mod from the Manager also schedules removal of the corresponding Manager-owned game directory. Clearing the cache follows the same rule. Mods placed directly in the game's `Mod` directory appear as **Other Mods in game** in the Manager, but are not modified or deleted.

#### 4. Merge Mods

Open **Merge Mods** from the Mod-management page and select at least two imported, cached Mods. The top of the list is lower priority and the bottom is higher priority; later Mods override earlier ones. The generated Mod returns to the ordinary cache and synchronization system, and does not change ordinary or in-game Mod ordering.

The local workflow is based on the MIT-licensed upstream [`sutan-game`](https://github.com/fentender/sutan-game) project. Merging runs in the Manager rather than in the native loader at game runtime. Because the game's original JSON cannot be extracted, Android uses a no-base-JSON overlay and does not distribute game-original JSON.

#### 5. Edit saves

Save editing needs the patched game running in the background. If the app says the save service is not running, tap **Launch the game and keep it in the background**.

The flow: pick a game user (a `SAVEDATA/<user>` directory under the game's save location; user 0 is collapsed by default) → pick a save file (10 load slots, the `auto_save.json` autosave, and other save files collapsed by default) → edit in the built-in editor page → save.

- **Save to game save** overwrites the file currently open.
- Under **Slots / Backups**, the editor's current content can be saved to a load slot; the app updates the load index and the file being edited is left untouched.
- Before every overwrite save or backup restore, the app snapshots the current save — up to the 10 most recent per file, restorable or deletable in one tap under **Slots / Backups**.
- Account-level global data such as the Fate Bazaar lives in `global.json`, which has its own save and backup channel, also capped at 10.

Save in-game and exit before editing, and re-enter the game to load after restoring a backup.

The editor page is the upstream web-based save editor for *Sultan's Game* (GPLv3, by 柳漪春涛). This project only provides the Android integration and save read/write; the editing logic is unmodified. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

### Settings and maintenance

The **Settings** page contains:

- **Clear local Mods** — removes every Mod added in the Manager and schedules removal of the matching game-directory entries.
- **Reset Manager state** — for import or patching trouble. Keeps cached Mods and the device signing key, but cancels and clears Workshop downloads and staging, abandons unfinished install sessions and patch transactions, cleans the merge workspace, **signs out of Steam**, and shows the disclaimer again.
- **Workshop** — whether the Workshop page appears in navigation (off by default).
- **App updates** — whether to contact GitHub on startup to check for a newer release (on by default).
- **Open-source licenses** — this project is GPLv3.

### Mod ZIP requirements

- Each Mod directory must contain a valid `info.json`; one ZIP may contain multiple top-level Mod directories.
- The import process checks directory structure and path safety; unsafe or duplicate entries are rejected.
- If `info.json` is not in the ZIP root or a first-level subdirectory, the app offers a deep-scan import that can find Mods at deeper levels after confirmation.
- Encrypted ZIPs are supported; the app prompts for the password.
- ZIPs exported by this Manager keep one top-level directory per Mod and can be imported back as-is.
- See the [architecture document](docs/architecture.md#mod-format) for Mod formats, configuration directories, and merge rules.

### How it works

1. **Extract** — Read APKs from the installed game or a user-selected local installation package.
2. **Sign** — Re-sign the base APK, original splits, and loader split with an RSA-4096 key generated in the device's Android KeyStore.
3. **Install** — Install through one multi-APK Android `PackageInstaller` session.
4. **Load** — The native `libmodloader.so` in the co-signed loader split loads automatically when the game starts and enables Mod support.
5. **Sync / Edit** — The Manager synchronizes Mods and reads/writes saves through the co-signed `ModStorageProvider` in the loader split — no root, no base-APK modification.

### Developers: build from source

#### Project layout

```
├── android/                    # Android applications
│   ├── bootstrap/              # Loader split (ContentProvider + libmodloader.so carrier)
│   ├── manager/                # Manager application
│   │   ├── app/                # Main app (Compose UI + platform implementations + Python merge scripts)
│   │   ├── merge-native/       # JNI JSON-repair library for merging
│   │   ├── tools/              # Chaquopy Android wheel staging and build
│   │   └── core/               # Platform-independent core libraries
│   │       ├── apk/            # Read-only APK inspection
│   │       ├── game-bridge/    # Game communication bridge (Mod sync + save read/write)
│   │       ├── loader-split/   # Loader split factory interfaces
│   │       ├── merge/          # Mod merge orchestration
│   │       ├── model/          # Domain models
│   │       ├── steam-protocol/ # Steam CM protocol client
│   │       ├── storage/        # Local Mod storage abstractions
│   │       ├── workshop/       # Workshop browsing and metadata
│   │       └── workshop-download/ # Workshop downloads and integrity checks
├── native/                     # C++ native loader
│   ├── include/modloader/      # Headers
│   ├── src/                    # Implementation
│   ├── tests/                  # Unit tests
│   └── third_party/dobby/      # Dobby (git submodule)
├── scripts/                    # Environment check, release pipeline, and verification scripts
├── docs/                       # Build and architecture documentation
```

#### Prerequisites

- **JDK 21** — Kotlin/JVM compilation
- **Android SDK Platform 37 and 35** — 37 is the current `compileSdk`; 35 provides the `android.jar` used to generate the loader split template; both are required
- **Android NDK 27.0+**
- **CMake 3.22+** and Ninja — native builds
- **Gradle** — through `android/manager/gradlew`
- **Python 3.11** — only needed to build the Chaquopy wheel for Mod merging; see the [build guide](docs/build.md)

Initialize submodules right after cloning, or the native build fails (Dobby is pulled in as a git submodule):

```bash
git submodule update --init --recursive
```

Then run the environment check from the repository root (it only checks Platform 35; confirm Platform 37 is installed separately):

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

Output: `native/build-android/libmodloader.so`. Release artifacts must be ELF64/AArch64, have `0x4000` alignment on every `PT_LOAD`, and contain no `TEXTREL`. See the [build guide](docs/build.md) for template regeneration, Manager release builds, and tests.

#### Release build

The release pipeline regenerates the native loader, Bootstrap AAR, unsigned split template, structural metadata, Chaquopy wheel, and signed Manager APK in that order. It requires the local, untracked release keystore/password files and environment variables for the SDK, NDK, and JDK:

```bash
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<ndk-version>"
bash scripts/build-release.sh
```

The template and metadata are generated under `android/manager/app/build/` and are not source-controlled. Do not run only `:app:assembleRelease`; the release pipeline must first generate and validate the loader template. See the [build guide](docs/build.md) for the staged native → Bootstrap → template pipeline and tests.

### Technology stack

- **UI**: Jetpack Compose + [Miuix](https://github.com/compose-miuix-ui/miuix) theme
- **Native**: C++17 (ARM64), JNI, IL2CPP runtime integration
- **Mod merging**: Chaquopy + CPython 3.11 (`arm64-v8a`), JNI JSON-repair library
- **Save editing**: WebView (AndroidX WebKit) + an injected JavaScript shim
- **Build**: Gradle (Kotlin DSL), CMake, Ninja
- **Signing**: Android KeyStore, APK Signature Scheme v1+v2
- **Workshop**: Steam Community browsing, Steam CM protocol, Protobuf, OkHttp, Coil
- **Dependency versions**: see [`android/manager/gradle/libs.versions.toml`](android/manager/gradle/libs.versions.toml)

### License

This project is released under the GNU General Public License v3.0. See [LICENSE](LICENSE).

This application is an independent community tool and is not affiliated with or endorsed by the rights holders of *Sultan's Game*, its publishers, or Valve. Game names, trademarks, and content rights belong to their respective owners.

### Acknowledgments

- [苏游修改器 / suyou-save-editor](https://github.com/khb10533/suyou-save-editor) — the web save editor (by 柳漪春涛)
- [sutan-game](https://github.com/fentender/sutan-game) — Mod merger
- [Workshop-Native](https://github.com/cjtestuse/Workshop-Native) — Workshop browsing and sign-in interaction adaptation source
- [WorkshopAndroidDownloader](https://github.com/Apricityx/WorkshopAndroidDownloader) — Steam CM protocol and Workshop download engine source
- [Dobby](https://github.com/jmpews/Dobby) — ARM64 dynamic binary instrumentation framework
- [apksig](https://android.googlesource.com/platform/tools/apksig) — Android APK signing library
- [Chaquopy](https://chaquo.com/chaquopy/) — the CPython 3.11 runtime that Mod merging needs on Android
- [yyjson](https://github.com/ibireme/yyjson) and [rapidfuzz-cpp](https://github.com/rapidfuzz/rapidfuzz-cpp) — C++ JSON and fuzzy-matching libraries used by merging
- [Miuix](https://github.com/compose-miuix-ui/miuix) — Compose UI theme

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full license and provenance record.
