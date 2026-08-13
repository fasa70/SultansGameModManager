#!/usr/bin/env bash
# Build the Chaquopy CPython 3.11 arm64-v8a wheel used by the Manager app.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wheel_root="$repo_root/android/manager/tools/sultan-core-wheel"
python_cmd="${PYTHON:-python}"
android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
android_ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
target_version="${CHAQUOPY_TARGET_VERSION:-3.11.14-0}"
target_cache="$wheel_root/.cache/chaquopy-target-$target_version"
build_root="$wheel_root/.build/android"
install_root="$wheel_root/.build/install"

fail() { printf 'sultan_core wheel build failed: %s\n' "$*" >&2; exit 1; }
require_file() { [[ -f "$2" ]] || fail "missing $1: $2"; }
require_dir() { [[ -d "$2" ]] || fail "missing $1: $2"; }

native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

require_dir ANDROID_HOME "$android_home"
if [[ -z "$android_ndk" ]]; then
  while IFS= read -r candidate; do
    if [[ -f "$candidate/build/cmake/android.toolchain.cmake" ]]; then android_ndk="$candidate"; break; fi
  done < <(printf '%s\n' "$android_home"/ndk/27.* | sort -V -r)
fi
require_dir ANDROID_NDK_HOME "$android_ndk"
require_file 'Android NDK toolchain' "$android_ndk/build/cmake/android.toolchain.cmake"

python_version="$($python_cmd -c 'import sys; print(".".join(map(str, sys.version_info[:3])))')"
[[ "$python_version" == 3.11.* ]] || fail "PYTHON must be CPython 3.11 (found $python_version)"
"$python_cmd" -c 'import nanobind' >/dev/null 2>&1 || fail 'nanobind is not installed in PYTHON'
nb_dir="$($python_cmd -c 'import nanobind; print(nanobind.cmake_dir())')"
require_dir nanobind "$nb_dir"

cmake_bin="${CMAKE:-}"
if [[ -z "$cmake_bin" ]]; then
  while IFS= read -r candidate; do
    if [[ -f "$candidate/cmake.exe" ]] && "$candidate/cmake.exe" --version >/dev/null 2>&1; then cmake_bin="$candidate/cmake.exe"; break; fi
  done < <(printf '%s\n' "$android_home"/cmake/*/bin | sort -V -r)
fi
[[ -n "$cmake_bin" ]] || fail 'CMake 3.22+ not found'

ninja_bin="${NINJA:-}"
if [[ -z "$ninja_bin" ]]; then
  while IFS= read -r candidate; do
    if [[ -f "$candidate/ninja.exe" ]] && "$candidate/ninja.exe" --version >/dev/null 2>&1; then ninja_bin="$candidate/ninja.exe"; break; fi
  done < <(printf '%s\n' "$android_home"/cmake/*/bin | sort -V -r)
fi
[[ -n "$ninja_bin" ]] || fail 'Ninja not found'

host_python="$($python_cmd -c 'import sys; print(sys.executable)')"
target_zip="$target_cache/target-$target_version-arm64-v8a.zip"
target_root="$target_cache/stage"
if [[ ! -f "$target_zip" ]]; then
  mkdir -p "$target_cache"
  url="https://repo.maven.apache.org/maven2/com/chaquo/python/target/$target_version/target-$target_version-arm64-v8a.zip"
  printf '%s\n' "Downloading Chaquopy target: $url"
  curl --fail --location --retry 3 --output "$target_zip" "$url"
fi
if [[ ! -f "$target_root/include/python3.11/Python.h" || ! -f "$target_root/jniLibs/arm64-v8a/libpython3.11.so" ]]; then
  rm -rf "$target_root"
  "$python_cmd" - "$target_zip" "$target_root" <<'PY'
import sys
import zipfile
from pathlib import Path
archive = Path(sys.argv[1])
destination = Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as source:
    source.extractall(destination)
PY
fi
require_file 'Chaquopy Python.h' "$target_root/include/python3.11/Python.h"
require_file 'Chaquopy libpython3.11.so' "$target_root/jniLibs/arm64-v8a/libpython3.11.so"

rm -rf "$build_root" "$install_root"
mkdir -p "$build_root" "$install_root"
source_native="$(native_path "$wheel_root/src/sultan_core_android")"
build_native="$(native_path "$build_root")"
install_native="$(native_path "$install_root")"
include_native="$(native_path "$target_root/include/python3.11")"
library_native="$(native_path "$target_root/jniLibs/arm64-v8a/libpython3.11.so")"
host_python_native="$(native_path "$host_python")"
ndk_toolchain_native="$(native_path "$android_ndk/build/cmake/android.toolchain.cmake")"
nb_dir_native="$(native_path "$nb_dir")"

printf '%s\n' 'Configuring Android arm64-v8a nanobind module'
MSYS_NO_PATHCONV=1 "$cmake_bin" \
  -S "$source_native" -B "$build_native" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$(native_path "$ninja_bin")" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk_toolchain_native" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release -Dnanobind_DIR="$nb_dir_native" \
  -DSULTAN_CHAQUOPY_ANDROID=ON \
  -DCHAQUOPY_PYTHON_INCLUDE_DIR="$include_native" \
  -DCHAQUOPY_PYTHON_LIBRARY="$library_native" \
  -DPython_EXECUTABLE="$host_python_native" \
  -DCMAKE_INSTALL_PREFIX="$install_native"
MSYS_NO_PATHCONV=1 "$cmake_bin" --build "$build_native" --parallel
MSYS_NO_PATHCONV=1 "$cmake_bin" --install "$build_native"

"$python_cmd" "$wheel_root/package_wheel.py" \
  --project "$wheel_root/pyproject.toml" \
  --install-root "$install_root" --output-dir "$wheel_root/dist" \
  --python-tag cp311 --abi-tag cp311 --platform-tag android_24_arm64_v8a
printf '%s\n' "Wheel written under $wheel_root/dist"
