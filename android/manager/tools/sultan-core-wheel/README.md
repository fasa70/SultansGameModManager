# sultan_core Android wheel staging

This directory stages the minimum upstream C++ JSON and `json_ops` sources
needed by the Android ID-remap worker. It is pinned to:

- sutan-game-master `2ea5ddb95bfa9fe419540396b637977d0c4293d7`
- yyjson `8b4a38dc994a110abaec8a400615567bd996105f`
- rapidfuzz-cpp `6c10b68930df73bfe567972e4008518ba4265b1`

The generated wheel is an Android/Chaquopy build artifact and must not be
replaced by a host CPython wheel. The build is intentionally separate from
the game loader and excludes the upstream state/delta/base-document path.
