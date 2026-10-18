#!/usr/bin/env bash
# Copy an arm64 llama.cpp build into the app's jniLibs.
#
#   app/sync-native.sh [BUILD_DIR]
#
# BUILD_DIR must contain llama-server and every libggml*/libllama*.so the build
# produced. With GGML_BACKEND_DL that includes the per-ISA CPU backends
# (libggml-cpu-android_armv8.0_1.so and friends): llama.cpp dlopens the best one
# for the running CPU at load time, so every variant must ship in the APK.
#
# Defaults to the measured Pixel 7 build on this host: llama.cpp 797de63
# built with GGML_CPU_REPACK=ON so Q4_K weights are repacked for ARM.
set -euo pipefail

BUILD_DIR="${1:-$HOME/llama.cpp/build-android-arm64-repack/bin}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="$HERE/app/src/main/jniLibs/arm64-v8a"

if [[ ! -f "$BUILD_DIR/llama-server" ]]; then
  echo "error: $BUILD_DIR/llama-server not found" >&2
  exit 1
fi

shopt -s nullglob
libs=("$BUILD_DIR"/lib*.so)
if [[ ${#libs[@]} -eq 0 ]]; then
  echo "error: no shared libraries in $BUILD_DIR" >&2
  exit 1
fi

# With GGML_BACKEND_DL at least one CPU backend must be present or llama.cpp
# will start with no backend and fail every model load. Fail here rather than
# ship an APK that cannot infer.
cpu_variants=("$BUILD_DIR"/libggml-cpu-*.so)
if [[ ${#cpu_variants[@]} -eq 0 ]]; then
  echo "error: no CPU backend variants in $BUILD_DIR (libggml-cpu-*.so);" >&2
  echo "       rebuild with app/build-native.sh (GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL)" >&2
  exit 1
fi

mkdir -p "$DEST"
# Clear stale libraries so a removed variant cannot be served from a leftover
# file, but keep the directory itself (the models live elsewhere).
rm -f "$DEST"/*.so

# Android only extracts lib*.so. The executable keeps its ELF type; only the
# file name is changed.
install -m 0755 "$BUILD_DIR/llama-server" "$DEST/libllama-server.so"

for lib in "${libs[@]}"; do
  install -m 0755 "$lib" "$DEST/"
done

echo "synced ${#libs[@]} shared libraries (${#cpu_variants[@]} CPU variants) + libllama-server.so into $DEST"
du -sh "$DEST"
ls -l "$DEST"
