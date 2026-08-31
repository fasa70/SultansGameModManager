#!/usr/bin/env bash
# Reproducibly build the official native loader, frozen split, and Manager APK.
# Local credentials and tool paths are supplied through environment variables.
set -euo pipefail

normalize_path() {
  case "$1" in
    [A-Za-z]:/*|[A-Za-z]:\\*)
      if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s\n' "$1"; fi
      ;;
    *) printf '%s\n' "$1" ;;
  esac
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_home="$(normalize_path "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}")"
android_ndk="$(normalize_path "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}")"
java_home="$(normalize_path "${JAVA_HOME:-}")"
keystore="${MANAGER_RELEASE_KEYSTORE:-$repo_root/release/manager-release.jks}"
password_file="${MANAGER_RELEASE_PASSWORD_FILE:-$repo_root/release/manager-release-password.txt}"
script_dir="$repo_root/scripts"
release_root="$repo_root/android/manager/app/build/release-stage"

fail() { printf 'Release build failed: %s\n' "$*" >&2; exit 1; }
require_dir() { [[ -n "$2" && -d "$2" ]] || fail "$1 is required: $2"; }
require_file() { [[ -f "$2" ]] || fail "Missing $1: $2"; }

is_executable() {
  [[ -x "$1" || ( -f "$1" && ( "$1" == *.bat || "$1" == *.cmd || "$1" == *.exe ) ) ]]
}

native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

version_major() {
  sed -n 's/.*version "\([0-9][0-9]*\)\..*/\1/p; s/.*version "\([0-9][0-9]*\)".*/\1/p' | head -1
}

require_java_21() {
  local java="$1" version
  version="$("$java" -version 2>&1 | version_major)" || fail "Cannot execute JDK: $java"
  [[ "$version" == "21" ]] || fail "JAVA_HOME must point to JDK 21 (found ${version:-unknown})"
}

select_build_tools() {
  local explicit="${ANDROID_BUILD_TOOLS:-}" directory candidate
  if [[ -n "$explicit" ]]; then printf '%s\n' "$explicit"; return; fi
  while IFS= read -r directory; do
    for candidate in "$directory/aapt2.exe" "$directory/aapt2"; do
      [[ -f "$candidate" ]] && printf '%s\n' "$directory" && return
    done
  done < <(printf '%s\n' "$android_home"/build-tools/* | sort -V -r)
  fail "Android build-tools not found under $android_home"
}

select_cmake() {
  local explicit="${CMAKE:-}" candidate directory
  if [[ -n "$explicit" ]]; then
    is_executable "$explicit" && printf '%s\n' "$explicit" || fail "CMake executable not found: $explicit"
    return
  fi
  while IFS= read -r directory; do
    for candidate in "$directory/cmake.exe" "$directory/cmake"; do
      if is_executable "$candidate" && "$candidate" --version >/dev/null 2>&1; then printf '%s\n' "$candidate"; return; fi
    done
  done < <(printf '%s\n' "$android_home"/cmake/*/bin | sort -V -r)
  candidate="$(command -v cmake 2>/dev/null || true)"
  [[ -n "$candidate" ]] && "$candidate" --version >/dev/null 2>&1 && printf '%s\n' "$candidate" && return
  fail 'CMake 3.22+ not found'
}

