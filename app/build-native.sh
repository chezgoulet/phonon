#!/usr/bin/env bash
# Build llama.cpp for Android arm64 from the pinned submodule and place the
# artifacts in the app's jniLibs.
#
#   app/build-native.sh
#
# Requires ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) pointing at the pinned NDK.
# Set PHONON_NATIVE_BUILD to keep the CMake build tree somewhere specific.
#
# The engine revision is fixed by the submodule commit; do not build a floating
# master. GGML_CPU_REPACK=ON is what makes Q4_K models run at the measured rate
# (see docs/performance.md); without it decode is 50-60% slower on the same
# weights.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRC="$ROOT/third_party/llama.cpp"
BUILD="${PHONON_NATIVE_BUILD:-$ROOT/.native-build}"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "error: set ANDROID_NDK_HOME to an Android NDK (30.0.16138531 for this project)" >&2
  exit 1
fi

if [[ ! -f "$SRC/CMakeLists.txt" ]]; then
  echo "error: submodule missing; run: git submodule update --init --recursive" >&2
  exit 1
fi

echo "llama.cpp at $(git -C "$SRC" rev-parse --short HEAD) ($(git -C "$SRC" log -1 --format='%s'))"

cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_CPU_REPACK=ON \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON

cmake --build "$BUILD" -j"$(nproc)" --target llama-server

"$HERE/sync-native.sh" "$BUILD/bin"
