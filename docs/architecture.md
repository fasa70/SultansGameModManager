# Architecture

## Overview

Sultan's Game Mod Manager adds official Windows mod support to the Android version of *Sultan's Game*. The game uses Unity with IL2CPP scripting backend, and while the Windows build has built-in mod loading (directory scanning, JSON config merging, resource overrides), the Android build has this functionality present in the native library but disabled or inaccessible due to code paths that differ from Windows.

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
│                │    │  System.load()    │
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
2. **Verify game profile** — check in-memory byte patterns against the known-good game version to confirm compatibility
3. **Install hooks** — use Dobby to intercept four crash-prone functions (`RefreshMods`, `LoadUserMods`, `LoadGlobalMods`, `ModLoader.Run`) and replace them with no-ops
4. **Observe config loading** — hook `LoadConfig` and `LoadRitePostProcess` to detect when the game has finished loading its built-in configuration
5. **Apply mod pipeline** — staged pipeline:
   - `kUpgrade`: Upgrade shop config (high-priority, single mod file)
   - `kRite` / `kEvent`: Rite and event directories (per-ID JSON files with post-processing)
   - `kRemaining`: All other single-file and directory configs, including single-object merges (variable.json, credits.json, sfx_config.json)
6. **Resource overrides** — PNG/WAV file replacement via IL2CPP resource hooks

## Mod Format

Mods follow the official Windows mod structure:

```
Mod/<mod-name>/
├── info.json          # Required: name, description, tags, version
├── preview.jpg        # Optional: preview image (≤1MB)
├── config/            # Optional: configuration files
│   ├── cards.json     # Card definitions
│   ├── upgrade.json   # Upgrade shop items
│   ├── over.json      # Endings
│   ├── quest.json     # 1001 Nights
│   ├── tag.json       # Tag definitions
│   ├── ui.json        # UI strings
│   ├── variable.json  # Game variables
│   ├── credits.json   # Credits data
│   ├── sfx_config.json # Sound effect configuration
│   ├── event/         # Event definitions (one JSON per event ID)
│   ├── rite/          # Rite definitions (one JSON per rite ID)
│   ├── loot/          # Loot tables
│   ├── after_story/   # After-story character configs
│   ├── init/          # Initialization configs
│   ├── dt/            # Dialog tree configs
│   ├── wizard/        # Wizard configs
│   └── rite_template/ # Rite template configs
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
- **Last-wins**: When multiple mods define the same key, the lexically-later mod wins