select_ninja() {
  local explicit="${NINJA:-}" candidate directory
  if [[ -n "$explicit" ]]; then
    is_executable "$explicit" && "$explicit" --version >/dev/null 2>&1 && printf '%s\n' "$explicit" || fail "Ninja not found: $explicit"
    return
  fi
  candidate="$(command -v ninja 2>/dev/null || true)"
  if [[ -n "$candidate" ]] && "$candidate" --version >/dev/null 2>&1; then printf '%s\n' "$candidate"; return; fi
  while IFS= read -r directory; do
    for candidate in "$directory/ninja.exe" "$directory/ninja"; do
      if is_executable "$candidate" && "$candidate" --version >/dev/null 2>&1; then printf '%s\n' "$candidate"; return; fi
    done
  done < <(printf '%s\n' "$android_home"/cmake/*/bin | sort -V -r)
  fail 'Ninja not found; set NINJA or install SDK CMake'
}

select_readelf() {
  local candidate directory
  while IFS= read -r directory; do
    candidate="$directory/llvm-readelf.exe"; [[ -f "$candidate" ]] && printf '%s\n' "$candidate" && return
    candidate="$directory/llvm-readelf"; [[ -x "$candidate" ]] && printf '%s\n' "$candidate" && return
  done < <(printf '%s\n' "$android_ndk/toolchains/llvm/prebuilt"/*/bin | sort -V -r)
  candidate="$(command -v llvm-readelf 2>/dev/null || true)"
  [[ -n "$candidate" ]] && printf '%s\n' "$candidate" && return
  fail 'llvm-readelf not found in the Android NDK'
}

assert_native_elf() {
  local readelf="$1" binary="$2" report="$3"
  "$readelf" -hW -lW -dW "$binary" >"$report"
  grep -q 'Class:[[:space:]]*ELF64' "$report" || fail "Native artifact is not ELF64 (see $report)"
  grep -q 'Machine:[[:space:]]*AArch64' "$report" || fail "Native artifact is not AArch64 (see $report)"
  ! grep -q 'TEXTREL' "$report" || fail "Native artifact contains TEXTREL (see $report)"
  awk '$1 == "LOAD" { count++; if ($NF != "0x4000") { printf "LOAD %d alignment is %s\n", count, $NF > "/dev/stderr"; bad=1 } } END { if (count == 0) { print "No LOAD segments found" > "/dev/stderr"; bad=1 } exit bad }' "$report" || fail "Native ELF 16 KB alignment check failed (see $report)"
}

require_dir ANDROID_HOME "$android_home"
require_dir ANDROID_NDK_HOME "$android_ndk"
[[ -n "$java_home" ]] || fail 'JAVA_HOME is required'
java="$java_home/bin/java.exe"; [[ -x "$java" ]] || java="$java_home/bin/java"
is_executable "$java" || fail "JAVA_HOME must contain bin/java: $java_home"
require_java_21 "$java"
keytool="$java_home/bin/keytool.exe"; [[ -x "$keytool" ]] || keytool="$java_home/bin/keytool"
is_executable "$keytool" || fail "JDK keytool not found: $java_home"
require_file 'release keystore' "$keystore"; require_file 'release password file' "$password_file"

