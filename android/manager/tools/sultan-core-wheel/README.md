# sultan_core Android wheel staging

This directory stages the minimum upstream C++ JSON and `json_ops` sources
needed by the Android ID-remap worker. It is pinned to:

- sutan-game-master `2ea5ddb95bfa9fe419540396b637977d0c4293d7`
- yyjson `8b4a38dc994a110abaec8a400615567bd996105f`
- rapidfuzz-cpp `6c10b68930df73bfe5679720e4008518ba4265b1`

The generated wheel is an Android/Chaquopy build artifact and must not be
replaced by a host CPython wheel. The build is intentionally separate from
the game loader and excludes the upstream state/delta/base-document path.

## Android build inputs

Chaquopy 17 uses CPython 3.11 and the `cp311-cp311-android_24_arm64_v8a`
wheel tag for this Manager. The native module is built against the matching
Chaquopy target headers and `libpython3.11.so`, downloaded from the Maven
coordinate `com.chaquo.python:target:3.11.14-0`, together with the Android NDK
27 toolchain. The generated `dist/` directory is intentionally ignored and
must not be committed; release/build automation should generate it before
Gradle runs the Chaquopy pip task.

The CMake option `SULTAN_CHAQUOPY_ANDROID=ON` enables the imported Chaquopy
Python target and the `.cpython-311.so` suffix used by Chaquopy 17's Android
Python importer. A normal host build leaves that option off.
The project metadata uses a PEP 440 version (`1.4.4.post1`), because pip rejects
`1.4.4.android1` even though the Android wheel platform tag remains explicit.
