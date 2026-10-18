#!/usr/bin/env bash
# Host harness: build ggml/llama.cpp with the SAME backend flags as the Android
# build and prove that ggml_backend_load_all() discovers the per-ISA CPU
# variants by looking beside the running executable.
set -euo pipefail
ROOT=/home/c/phonon
SRC=$ROOT/third_party/llama.cpp
B=/home/c/ggml-host-build
cmake -S "$SRC" -B "$B" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_CPU_REPACK=ON \
  -DGGML_CPU_ALL_VARIANTS=ON \
  -DGGML_BACKEND_DL=ON \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF > "$B.configure.log.out" 2>&1 || { tail -20 "$B.configure.log.out"; exit 1; }
cmake --build "$B" -j"$(nproc)" --target ggml ggml-base > "$B.build.log.out" 2>&1 || { tail -30 "$B.build.log.out"; exit 1; }
echo "== variant .so built =="
ls "$B/bin"/libggml-cpu-*.so | xargs -n1 basename
gcc -O0 -o "$B/bin/backend_probe" "$ROOT/tools/native/backend_discovery_probe.c" \
  -I "$SRC/ggml/include" -L "$B/bin" -lggml -lggml-base -Wl,-rpath,"$B/bin"
echo "== running probe from bin/ (exe dir == backend dir) =="
( cd "$B/bin" && ./backend_probe )
