# Contributing

## Scope

This project is an independent community tool for bringing Windows mod compatibility to the Android version of *Sultan's Game*. Contributions should focus on:

- Improving mod compatibility and loading reliability
- Fixing bugs in the Manager app (UI, patching flow, Workshop integration)
- Documentation improvements
- Test coverage

**Out of scope:**
- Distributing game assets, binaries, or copyrighted content
- Bypassing DRM, paid content, or platform restrictions
- Tools for extracting or decrypting game files

## Development Setup

See [docs/build.md](docs/build.md) for detailed build instructions.

### Environment

- JDK 21
- Android SDK 35 with NDK 27.0+
- CMake 3.22+ with Ninja

### First Build

```bash
git clone --recurse-submodules <repo-url>
cd SultansGameModManager
cd android/manager
./gradlew :core:model:test  # verify JVM tests pass
./gradlew :app:assembleDebug  # build debug APK
```

## Project Conventions

### Code Style

- **Kotlin**: Standard Kotlin conventions, 4-space indentation
- **C++**: C++17, 2-space indentation, `snake_case` for functions, `PascalCase` for types
- **Commit messages**: Short imperative summary in Chinese or English

### Architecture Principles

- **Fail-closed**: Unknown states default to refusing operation, not proceeding
- **No silent modification**: The app never modifies the game directory, APKs, or system settings without explicit user confirmation
- **Private data stays local**: Cached mods, download artifacts, and Steam session tokens are stored in the app's private directory only
- **Verifiable**: Every signing, extraction, and installation step produces verifiable results before proceeding
- **Base APK boundary**: Never modify the game's DEX, manifest, resources, or native libraries; only re-sign the base and install the same-signature loader split
- **Official profile gate**: Keep the frozen package/version/ABI and exact metadata/code-fingerprint checks fail-closed; do not broaden matching from runtime success alone
- **No direct activation**: Native compatibility hooks may observe the official activation chain, but must not directly invoke the game's ModLoader activation entry points

### Pull Requests

1. Run the Manager JVM suite:
   `./gradlew :core:model:test :core:apk:test :core:storage:test :core:workshop:test :core:steam-protocol:test :core:workshop-download:test :app:testDebugUnitTest`
2. If modifying native code, configure the official backend with all release gates enabled, build the host tests, and run `ctest --test-dir native/build-host --output-on-failure`.
3. If modifying the loader template or native loader, verify the unsigned template, package/version/split structure, ZIP_STORED native entry, and the native ELF contract described in [docs/build.md](docs/build.md). Native/template content is not pinned by a checked-in SHA-256; do not add or update digest constants for ordinary native changes.
4. Run `./gradlew :app:connectedDebugAndroidTest` only when an emulator/device is explicitly available and installation is authorized; otherwise record it as skipped rather than claiming it passed.
5. Keep PRs focused — one concern per PR

## License

By contributing, you agree that your contributions will be licensed under the GNU GPLv3. See [LICENSE](LICENSE) for the full text.
