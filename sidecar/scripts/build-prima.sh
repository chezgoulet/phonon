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

# ── Cross-compile libzmq for Android ───────────────────────────
# prima.cpp links against libzmq, which isn't available in the NDK
# sysroot. Build a static version targeting the same ABI.
ZMQ_VERSION="4.3.5"
ZMQ_BUILD_DIR="$BUILD_DIR/zmq-build"
ZMQ_INSTALL_DIR="$BUILD_DIR/zmq-install"

if [ ! -d "$ZMQ_INSTALL_DIR/lib" ]; then
    echo "==> Cross-compiling libzmq $ZMQ_VERSION for Android $ABI..."
    ZMQ_SRC_DIR="$ZMQ_BUILD_DIR/zeromq-${ZMQ_VERSION}"
    if [ ! -d "$ZMQ_SRC_DIR" ]; then
        mkdir -p "$ZMQ_BUILD_DIR"
        curl -sL "https://github.com/zeromq/libzmq/releases/download/v${ZMQ_VERSION}/zeromq-${ZMQ_VERSION}.tar.gz" \
          | tar xz -C "$ZMQ_BUILD_DIR"
    fi
    mkdir -p "$ZMQ_BUILD_DIR/build"
    "$CMAKE" -S "$ZMQ_SRC_DIR" -B "$ZMQ_BUILD_DIR/build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED=OFF \
        -DBUILD_STATIC=ON \
        -DWITH_TLS=OFF \
        -DWITH_VMCI=OFF \
        -DWITH_RADIX_TREE=OFF \
        -DWITH_LIBBSD=OFF \
        -DWITH_NORM=OFF \
        -DCMAKE_INSTALL_PREFIX="$ZMQ_INSTALL_DIR"
    "$CMAKE" --build "$ZMQ_BUILD_DIR/build" --parallel
    "$CMAKE" --install "$ZMQ_BUILD_DIR/build" --prefix "$ZMQ_INSTALL_DIR"
else
    echo "==> libzmq already built for Android, skipping..."
fi

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
    -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
    -DCMAKE_PREFIX_PATH="$ZMQ_INSTALL_DIR" \
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
    -DCMAKE_LIBRARY_PATH="$ZMQ_INSTALL_DIR/lib"

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
