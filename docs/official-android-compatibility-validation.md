# Official Android compatibility validation

## 2026-08-09 final UI + URI + TMP combination

The official native backend was validated on the ARM64 Android 1.0.5 profile with the following compatibility features enabled together:

- official Mod UI reveal and panel observers;
- resource argument compatibility for `LoadSprite`, `LoadSpriteImmediate`, `LoadAudioClip`, and `UnityWebRequestTexture.GetTexture`;
- TMP glyph field-name compatibility from `m_SpriteGlyphTable` to `m_GlyphTable`.

### GetTexture SIGILL isolation

Instrumenting or replacing the single-argument `GetTexture(System.String)` entry at RVA `0x3ff95dc` caused SIGILL when the official Mod panel loaded previews. IDA confirmed that entry is only a forwarding stub:

```text
MOV W1, WZR
B   0x3ff95e4
```

The stable implementation leaves that forwarding stub untouched and replaces the full `GetTexture(System.String, System.Boolean)` implementation at RVA `0x3ff95e4`. The replacement preserves the ARM64 IL2CPP arguments, rewrites only eligible Mod paths, and calls the saved trampoline. Both overloads remain gated by exact metadata signatures, static flags, RVAs, and code fingerprints.

Rewritten managed strings are retained with bounded, deduplicated GC handles for the process lifetime. `LoadSpriteImmediate` continues to receive an absolute filesystem path; URI-based loaders receive `file://` arguments.

### Device evidence

The final combination reported:

```text
official_uri=ready
uri_sprite=ready
uri_audio=ready
uri_texture=ready
tmp_glyph=ready
ui_reveal=ready
official_ui_observer=ready
official_canary=ready
```

After the user manually opened the official Mod UI and refreshed:

```text
mods=5
panel_mods=5
GetTexture rewrites=5
```

The user confirmed that all previews rendered correctly and that the TMP compatibility fix was effective. The natural official activation chain progressed from `active_mods=0` through `active_mods=4`; resource logs also confirmed audio, sprite, and immediate-path handling. An automated follow-up observed the same PID for 301 seconds with zero SIGILL/fatal matches. The PID was then externally replaced after the user had finished interacting, without a SIGILL/fatal record for the original process, so this is recorded as five minutes of continuous-process evidence rather than a completed ten-minute single-process run. No loader code directly invoked `RefreshMods`, `LoadUserMods`, `LoadGlobalMods`, `ActiveMod`, or `ModLoader.Run`, and no Mod data or `mods.json` was modified.

### Frozen Manager template

The Manager release asset was rebuilt from the current feature HEAD with all official compatibility gates explicitly enabled. This rebuild is the artifact shipped by future Manager patch operations; it is distinct from the earlier device-signed diagnostic split used for runtime validation.

```text
embedded native SHA-256:
404b7caa0aab2c02fe6e1217616291e4e91bed57eb858e9b15ec135d2f4d29a8

complete unsigned template SHA-256:
f811b0b7b4b93287b6babe2c337c28c047f504b4bc7225d03b31b140a9adb9b3
```

The frozen template is unsigned, identifies package `com.gametree.sultan.pd`, split `modloader`, version code `10005`, and version name `1.0.5`; its native payload is stored at `assets/modloader/arm64-v8a/modloader.bin` without ZIP compression. Static build and hash validation proves that it contains the current official UI + URI/texture + TMP source combination. The earlier runtime evidence applies to the same source behavior, but this exact rebuilt hash was not separately installed during the artifact-freezing step.

### Offline acceptance

- Native host tests: 2/2 passed.
- Android target: ELF64 AArch64.
- Four `PT_LOAD` segments, each aligned to `0x4000`.
- No `TEXTREL`.
- Frozen Manager embedded native SHA-256: `404b7caa0aab2c02fe6e1217616291e4e91bed57eb858e9b15ec135d2f4d29a8`.
- Earlier device-validated diagnostic native SHA-256: `9b9173cdb939fa6eda40abd06081bac755f208c2401a93fed8d3d3a8facc8f71`.
- The signed diagnostic split retained the expected release certificate and the base APK remained byte-identical to the prior signed installation set.
