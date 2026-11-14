#!/bin/sh
#
# Builds the whole runner in one pass:
#   1. locates the Android SDK/NDK and extracts the FMOD Studio SDK
#   2. fetches godot-cpp 4.1 and patches its Android host detection for Termux
#   3. builds every GDExtension for the requested ABIs (fmod extension compiles
#      the vendored natives/fmod-for-godot sources with UTAP's patch applied)
#   4. assembles the debug APK (engine is the official Godot 4.1.4 template
#      pulled from Maven; a locally built engine AAR is never used — its font
#      loading is broken)
#
# Usage:
#   ./build.sh                          # arm64-v8a, debug APK
#   ABIS="arm64-v8a x86_64" ./build.sh  # add x86_64
#   ./build.sh :app:assembleRelease
#
set -eu

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ABIS="${ABIS:-arm64-v8a}"
GODOT_CPP_BRANCH="${GODOT_CPP_BRANCH:-4.1}"
GODOT_CPP_DIR="$ROOT/natives/godot-cpp"
NDK_VERSION="23.2.8568313"
FMOD_DIR="$ROOT/runtime/fmodstudioapi20214android/api"
if [ -z "${JOBS:-}" ]; then
    cpus="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
    # Clang can use over 1 GiB per translation unit; on phones the default
    # core count swaps (or gets OOM-killed) before the libraries link.
    mem_kb="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
    jobs=$(( mem_kb / 1024 / 1024 / 2 ))
    [ "$jobs" -lt 1 ] && jobs=1
    [ "$jobs" -gt "$cpus" ] && jobs="$cpus"
    JOBS="$jobs"
fi
TARGET="${TARGET:-template_release}"
GRADLE_TASK="${1:-:app:assembleDebug}"

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- Android SDK / NDK ------------------------------------------------------

if [ -z "${ANDROID_HOME:-}" ]; then
    for candidate in "$HOME/android-sdk" "$HOME/Android/Sdk" "$HOME/storage/shared/Android/Sdk"; do
        if [ -d "$candidate" ]; then
            ANDROID_HOME="$candidate"
            break
        fi
    done
fi
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || die "Android SDK not found; set ANDROID_HOME."

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/$NDK_VERSION}"
[ -d "$ANDROID_NDK_ROOT" ] || die "NDK $NDK_VERSION not found under $ANDROID_HOME/ndk."

# --- FMOD Studio SDK -------------------------------------------------------

if [ ! -d "$FMOD_DIR/core/inc" ] || [ ! -d "$FMOD_DIR/studio/inc" ]; then
    tarball="${FMOD_TARBALL:-}"
    if [ -z "$tarball" ]; then
        for candidate in \
            "$HOME/storage/downloads/fmodstudioapi20214android.tar.gz" \
            "$HOME/Downloads/fmodstudioapi20214android.tar.gz" \
            "/storage/emulated/0/Download/fmodstudioapi20214android.tar.gz"; do
            [ -f "$candidate" ] && { tarball="$candidate"; break; }
        done
    fi
    [ -n "$tarball" ] && [ -f "$tarball" ] || die \
        "FMOD SDK not found. Put fmodstudioapi20214android.tar.gz in Downloads or set FMOD_TARBALL."
    say "Extracting FMOD SDK from $tarball"
    mkdir -p "$ROOT/runtime"
    tar -xzf "$tarball" -C "$ROOT/runtime"
fi

for abi in $ABIS; do
    for lib in "$FMOD_DIR/core/lib/$abi/libfmod.so" "$FMOD_DIR/studio/lib/$abi/libfmodstudio.so"; do
        [ -f "$lib" ] || die "FMOD library missing: $lib"
        mkdir -p "$ROOT/natives/android/$abi"
        cp -f "$lib" "$ROOT/natives/android/$abi/"
    done
done

# --- Engine AAR guard -------------------------------------------------------
# The runner must ship the official 4.1.4 template (fonts broke on the custom
# engine). app/build.gradle.kts always pulls it from Maven; fail early here if a
# stale local AAR is still around so nobody quietly rebuilds the old behaviour.
if ls "$ROOT"/app/libs/godot-lib*.aar >/dev/null 2>&1; then
    die "Stale engine AAR in app/libs — the build now uses the official 4.1.4 template from Maven. Delete app/libs/godot-lib*.aar."
fi

# --- godot-cpp -------------------------------------------------------------

if [ ! -f "$GODOT_CPP_DIR/SConstruct" ]; then
    say "Cloning godot-cpp $GODOT_CPP_BRANCH"
    git clone --depth 1 --branch "$GODOT_CPP_BRANCH" \
        https://github.com/godotengine/godot-cpp.git "$GODOT_CPP_DIR"
fi

# godot-cpp 4.1 assumes an x86_64 Linux host. Termux reports sys.platform
# "android" and an aarch64 machine, so teach it to pick the matching NDK host.
python3 - "$GODOT_CPP_DIR/tools/android.py" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text()
if "Termux reports sys.platform" in text:
    raise SystemExit

old = '''    elif sys.platform.startswith("linux"):
        toolchain += "linux-x86_64"
    elif sys.platform == "darwin":'''
new = '''    elif sys.platform.startswith("linux") or sys.platform == "android":
        import platform as pltfm

        # Termux reports sys.platform == "android"; pick the NDK host build that
        # matches the machine instead of assuming an x86_64 build host.
        host = "linux-aarch64" if pltfm.machine() in ("aarch64", "arm64") else "linux-x86_64"
        toolchain += host
    elif sys.platform == "darwin":'''
if old not in text:
    raise SystemExit("error: unexpected godot-cpp android.py layout; cannot patch host detection")
path.write_text(text.replace(old, new))
PY

# --- Native extensions -----------------------------------------------------

arch_for_abi() {
    case "$1" in
        arm64-v8a) echo arm64 ;;
        armeabi-v7a) echo arm32 ;;
        x86_64) echo x86_64 ;;
        x86) echo x86_32 ;;
        *) die "unknown ABI: $1" ;;
    esac
}

for abi in $ABIS; do
    arch="$(arch_for_abi "$abi")"
    say "Building GDExtensions for $abi ($arch)"
    scons -C "$ROOT/natives" \
        platform=android target="$TARGET" arch="$arch" -j"$JOBS"
done

# --- APK -------------------------------------------------------------------

if [ ! -f "$ROOT/local.properties" ]; then
    echo "sdk.dir=$ANDROID_HOME" > "$ROOT/local.properties"
fi

say "Assembling $GRADLE_TASK"

# AGP bundles an x86_64 AAPT2. On an arm64 host (Termux) that binary cannot
# run, so point AGP at a native AAPT2 instead of the Maven copy.
aapt2_override="${AAPT2_OVERRIDE:-}"
if [ -z "$aapt2_override" ]; then
    case "$(uname -m)" in
        aarch64|arm64)
            for candidate in "$(command -v aapt2 2>/dev/null || true)" \
                "$ANDROID_HOME/build-tools/36.0.0/aapt2"; do
                if [ -n "$candidate" ] && [ -x "$candidate" ]; then
                    aapt2_override="$candidate"
                    break
                fi
            done
            ;;
    esac
fi

if [ -n "$aapt2_override" ]; then
    say "Using native AAPT2: $aapt2_override"
    "$ROOT/gradlew" -p "$ROOT" \
        -Pandroid.aapt2FromMavenOverride="$aapt2_override" "$GRADLE_TASK"
else
    "$ROOT/gradlew" -p "$ROOT" "$GRADLE_TASK"
fi
