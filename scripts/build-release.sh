#!/usr/bin/env bash
# Reproducibly build the official native loader, frozen split, and Manager APK.
# Local credentials and tool paths are supplied through environment variables.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
android_ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
java_home="${JAVA_HOME:-}"
keystore="${MANAGER_RELEASE_KEYSTORE:-$repo_root/release/manager-release.jks}"
password_file="${MANAGER_RELEASE_PASSWORD_FILE:-$repo_root/release/manager-release-password.txt}"

[[ -n "$android_home" && -d "$android_home" ]] || { printf 'ANDROID_HOME is required.\n' >&2; exit 1; }
[[ -n "$android_ndk" && -d "$android_ndk" ]] || { printf 'ANDROID_NDK_HOME is required.\n' >&2; exit 1; }
[[ -n "$java_home" && -x "$java_home/bin/java.exe" || -x "$java_home/bin/java" ]] || {
  printf 'JAVA_HOME must point to JDK 21.\n' >&2
  exit 1
}
[[ -f "$keystore" && -f "$password_file" ]] || {
  printf 'Release keystore and password file are required.\n' >&2
  exit 1
}

cmake="${CMAKE:-$android_home/cmake/3.22.1/bin/cmake.exe}"
[[ -x "$cmake" ]] || { printf 'CMake executable not found: %s\n' "$cmake" >&2; exit 1; }
android_jar="$android_home/platforms/android-35/android.jar"
build_tools="${ANDROID_BUILD_TOOLS:-}"
if [[ -z "$build_tools" ]]; then
  build_tools="$(printf '%s\n' "$android_home"/build-tools/*/aapt2.exe | sort -V | tail -1 | xargs -r dirname)"
fi
aapt2="$build_tools/aapt2.exe"
d8="$build_tools/d8.bat"
for file in "$android_jar" "$aapt2" "$d8"; do
  [[ -f "$file" ]] || { printf 'Missing Android build input: %s\n' "$file" >&2; exit 1; }
done

password="$(tr -d '\r\n' < "$password_file")"
keytool="$java_home/bin/keytool.exe"
[[ -x "$keytool" ]] || keytool="$java_home/bin/keytool"
certificate_sha256="$($keytool -list -v -keystore "$keystore" -storepass "$password" -alias manager-release |
  tr -d '\r' | awk -F': ' '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}')"
