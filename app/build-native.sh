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

# -march is not optional: a cross-compile otherwise targets baseline ARMv8, so
# the dot-product kernels are absent and the repack selector (which requires
# them) never engages. Every phone this project targets has dotprod. Measured on
# the Pixel 7: decode 9.2 -> 13.7 tok/s, 2,415-token prefill 15 -> 58 tok/s from
# this flag, 82 with -tb 4 passed to llama-server.
cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_CPU_REPACK=ON \
  -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod+fp16" \
  -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod+fp16" \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON

cmake --build "$BUILD" -j"$(nproc)" --target llama-server

"$HERE/sync-native.sh" "$BUILD/bin"