cmake="$(select_cmake)"; ninja="$(select_ninja)"; readelf="$(select_readelf)"
android_jar="$android_home/platforms/android-35/android.jar"; build_tools="$(select_build_tools)"
aapt2="$build_tools/aapt2.exe"; [[ -f "$aapt2" ]] || aapt2="$build_tools/aapt2"
d8="$build_tools/d8.bat"; [[ -f "$d8" ]] || d8="$build_tools/d8"
apksigner="$build_tools/apksigner.bat"; [[ -f "$apksigner" ]] || apksigner="$build_tools/apksigner"
require_file 'Android API 35 android.jar' "$android_jar"; require_file 'aapt2' "$aapt2"; require_file 'd8' "$d8"; require_file 'apksigner' "$apksigner"
password="$(tr -d '\r\n' < "$password_file")"
certificate_sha256="$("$keytool" -list -v -keystore "$keystore" -storepass "$password" -alias manager-release | tr -d '\r' | awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}')"
[[ "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]] || fail 'Could not read release certificate SHA-256'

mkdir -p "$release_root"
stage="$(mktemp -d "$release_root/run.XXXXXX")"
rollback_on_exit() {
  local status=$?
  if [[ "$status" == 0 ]]; then rm -rf "$stage"; else printf 'Release staging retained: %s\n' "$stage" >&2; fi
  exit "$status"
}
trap rollback_on_exit EXIT
native_build="$repo_root/native/build-android-release"; native_binary="$native_build/libmodloader.so"; readelf_report="$stage/readelf.txt"; template_candidate="$stage/candidate/modloader-template-10005.apk"; publish_stage="$stage/publish"; manager_apk="$repo_root/android/manager/app/build/outputs/apk/release/app-release.apk"

printf '%s\n' '[1/5] Configure and build official native loader'
MSYS_NO_PATHCONV=1 "$cmake" -S "$(native_path "$repo_root/native")" -B "$(native_path "$native_build")" -G Ninja -DCMAKE_MAKE_PROGRAM="$(native_path "$ninja")" -DCMAKE_TOOLCHAIN_FILE="$(native_path "$android_ndk/build/cmake/android.toolchain.cmake")" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-35 -DMODLOADER_BACKEND_MODE=1 -DMODLOADER_OFFICIAL_URI_HOOKS=ON -DMODLOADER_OFFICIAL_URI_TEXTURE_HOOK=ON -DMODLOADER_OFFICIAL_TMP_GLYPH_HOOKS=ON -DCMAKE_BUILD_TYPE=Release
MSYS_NO_PATHCONV=1 "$cmake" --build "$(native_path "$native_build")"
require_file 'native Dobby submodule' "$repo_root/native/third_party/dobby/CMakeLists.txt"
require_file 'native loader' "$native_binary"
assert_native_elf "$readelf" "$native_binary" "$readelf_report"

export JAVA_HOME="$java_home"
export ANDROID_HOME="$android_home"
export ANDROID_NDK_HOME="$android_ndk"
printf '%s\n' '[2/6] Build protocol v2 Bootstrap AAR'
(cd "$repo_root/android/manager" && bash ./gradlew --no-configuration-cache :bootstrap:assembleRelease -PmanagerCertificateSha256="$certificate_sha256" -PmodloaderBinary="$(native_path "$native_binary")")
printf '%s\n' '[3/6] Build and validate frozen split candidate'
python -X utf8 "$repo_root/android/bootstrap/build_split_template.py" --bootstrap-aar "$(native_path "$repo_root/android/bootstrap/build/outputs/aar/bootstrap-release.aar")" --bootstrap-manifest "$(native_path "$repo_root/android/bootstrap/src/main/AndroidManifest.xml")" --android-jar "$(native_path "$android_jar")" --aapt2 "$(native_path "$aapt2")" --d8 "$(native_path "$d8")" --output "$(native_path "$template_candidate")" --version-code 10005 --version-name 1.0.5 --revision 2
python -X utf8 "$repo_root/android/bootstrap/build_split_template.py" --verify --output "$(native_path "$template_candidate")" --version-code 10005 --version-name 1.0.5 --revision 2
printf '%s\n' '[4/6] Stage and validate loader template'
PYTHONPATH="$script_dir" python -X utf8 "$script_dir/update-release-pins.py" --template "$template_candidate" --stage "$publish_stage"
PYTHONPATH="$script_dir" python -X utf8 "$script_dir/verify-loader-template.py" --stage "$publish_stage"
export PYTHON="${PYTHON:-python}"
printf '%s\n' '[5/6] Build Chaquopy CPython 3.11 Android wheel'
bash "$repo_root/scripts/build-sultan-core-wheel.sh"
printf '%s\n' '[6/6] Assemble signed Manager release'
rm -f "$manager_apk"
(cd "$repo_root/android/manager" && bash ./gradlew --no-configuration-cache :app:assembleRelease -PmanagerCertificateSha256="$certificate_sha256" -PmodloaderBinary="$(native_path "$native_binary")" -PreleaseTemplate="$(native_path "$template_candidate")")
require_file 'Manager release APK' "$manager_apk"
"$apksigner" verify "$manager_apk"
PYTHONPATH="$script_dir" python -X utf8 "$script_dir/verify-loader-template.py" --stage "$publish_stage" --manager-apk "$manager_apk"
printf 'Release APK: %s\n' "$manager_apk"
printf 'Release APK SHA-256: %s\n' "$(sha256sum "$manager_apk" | cut -d' ' -f1)"
