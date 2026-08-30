# Third-party source notice

## 苏游修改器 / suyou-save-editor (khb10533/suyou-save-editor)

`android/manager/app/src/main/assets/save-editor/index.html` is the upstream
**苏游修改器 · 柳漪春涛正式版 v1** save editor, <https://github.com/khb10533/suyou-save-editor>,
commit `d2bb41fee0b81503424a8bf108e1d4ed1e8cba72`, SHA-256
`e1fe268866a99cbf036c5fdadb9c39211e2d9372e6b8d90ace2a8fe52e8360e3`.

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
is adapted from **Workshop-Native**, commit
`f25129d62bb86d610a723a338ef25f7b134cbf9d`, obtained from a local source archive during development.

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
