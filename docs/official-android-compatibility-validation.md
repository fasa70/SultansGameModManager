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

The current master release template combines the protocol v2 Bootstrap with the official UI reveal, URI/Texture, and TMP compatibility native build. It is built from the native artifact with every release gate explicitly enabled, then regenerated from the Bootstrap AAR; the Manager only consumes this verified frozen asset.

```text
embedded native SHA-256:
404b7caa0aab2c02fe6e1217616291e4e91bed57eb858e9b15ec135d2f4d29a8

complete unsigned template SHA-256:
fbc06a1ddfdae416095e0523d89da225bf29640ed7db71ab90ca2eabf01287c6

Bootstrap ModStorage protocol:
2
```

The frozen template is unsigned, identifies package `com.gametree.sultan.pd`, split `modloader`, version code `10005`, and version name `1.0.5`; its native payload is stored at `assets/modloader/arm64-v8a/modloader.bin` without ZIP compression. The public identity is recorded in `release/loader-template-10005.json`; production and test digest pins must be updated with the binary in the same change.

### Offline acceptance

- Native host tests: 2/2 passed for the validated native artifact.
- Android target: ELF64 AArch64.
- Four `PT_LOAD` segments, each aligned to `0x4000`.
- No `TEXTREL`.
- Frozen Manager embedded native SHA-256: `404b7caa0aab2c02fe6e1217616291e4e91bed57eb858e9b15ec135d2f4d29a8`.
- Frozen unsigned template SHA-256: `fbc06a1ddfdae416095e0523d89da225bf29640ed7db71ab90ca2eabf01287c6`.
- The native payload and template were rebuilt and checked for digest closure, stored compression, and unsigned state before publication.
