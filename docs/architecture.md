# Architecture

## Overview

Sultan's Game Mod Manager adds official Windows mod support to the Android version of *Sultan's Game*. The game uses Unity with IL2CPP scripting backend, and while the Windows build has built-in mod loading (directory scanning, JSON config merging, resource overrides), the Android build has this functionality present in the native library but disabled or inaccessible due to code paths that differ from Windows.

The currently frozen official Android profile targets package `com.gametree.sultan.pd`, version code `10005` (`1.0.5`), and `arm64-v8a`. The release loader combines the official Mod UI reveal, resource URI compatibility, and TMP glyph-field compatibility gates. Unknown profiles fail closed rather than receiving a best-effort patch.

The solution has three layers:

1. **Native loader** (`libmodloader.so`) — injected into the game process to intercept IL2CPP runtime calls and inject mod data
2. **Loader split APK** — an Android split APK sharing the game's package ID, carrying the native library via a `ContentProvider`
3. **Manager app** — a separate Android app that extracts, signs, and installs the patched game

## Design Decisions

### Why a split APK instead of modifying the base APK?

The game's base APK is protected by an "Ano" shell. Any modification to the base APK's DEX, manifest, resources, or native libraries triggers a crash (SIGBUS/SIGSEGV) during initialization. The only safe operation on the base APK is removing the original signature and re-signing.

A split APK, installed alongside the base APK in a single PackageInstaller session, can declare the same package name and carry additional native libraries without modifying the base.

### Why device-scoped signing?

To keep the solution serverless and preserve user privacy:

- Each device generates its own RSA-4096 key pair in Android KeyStore
- The private key is non-exportable — it never leaves the device
- Base APK + original splits + loader split are all re-signed with this device key
- The original signature is replaced (Android requires all splits in a session share the same signer)

This means the patched game can only be installed on the device that created the signature. If the user switches devices, they must re-extract and re-sign.

### Why APK Signature Scheme v1+v2?

Analysis of the official APK shows it uses v1+v2 (not v3). We match this to avoid introducing unnecessary signature scheme differences that could trigger compatibility issues.

## Component Architecture

```
┌──────────────────────────────────────────┐
│              Manager App                  │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐ │
│  │ Patch   │  │  Mod     │  │ Workshop │ │
│  │ Screen  │  │ Manager  │  │ Client   │ │
│  └────┬────┘  └────┬─────┘  └────┬─────┘ │
│       │            │              │       │
│  ┌────┴────────────┴──────────────┴─────┐ │
│  │         Core Libraries               │ │
│  │  APK │ Model │ Storage │ Steam Proto │ │
│  └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
                    │
        PackageInstaller (system)
                    │
        ┌───────────┴───────────┐
        │                       │
┌───────┴────────┐    ┌────────┴──────────┐
│  Base APK      │    │  Loader Split APK │
│  (re-signed)   │    │  (re-signed)      │
│                │    │  ┌──────────────┐ │
│                │    │  │ContentProvider│ │
│                │    │  │ extracts .so  │ │
│                │    │  │ to code_cache │ │
│                │    │  └──────┬───────┘ │
│                │    │         │         │
│                │    │    System.load()    │
│                │    │         │         │
│                │    │  ┌──────┴───────┐ │
│                │    │  │libmodloader  │ │
│                │    │  │  .so         │ │
│                │    │  └──────────────┘ │
└────────────────┴────┴───────────────────┘
```

## Native Loader Pipeline

When the game process starts and `libmodloader.so` is loaded:

1. **Wait for IL2CPP** — poll `libil2cpp.so` exports until `il2cpp_domain_get` and other required symbols are available
2. **Verify game profile** — check exact package/version/ABI metadata plus in-memory byte patterns and method signatures against the frozen official profile
3. **Install official compatibility gates** — when the release flags are enabled, install the official UI reveal/observer, resource URI/Texture, and TMP glyph-field hooks; any failed gate rejects the compatibility state
4. **Use the official Mod backend** — the game scans all directories below `externalFilesDir/Mod`; the official in-game panel owns discovery refresh, hot loading, enable/disable state, and ordering
5. **Synchronize Manager-owned Mods** — the Manager sends each validated Mod through the co-signed Provider, which writes only an owner-prefixed directory using per-Mod staging and replacement; it never replaces the whole `Mod` root or external Mod directories
6. **Keep Android 15+ activation explicit** — when the Provider is unavailable until the game has been started, the Manager persists pending work, asks the user to leave the game in the background, and retries when the Manager resumes

The complete release combination and its device evidence are recorded in [official Android compatibility validation](official-android-compatibility-validation.md). The Manager's unsigned frozen template is the artifact used by future patch operations; the Manager does not compile a new native library at patch time.

## Manager-side Mod Merge

The Manager provides a local Mod merge workflow based on the MIT-licensed upstream `sutan-game-master` project. The reused source, pinned revision, and license terms are documented in [`android/manager/core/merge/SOURCE_NOTICE.md`](../android/manager/core/merge/SOURCE_NOTICE.md).

Users select at least two cached Mods and order them from low priority at the top to high priority at the bottom. The Manager imports the generated result as an ordinary cached Mod; the native loader does not merge Mods at game runtime, and the merge order does not change ordinary or in-game Mod ordering.

Because the game base JSON cannot be extracted, Android uses a no-base-JSON overlay workflow and does not distribute game-original JSON. Game/catalog version differences and ID conflicts are handled as best-effort warnings, so the user may continue. The Manager displays: **因Android版本限制，无法提取游戏Info，合并结果可能与上游项目有出入**. Invalid input or failed output operations still stop the merge, and partial results are not imported.

This policy only applies to Mod merging. APK patch/install and native loader compatibility checks remain fail-closed.

## Mod Format

Mods follow the official Windows mod structure:

```
Mod/<mod-name>/
├── info.json          # Required: name, description, tags, version
├── preview.jpg        # Optional: preview image (≤1MB)
├── config/            # Optional: configuration files
│   ├── cards.json      # Card definitions
│   ├── upgrade.json    # Upgrade shop items
│   ├── over.json       # Endings
│   ├── quest.json      # 1001 Nights
│   ├── tag.json        # Tag definitions
│   ├── ui.json         # UI strings
│   ├── variable.json   # Game variables
│   ├── credits.json    # Credits data
│   ├── sfx_config.json # Sound effect configuration
│   ├── event/          # Event definitions (one JSON per event ID)
│   ├── rite/           # Rite definitions (one JSON per rite ID)
│   ├── loot/           # Loot tables
│   ├── after_story/    # After-story character configs
│   ├── init/           # Initialization configs
│   ├── dt/             # Dialog tree configs
│   ├── wizard/         # Wizard configs
│   └── rite_template/  # Rite template configs
├── bgm/               # Optional: background music replacement (.wav)
└── image/             # Optional: image replacement (.png)
    ├── cards/
    ├── head/
    ├── rite/
    └── tag/
```

### Data Merge Rules

- **Overwrite + append**: Mod data overwrites matching IDs and appends new IDs
- **Single-object merge**: `variable.json`, `credits.json`, `sfx_config.json` use field-level merge
- **sfx_config**: Only allows overwriting existing keys; the sole exception is `armageddon_music_loop` which can be added
- **Official panel order**: When multiple Mods define the same key, the game’s official Mod panel determines the effective load order; Manager directory names do not encode or control it
- **Best-effort ID remapping**: ID and tag conflicts continue through the upstream remapper and are reported as warnings
- **No-base-JSON limitation**: Android cannot extract the game's original JSON, so omitted fields do not mean deletion from the unavailable game base
