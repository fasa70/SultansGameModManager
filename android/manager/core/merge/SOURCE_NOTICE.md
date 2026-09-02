# Source notice

This module reuses behavior and algorithms from the MIT-licensed upstream project
`sutan-game-master`.

Copyright (c) 2025 Fentende

The upstream project is licensed under the MIT License:

```text
MIT License

Copyright (c) 2025 Fentende

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The Android worker reuses only the upstream merge-related components:

- `csrc/json/json_cleaner.*` for non-standard JSON repair;
- `csrc/json/json_doc.*`, `json_val.*`, and their required JSON support;
- `csrc/json_ops/*` for typed ID extraction and replacement;
- `csrc/delta/array_match.*` for Mod-to-Mod special-array matching;
- `src/core/mod/id_remap.py` for ID conflict and allocation semantics.

The Android implementation does **not** distribute game-original JSON, the
upstream desktop GUI, Steam/Workshop integration, or the upstream full
base-document delta workflow. Android uses an explicit no-base-JSON overlay
mode: absent fields are not deletions, and the game itself supplies untouched
base content at load time.

Any third-party runtime or native dependency added for the worker must retain
its own license and notice in the resulting distribution.

## Android runtime and dependency locks

The Manager Android app uses Chaquopy `17.0.0` with CPython `3.11` and raises
its minimum Android API to 24. Python is packaged only in the Manager APK for
`arm64-v8a`; it is not included in the Bootstrap/loader split and does not run
inside the game process. Chaquopy and CPython notices must accompany release
artifacts according to their distribution terms.

The Android worker source is locked to upstream `sutan-game-master`
commit `2ea5ddb95bfa9fe419540396b637977d0c4293d7` (v1.4.4). Its C++ JSON
runtime uses these exact submodule commits:

- yyjson `8b4a38dc994a110abaec8a400615567bd996105f` (0.12.0, MIT);
- rapidfuzz-cpp `6c10b68930df73bfe5679720e4008518ba4265b1` (3.3.3, MIT).

Their license texts are staged beside the wheel build sources. No game
original JSON, device cache export, release key, or password is included.
