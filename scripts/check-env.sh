#!/usr/bin/env bash
# Check build prerequisites for Sultan's Game Mod Manager
# Usage: bash scripts/check-env.sh
set -euo pipefail

RED='\033[0;31m' GREEN='\033[0;32m' YELLOW='\033[1;33m' NC='\033[0m'
pass() { echo -e "${GREEN}[OK]${NC}   $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[MISS]${NC} $1"; }

echo "=== Sultan's Game Mod Manager — Build Environment Check ==="
echo ""

# --- JDK 21 ---
JAVA=""
if command -v java &>/dev/null; then
    VER=$(java -version 2>&1 | head -1 | grep -oP '"\K[0-9]+')
    if [ "$VER" = "21" ]; then
        JAVA=$(command -v java)
        pass "JDK 21 — $JAVA"
    else
        warn "JDK found but version $VER (need 21) — $(command -v java)"
    fi
else
    fail "JDK 21 not found — install from https://adoptium.net"
fi
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$JAVA" 2>/dev/null || echo "$JAVA")")")}"

# --- Android SDK ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
    SDK=$(grep 'sdk.dir=' local.properties 2>/dev/null | cut -d= -f2 || true)
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
    pass "Android SDK — $SDK"
    if [ -d "$SDK/platforms/android-35" ]; then
        pass "  Android API 35 platform"
    else
        warn "  Android API 35 platform not installed — use sdkmanager"
    fi
else
    fail "Android SDK not found — set ANDROID_HOME or create local.properties"
fi

# --- NDK ---
NDK=""
NDK_CANDIDATES=(
    "$SDK/ndk/27.0.12077973"
    "$SDK/ndk/27.2.12479018"
    "$SDK/ndk/27.1.12297006"
    "$SDK/ndk/27.0.11902837"
)
for candidate in "${NDK_CANDIDATES[@]}"; do
    [ -d "$candidate" ] && NDK="$candidate" && break
done
if [ -z "$NDK" ] && command -v ls &>/dev/null; then
    NDK=$(ls -d "$SDK/ndk/27."* 2>/dev/null | sort -V | tail -1 || true)
fi
if [ -n "$NDK" ]; then
    pass "Android NDK 27 — $NDK"
else
    fail "Android NDK 27 not found — install via sdkmanager 'ndk;27.0.12077973'"
fi

# --- CMake ---
CMAKE=""
for candidate in \
    "$SDK/cmake/3.22.1/bin/cmake" \
    "$SDK/cmake/3.31.5/bin/cmake" \
    "$(command -v cmake 2>/dev/null || true)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ] && "$candidate" --version &>/dev/null; then
        CMAKE="$candidate"
        break
    fi
done
if [ -n "$CMAKE" ]; then
    pass "CMake — $($CMAKE --version | head -1)"
else
    fail "CMake 3.22+ not found — install via sdkmanager 'cmake;3.22.1'"
fi

# --- Gradle ---
if [ -x android/manager/gradlew ]; then
    pass "Gradle wrapper — android/manager/gradlew"
else
    fail "Gradle wrapper missing — run 'gradle wrapper' in android/manager/"
fi

# --- Git Submodules ---
if [ -f native/third_party/dobby/CMakeLists.txt ]; then
    pass "Dobby submodule — present"
else
    warn "Dobby submodule not checked out — run: git submodule update --init --recursive"
fi

echo ""
echo "=== Summary ==="
MISSING=0
[ -z "$JAVA" ] && echo "  - Install JDK 21: https://adoptium.net" && MISSING=1
[ -z "$SDK" ] && echo "  - Set ANDROID_HOME or create android/manager/local.properties with sdk.dir" && MISSING=1
[ -z "$NDK" ] && echo "  - Install NDK 27: sdkmanager 'ndk;27.0.12077973'" && MISSING=1
[ -z "$CMAKE" ] && echo "  - Install CMake 3.22+: sdkmanager 'cmake;3.22.1'" && MISSING=1

if [ "$MISSING" = "0" ]; then
    echo -e "${GREEN}All prerequisites satisfied.${NC}"
    echo ""
    echo "Quick start:"
    echo "  cd android/manager && ./gradlew :core:model:test"
    echo "  cd android/manager && ./gradlew :app:assembleDebug"
    echo ""
    echo "Native build:"
    echo "  cmake -B native/build-android -G Ninja \\"
    echo "    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \\"
    echo "    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21"
    echo "  cmake --build native/build-android"
else
    echo -e "${RED}Some prerequisites are missing.${NC}"
    exit 1
fi
