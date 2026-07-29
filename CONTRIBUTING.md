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

### Pull Requests

1. Run `./gradlew :core:model:test :core:apk:test :core:storage:test` and ensure all pass
2. If modifying native code, verify `cmake --build native/build-host && ./native/build-host/modloader_core_tests` passes
3. If modifying Android framework code, run `./gradlew :app:connectedDebugAndroidTest` on an emulator or device
4. Keep PRs focused — one concern per PR

## License

By contributing, you agree that your contributions will be licensed under the GNU GPLv3. See [LICENSE](LICENSE) for the full text.
