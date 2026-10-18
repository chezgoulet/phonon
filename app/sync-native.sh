#!/usr/bin/env bash
# Copy an arm64 llama.cpp build into the app's jniLibs.
#
#   app/sync-native.sh [BUILD_DIR]
#
# BUILD_DIR must contain llama-server and the libggml*/libllama*.so files.
# Defaults to the measured Pixel 7 build on this host: llama.cpp 797de63
# built with GGML_CPU_REPACK=ON so Q4_K weights are repacked for ARM.
set -euo pipefail

BUILD_DIR="${1:-/home/c/llama.cpp/build-android-arm64-repack/bin}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="$HERE/app/src/main/jniLibs/arm64-v8a"

if [[ ! -f "$BUILD_DIR/llama-server" ]]; then
  echo "error: $BUILD_DIR/llama-server not found" >&2
  exit 1
fi

mkdir -p "$DEST"
rm -f "$DEST"/*.so

# Android only extracts lib*.so. The executable keeps its ELF type; only the
# file name is changed.
install -m 0755 "$BUILD_DIR/llama-server" "$DEST/libllama-server.so"

shopt -s nullglob
libs=("$BUILD_DIR"/lib*.so)
if [[ ${#libs[@]} -eq 0 ]]; then
  echo "error: no shared libraries in $BUILD_DIR" >&2
  exit 1
fi
for lib in "${libs[@]}"; do
  install -m 0755 "$lib" "$DEST/"
done

echo "synced ${#libs[@]} shared libraries + libllama-server.so into $DEST"
du -sh "$DEST"
ls -l "$DEST"
