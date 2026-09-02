# Third-party source notice

This file is the authoritative license and provenance record for third-party
source this project vendors or adapts, and for the runtime components whose
notices must accompany release artifacts. Module-level detail lives in the
per-module notices linked from each section.

The project as a whole is distributed under GPLv3 (see [`LICENSE`](LICENSE)).
Each section below states its upstream's own license, which redistribution under
GPLv3 does not change.

## 苏游修改器 / suyou-save-editor (khb10533/suyou-save-editor)

`android/manager/app/src/main/assets/save-editor/index.html` is the upstream
**苏游修改器 · 柳漪春涛正式版 v2** save editor, <https://github.com/khb10533/suyou-save-editor>,
commit `ffbcb9871ec93f2599aeffa82c59176c30cf6d12`, SHA-256
`1b760303c7aba86c4063b4573ae6a2c538850ba6d83dd07dba9a0bd371f4accb`.

The file is vendored **byte-for-byte unmodified**. Copyright (C) 2026 柳漪春涛.
It is licensed under the GNU General Public License version 3 or (at the
licensee's option) any later version, and its own header carries the full
copyright and license notice. This project distributes it under the same GPLv3
terms; the repository's `LICENSE` reproduces the license text.

**How this project uses it:** the manager loads the file into a WebView from
app assets and drives it at runtime with an injected JavaScript shim, so no
upstream line is edited and an upstream update is a drop-in file replacement.
The shim hides the upstream file-picker affordance and redirects its download
path to the manager, which reads and writes the game's save files through the
patched game's `ModStorageProvider` instead. Upstream's own import/export,
editing semantics, bundled game data lookup, and UI are otherwise untouched.

**Bundled third-party components inside that file**, both inlined by upstream
and both MIT-licensed, whose notices upstream reproduces in the file header:

- Tailwind CSS v3.4.19 — MIT License — <https://tailwindcss.com>
- JSON5 v2.2.3 — MIT License — <https://json5.org>

**Game text carve-out.** The file also embeds the names and descriptions of the
game's cards, events, rites, and tags (`GAMEDB`, `TAGDB`, `TAG_NAMES`).
Upstream states that this text is copyright the developer of *Sultan's Game*,
that it is bundled only as a convenience for save editing, that it is **not**
covered by upstream's GPLv3 grant, and that upstream will remove it if the
rights holder objects. This project redistributes the file as upstream
publishes it and makes no additional claim over that text; the same removal
request would apply here.

## Workshop-Native

The Steam Workshop browse parser in
`android/manager/core/workshop/src/main/kotlin/com/sultansgame/modmanager/workshop/CommunityWorkshopBrowseParser.kt`
is adapted from **Workshop-Native**,
<https://github.com/cjtestuse/Workshop-Native>, commit
`f25129d62bb86d610a723a338ef25f7b134cbf9d`, obtained from a local source archive
during development.

Workshop-Native is licensed under GNU General Public License version 3.0.
This project distributes the adapted source under the same GPLv3 terms.
The adaptation changes its package names and connects it to this project's
App ID and domain models; its compatibility-oriented HTML/SSR/DOM parsing
logic is preserved.

The Workshop artwork retry logic in
`android/manager/app/src/main/java/com/sultansgame/modmanager/MainActivity.kt`
is adapted from Workshop-Native's
`app/src/main/java/com/slay/workshopnative/ui/components/ArtworkThumbnail.kt`
at the same commit and archive. This project retains only the ordered
candidate retry behavior and revalidates every candidate with its stricter
Steam HTTPS preview-image policy.

The Steam Guard interaction state handling in
`android/manager/app/src/main/java/com/sultansgame/modmanager/platform/auth/SteamCmAuthProvider.kt`
was additionally adapted from the code-challenge flow in Workshop-Native's
`app/src/main/java/com/slay/workshopnative/data/remote/SteamSessionManager.kt`
at the same commit and archive. This project retains its existing Kotlin
Steam CM transport instead of copying Workshop-Native's JavaSteam client;
the adaptation preserves the one-time code submission and result-polling
semantics needed to avoid duplicate Steam Guard requests.

## WorkshopAndroidDownloader

`android/manager/core/steam-protocol` and
`android/manager/core/workshop-download` contain adapted source from
**WorkshopAndroidDownloader**,
<https://github.com/Apricityx/WorkshopAndroidDownloader>, commit
`6443f81d5a462da52d3d3a5cbb22a265df39e6db`.

Upstream is licensed under the **Apache License, Version 2.0**. A verbatim copy
of that license is staged at
[`android/manager/core/steam-protocol/LICENSE.Apache-2.0`](android/manager/core/steam-protocol/LICENSE.Apache-2.0).
Upstream ships no `NOTICE` file, so Apache-2.0 section 4(d) adds no attribution
text to propagate. The Kotlin package names are left unchanged
(`top.apricityx.workshop.*`); this project's modifications disable HTTP
endpoints, automatic redirects, and anonymous CDN fallback, and keep downloaded
content in Manager-private staging until the user imports a validated Mod.

This upstream supplies the Steam CM protocol and authentication transport, CDN
and depot manifest handling, the download engine, chunk processing, and integrity
verification. It is **not** superseded by Workshop-Native above, which covers
only browse-page parsing, artwork retry behaviour, and Steam Guard interaction
semantics. Full detail:
[`android/manager/core/steam-protocol/SOURCE_NOTICE.md`](android/manager/core/steam-protocol/SOURCE_NOTICE.md).

## sutan-game (Mod merger)

The Manager-side Mod merge reuses behaviour and algorithms from the MIT-licensed
upstream **sutan-game**, <https://github.com/fentender/sutan-game>, commit
`2ea5ddb95bfa9fe419540396b637977d0c4293d7` (v1.4.4). Copyright (c) 2025 Fentende.

Vendored source:

- `android/manager/merge-native/src/main/cpp/upstream/` — upstream
  `csrc/json/json_cleaner.*`, `json_doc.h`, `json_val.h`
- `android/manager/app/src/main/python/upstream_sultan/` — upstream
  `src/core/mod/id_remap.py` and its required support modules
- `android/manager/tools/sultan-core-wheel/src/` — the minimum upstream C++ JSON
  and `json_ops` source set built into the Chaquopy wheel

The MIT license text and the full reused-component list are in
[`android/manager/core/merge/SOURCE_NOTICE.md`](android/manager/core/merge/SOURCE_NOTICE.md).
Android uses a no-base-JSON overlay mode and does not distribute game-original
JSON.

## Dobby

`native/third_party/dobby` is a git submodule of **Dobby**,
<https://github.com/jmpews/Dobby>, pinned at commit
`0c29f641c7e932af9ff99f4abf9ef98040a5bbba`. It is statically linked into
`libmodloader.so` and provides ARM64 inline hooking.

Dobby is licensed under the **Apache License, Version 2.0**; its own `LICENSE`
file is included in the submodule and no upstream `NOTICE` file is present. This
project does not modify Dobby's sources; `native/CMakeLists.txt` only sets its
build options and compensates for an include-order issue at the pinned commit.

## Chaquopy and CPython

The Manager APK embeds a CPython 3.11 runtime through **Chaquopy** `17.0.0`
(<https://chaquo.com/chaquopy/>) for `arm64-v8a` only, used by the Mod merge
worker. Python is not part of the Bootstrap/loader split and does not run inside
the game process.

Chaquopy's own license terms are declared in its repository
(<https://github.com/chaquo/chaquopy>); the bundled CPython runtime is licensed
under the **PSF License Agreement** (<https://docs.python.org/3/license.html>).
Their notices, as produced by the Chaquopy build, must accompany release
artifacts per their distribution terms; see
[`android/manager/core/merge/SOURCE_NOTICE.md`](android/manager/core/merge/SOURCE_NOTICE.md)
for the runtime and dependency locks.

## Bundled C++ libraries in the Chaquopy wheel

Staged under `android/manager/tools/sultan-core-wheel/src/sultan_core_android/`,
with their license texts kept beside the sources as `YYJSON_LICENSE` and
`RAPIDFUZZ_LICENSE`:

- **yyjson** `8b4a38dc994a110abaec8a400615567bd996105f` (0.12.0) — MIT —
  Copyright (c) 2020 YaoYuan — <https://github.com/ibireme/yyjson>
- **rapidfuzz-cpp** `6c10b68930df73bfe5679720e4008518ba4265b1` (3.3.3) — MIT —
  Copyright © 2020 Max Bachmann, Copyright © 2011 Adam Cohen —
  <https://github.com/rapidfuzz/rapidfuzz-cpp>

**nanobind** (<https://github.com/wjakob/nanobind>, BSD-3-Clause) is a build-time
dependency of that wheel and is not vendored in this repository.

## Binary dependencies

Gradle resolves these at build time; this repository does not vendor their
source. Versions are pinned in
[`android/manager/gradle/libs.versions.toml`](android/manager/gradle/libs.versions.toml),
and each dependency's license is the one its own upstream declares — this file
neither restates nor reproduces those licenses.

Listed because their role is easy to mistake for vendored source:

- **apksig** — APK signing in the patch pipeline
- **Miuix** (`top.yukonga.miuix.kmp`) — Compose UI theme and widgets
- **zip4j** — encrypted Mod ZIP import and export
- **OkHttp**, **Okio**, **Coil** — HTTP transport and image loading
- **jsoup** — Workshop HTML parsing
- **protobuf-javalite** — Steam CM message codecs
- **XZ for Java**, **zstd-jni** — Workshop payload decompression
- **AndroidX** libraries (Compose, Room, WorkManager, WebKit, …)

