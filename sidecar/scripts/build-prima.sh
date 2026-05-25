#!/usr/bin/env bash
# Build prima.cpp (llama.cpp fork) for Android arm64-v8a via NDK.
#
# Prerequisites:
#   ANDROID_NDK  — path to NDK (e.g. $HOME/Android/Sdk/ndk/27.0.12077973)
#   CMAKE        — cmake 3.14+ (defaults to cmake on PATH)
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/libllama.so     — shared library
#   app/src/main/assets/prima/llama-server          — inference binary
#
# Run from the repo root (phonon/):
#   bash sidecar/scripts/build-prima.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PRIMA_DIR="$REPO_ROOT/extern/prima.cpp"

# ── Configuration ──────────────────────────────────────────────
NDK="${ANDROID_NDK:?Set ANDROID_NDK to the NDK root}"
CMAKE="${CMAKE:-cmake}"
ABI="arm64-v8a"
ANDROID_PLATFORM="android-29"
BUILD_DIR="$PRIMA_DIR/build-android"
INSTALL_DIR="$BUILD_DIR/install"

# Output locations in the sidecar project
JNILIBS_DIR="$REPO_ROOT/sidecar/app/src/main/jniLibs/$ABI"
ASSETS_DIR="$REPO_ROOT/sidecar/app/src/main/assets/prima"

# ── Clean ──────────────────────────────────────────────────────
echo "==> Cleaning previous build..."
rm -rf "$BUILD_DIR"
mkdir -p "$JNILIBS_DIR" "$ASSETS_DIR"

# ── Configure ──────────────────────────────────────────────────
echo "==> Configuring CMake for $ABI (API $ANDROID_PLATFORM)..."
"$CMAKE" -S "$PRIMA_DIR" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_CURL=OFF \
    -DLLAMA_FATAL_WARNINGS=OFF \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR"

# ── Build ──────────────────────────────────────────────────────
echo "==> Building..."
"$CMAKE" --build "$BUILD_DIR" --target llama --parallel
"$CMAKE" --build "$BUILD_DIR" --target llama-server --parallel

# ── Install ────────────────────────────────────────────────────
echo "==> Installing..."
"$CMAKE" --install "$BUILD_DIR" --prefix "$INSTALL_DIR"

# ── Copy to sidecar project ────────────────────────────────────
echo "==> Copying outputs..."
cp -v "$BUILD_DIR/src/libllama.so" "$JNILIBS_DIR/libllama.so"
cp -v "$BUILD_DIR/bin/llama-server" "$ASSETS_DIR/llama-server"

echo "==> Done. Outputs:"
echo "    $JNILIBS_DIR/libllama.so"
echo "    $ASSETS_DIR/llama-server"
