# Merge native helper

This Android library contains the upstream `JsonRepairer` source from
`sutan-game-master/csrc/json/json_cleaner.*` and a small JNI facade.

It is separate from the game loader's `libmodloader.so`. It does not read game
files or run inside the game process.

Chaquopy 17.0.0 is configured for the Manager app with minSdk 24 and only the
`arm64-v8a` ABI. Python is not packaged in the bootstrap loader split.

The Manager APK carries the fixed upstream `id_remap.py` algorithm under
`app/src/main/python` (upstream tree at `upstream_sultan/core/mod/id_remap.py`,
driven by this project's `android_merge_worker.py` and
`android_merge_id_remap.py`), plus a Catalog-backed adapter which copies selected
Mod inputs into a temporary workspace. It never loads or ships game-original JSON
and does not invoke the upstream desktop base/delta workflow.

The separate `android/manager/tools/sultan-core-wheel` staging directory contains
the minimum upstream JSON/`json_ops` source set for a Chaquopy Android wheel. Its
source locks are:

- `sutan-game-master@2ea5ddb95bfa9fe419540396b637977d0c4293d7`
- `yyjson@8b4a38dc994a110abaec8a400615567bd996105f`
- `rapidfuzz-cpp@6c10b68930df73bfe5679720e4008518ba4265b1`

The source is covered by the upstream MIT notice in
`../core/merge/SOURCE_NOTICE.md` and the staged third-party license files.
