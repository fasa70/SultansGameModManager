#!/usr/bin/env bash
# Check build prerequisites for Sultan's Game Mod Manager
# Usage: bash scripts/check-env.sh
set -euo pipefail

RED='\033[0;31m' GREEN='\033[0;32m' YELLOW='\033[1;33m' NC='\033[0m'
pass() { echo -e "${GREEN}[OK]${NC}   $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[MISS]${NC} $1"; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
echo "=== Sultan's Game Mod Manager — Build Environment Check ==="
echo ""

# --- JDK 21 ---
JAVA=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java.exe" ]; then
    JAVA="$JAVA_HOME/bin/java.exe"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java &>/dev/null; then
    JAVA=$(command -v java)
fi
if [ -n "$JAVA" ]; then
    VER=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\)\..*/\1/p; s/.*version "\([0-9][0-9]*\)".*/\1/p' | head -1)
    if [ "$VER" = "21" ]; then
        pass "JDK 21 — $JAVA"
    else
        warn "JDK found but version $VER (need 21) — $JAVA"
        JAVA=""
    fi
else
    fail "JDK 21 not found — install from https://adoptium.net"
fi

# --- Android SDK ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f android/manager/local.properties ]; then
    SDK=$(grep 'sdk.dir=' android/manager/local.properties 2>/dev/null | cut -d= -f2 || true)
fi
if [ -n "$SDK" ] && [ -d "$SDK" ]; then
    pass "Android SDK — $SDK"
    [ -d "$SDK/platforms/android-35" ] && pass "  Android API 35 platform" || warn "  Android API 35 platform not installed — use sdkmanager"
else
    fail "Android SDK not found — set ANDROID_HOME or create android/manager/local.properties"
fi

# --- NDK ---
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [ -z "$NDK" ] && [ -n "$SDK" ]; then
    while IFS= read -r candidate; do
        if [ -d "$candidate" ]; then NDK="$candidate"; break; fi
    done < <(printf '%s\n' "$SDK"/ndk/27.* | sort -V -r)
fi
if [ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
    pass "Android NDK 27 — $NDK"
else
    NDK=""
    fail "Android NDK 27 not found — install via sdkmanager 'ndk;27.0.12077973'"
fi

# --- CMake and Ninja ---
CMAKE="${CMAKE:-}"
if [ -z "$CMAKE" ] && [ -n "$SDK" ]; then
    while IFS= read -r candidate; do
        if [ -x "$candidate" ] && "$candidate" --version &>/dev/null; then CMAKE="$candidate"; break; fi
    done < <(printf '%s\n' "$SDK"/cmake/*/bin/cmake.exe "$SDK"/cmake/*/bin/cmake)
fi
[ -z "$CMAKE" ] && CMAKE=$(command -v cmake 2>/dev/null || true)
if [ -n "$CMAKE" ] && [ -x "$CMAKE" ] && "$CMAKE" --version &>/dev/null; then
    pass "CMake — $("$CMAKE" --version | head -1)"
else
    CMAKE=""
    fail "CMake 3.22+ not found — install via sdkmanager 'cmake;3.22.1'"
fi

NINJA="${NINJA:-}"
[ -z "$NINJA" ] && NINJA=$(command -v ninja 2>/dev/null || true)
if [ -z "$NINJA" ] && [ -n "$SDK" ]; then
    while IFS= read -r candidate; do
        if [ -f "$candidate" ] && "$candidate" --version &>/dev/null; then NINJA="$candidate"; break; fi
    done < <(printf '%s\n' "$SDK"/cmake/*/bin/ninja.exe "$SDK"/cmake/*/bin/ninja)
fi
if [ -n "$NINJA" ] && [ -f "$NINJA" ] && "$NINJA" --version &>/dev/null; then
    pass "Ninja — $("$NINJA" --version)"
else
    NINJA=""
    fail "Ninja not found — install Android SDK CMake or set NINJA"
fi

# --- Gradle ---
if [ -f android/manager/gradlew ]; then
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
[ -z "$NINJA" ] && echo "  - Install Ninja via Android SDK CMake or set NINJA" && MISSING=1

if [ "$MISSING" = "0" ]; then
    echo -e "${GREEN}All prerequisites satisfied.${NC}"
else
    echo -e "${RED}Some prerequisites are missing.${NC}"
    exit 1
fi
