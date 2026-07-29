# Steam Workshop GPLv3 source notice

`core/steam-protocol` and `core/workshop-download` include adapted source files from `WorkshopAndroidDownloader-main` (commit `6443f81d5a462da52d3d3a5cbb22a265df39e6db`), provided by the user as GPLv3-licensed source.

The Manager keeps these components isolated from the game loader. Its adaptations disable HTTP endpoints, automatic redirects, and anonymous CDN fallback; downloaded content remains in Manager-private staging until the user explicitly imports a validated Mod.
