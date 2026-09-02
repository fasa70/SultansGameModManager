# Contributing

## Scope

This project is an independent community tool for bringing Windows mod compatibility to the Android version of *Sultan's Game*. Contributions should focus on:

- Improving mod compatibility and loading reliability
- Fixing bugs in the Manager app (UI, patching flow, Workshop integration, Mod merging, save editor)
- Documentation improvements
- Test coverage

**Out of scope:**
- Distributing game assets, binaries, or copyrighted content
- Bypassing DRM, paid content, or platform restrictions
- Tools for extracting or decrypting game files

## Development Setup

See [docs/build.md](docs/build.md) for the authoritative environment table and detailed build instructions. In short: JDK 21, Android SDK platforms 37 and 35, NDK 27, CMake 3.22+ with Ninja, and `git submodule update --init --recursive` after cloning (Dobby is a submodule).

## Tests

Run the relevant tests before submitting:

- Kotlin: the Gradle test tasks listed in [docs/build.md](docs/build.md#verification-and-tests) (`:core:merge:test` needs `--no-configuration-cache`).
- Native: build and run the host tests with `ctest` when on a compatible host.
- Loader/bootstrap changes: the loader template structural validation in [docs/build.md](docs/build.md#verification-and-tests).

## Code style

- Native C++17: 2-space indent, `snake_case` functions, `PascalCase` types, warnings are errors.
- Kotlin: standard conventions, 4-space indent.
- Python: follow the existing files' style.

## License

By contributing, you agree that your contributions will be licensed under the GNU GPLv3. See [LICENSE](LICENSE) for the full text.
