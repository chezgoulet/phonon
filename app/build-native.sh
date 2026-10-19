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
#
# Device-agnostic kernels: a fixed -march=armv8.2-a+dotprod+fp16 build SIGILLs
# on the Cortex-A73/A53 era (no dotprod) and gives an i8mm phone nothing extra.
# GGML_CPU_ALL_VARIANTS compiles one CPU backend per ISA level and GGML_BACKEND_DL
# makes llama.cpp pick the best one the running CPU actually supports at load
# time. Each of these still runs the repack path when its dotprod kernels are
# present, so REPACK stays on.
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

# Optional OpenCL prefill backend. OFF by default: the shipped APK must not
# change behaviour, and the backend is only *expected* to help prefill on
# Adreno GPUs — it has not been tested on one. Set PHONON_OPENCL=1 to build and
# sync libggml-opencl.so alongside the CPU backends; see docs/native-backends.md.
OPENCL="${PHONON_OPENCL:-0}"
OPENCL_FLAG="-DGGML_OPENCL=OFF"
if [[ "$OPENCL" == "1" ]]; then
  OPENCL_FLAG="-DGGML_OPENCL=ON"
  echo "PHONON_OPENCL=1: building the OpenCL backend (libggml-opencl.so)."
  echo "  Expected to help prefill only on Adreno; untested on any GPU."
fi

# -DGGML_CPU_ARM_ARCH and -march are deliberately absent: they are mutually
# exclusive with ALL_VARIANTS, and the whole point is that no single ISA is
# baked in. The per-variant -march picks its own target.
# Reproducibility: the NDK builds with -g, so the absolute source and build
# paths land in the debug info and the .so files differ between two checkouts.
# Map them to stable prefixes; verified by building twice in different paths and
# comparing the APK.
PREFIX_MAP="-ffile-prefix-map=$BUILD=/build -ffile-prefix-map=$SRC=/src"
cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_CPU_REPACK=ON \
  -DCMAKE_C_FLAGS="$PREFIX_MAP" \
  -DCMAKE_CXX_FLAGS="$PREFIX_MAP -Wl,--build-id=none" \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none" \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,--build-id=none" \
  -DGGML_CPU_ALL_VARIANTS=ON \
  -DGGML_BACKEND_DL=ON \
  -DGGML_CPU_KLEIDIAI="${PHONON_NATIVE_KLEIDIAI:-ON}" \
  "$OPENCL_FLAG" \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON

cmake --build "$BUILD" -j"$(nproc)" --target llama-server

"$HERE/sync-native.sh" "$BUILD/bin"
