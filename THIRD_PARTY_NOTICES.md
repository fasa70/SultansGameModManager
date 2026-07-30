# Third-party source notice

## Workshop-Native

The Steam Workshop browse parser in
`android/manager/core/workshop/src/main/kotlin/com/sultansgame/modmanager/workshop/CommunityWorkshopBrowseParser.kt`
is adapted from **Workshop-Native**, commit
`f25129d62bb86d610a723a338ef25f7b134cbf9d`, obtained from
`C:/Users/Admin/Downloads/Workshop-Native-main.zip` during development.

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