[[ "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]] || {
  printf 'Could not read release certificate SHA-256.\n' >&2
  exit 1
}

native_build="$repo_root/native/build-android-release"
native_binary="$native_build/libmodloader.so"
template="$repo_root/android/manager/app/src/main/assets/release/modloader-template-10005.apk"
candidate="$repo_root/android/manager/app/build/release-stage/modloader-template-10005.apk"

echo "[1/5] Configure and build official native loader"
MSYS_NO_PATHCONV=1 "$cmake" -S "$repo_root/native" -B "$native_build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$android_ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-35 \
  -DMODLOADER_BACKEND_MODE=1 \
  -DMODLOADER_OFFICIAL_URI_HOOKS=ON \
  -DMODLOADER_OFFICIAL_URI_TEXTURE_HOOK=ON \
  -DMODLOADER_OFFICIAL_TMP_GLYPH_HOOKS=ON \
  -DCMAKE_BUILD_TYPE=Release
MSYS_NO_PATHCONV=1 "$cmake" --build "$native_build"

native_sha256="$(sha256sum "$native_binary" | cut -d' ' -f1)"
"$android_ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe" -hW -lW -dW "$native_binary" |
  tee "$native_build/readelf.txt" >/dev/null
if grep -q 'TEXTREL' "$native_build/readelf.txt" || ! grep -q 'AArch64' "$native_build/readelf.txt" ||
   [[ "$(grep -c 'LOAD' "$native_build/readelf.txt")" -lt 4 ]]; then
  printf 'Native ELF release checks failed.\n' >&2
  exit 1
fi

export JAVA_HOME="$java_home"
echo "[2/5] Build protocol v2 Bootstrap AAR"
(
  cd "$repo_root/android/manager"
  ./gradlew :bootstrap:assembleRelease \
    -PmanagerCertificateSha256="$certificate_sha256" \
    -PmodloaderBinary="$native_binary"
)

echo "[3/5] Build and validate frozen split candidate"
python "$repo_root/android/bootstrap/build_split_template.py" \
  --bootstrap-aar "$repo_root/android/bootstrap/build/outputs/aar/bootstrap-release.aar" \
  --bootstrap-manifest "$repo_root/android/bootstrap/src/main/AndroidManifest.xml" \
  --android-jar "$android_jar" --aapt2 "$aapt2" --d8 "$d8" \
  --output "$candidate" --version-code 10005 --version-name 1.0.5
candidate_sha256="$(sha256sum "$candidate" | cut -d' ' -f1)"
embedded_sha256="$(python "$repo_root/android/bootstrap/build_split_template.py" \
  --verify --output "$candidate" --expected-native-sha256 "$native_sha256" | sed -n 's/.*template=\([^ ]*\) native=.*/\1/p')"
[[ "$candidate_sha256" == "$embedded_sha256" ]] || { printf 'Candidate digest changed during verification.\n' >&2; exit 1; }

printf '%s\n' "[4/5] Publish template and update release pins"
mkdir -p "$(dirname "$template")"
python - "$candidate" "$template" <<'PY'
import os
import sys
from pathlib import Path
os.replace(Path(sys.argv[1]), Path(sys.argv[2]))
PY
python - "$repo_root" "$candidate_sha256" "$native_sha256" <<'PY'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
template, native = sys.argv[2:]
files = [
    root / "android/manager/app/src/main/java/com/sultansgame/modmanager/platform/patch/GameProfileRegistry.kt",
    root / "android/manager/app/src/main/java/com/sultansgame/modmanager/platform/patch/AndroidLoaderSplitArtifactFactory.kt",
    root / "android/manager/app/src/androidTest/java/com/sultansgame/modmanager/platform/patch/DeviceSigningKeyStoreTest.kt",
]
for path in files:
    text = path.read_text()
    text = re.sub(r'(loaderTemplateSha256\s*=\s*")([0-9a-f]{64})(")', rf'\g<1>{template}\g<3>', text)
    text = re.sub(r'(nativeLoaderSha256\s*=\s*")([0-9a-f]{64})(")', rf'\g<1>{native}\g<3>', text)
    text = re.sub(r'("(?:loaderTemplateSha256|TEMPLATE_SHA256)"?\s*[,)]?\s*)', lambda m: m.group(0), text)
    text = text.replace('80dc4e600ea58b272f36cfa81c830d12fc74e63276bed7f88a935f61e07693e3', template)
    text = text.replace('f811b0b7b4b93287b6babe2c337c28c047f504b4bc7225d03b31b140a9adb9b3', template)
    text = text.replace('23ce7678ad665bb18a78e54ed1c65d23583384b5c67bad739cfae1961f2c0734', native)
    text = text.replace('404b7caa0aab2c02fe6e1217616291e4e91bed57eb858e9b15ec135d2f4d29a8', native)
    path.write_text(text)
meta = root / "release/loader-template-10005.json"
text = meta.read_text()
text = re.sub(r'("templateSha256":\s*")[0-9a-f]{64}', rf'\g<1>{template}', text)
text = re.sub(r'("nativeSha256":\s*")[0-9a-f]{64}', rf'\g<1>{native}', text)
meta.write_text(text)
PY

echo "[5/5] Assemble signed Manager release"
(
  cd "$repo_root/android/manager"
  ./gradlew :app:assembleRelease \
    -PmanagerCertificateSha256="$certificate_sha256" \
    -PmodloaderBinary="$native_binary"
)
echo "Release APK: $repo_root/android/manager/app/build/outputs/apk/release/app-release.apk"
