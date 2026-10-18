# Native CPU backends on Android (GGML_BACKEND_DL)

The app ships one APK that must run on every arm64 phone the project targets:
Cortex-A73/A53-era chips with no dot-product instructions, and i8mm phones that
want faster int8 matmul. A single `-march=armv8.2-a+dotprod+fp16` build cannot do
both — it SIGILLs on the older phone and leaves the newer one idle.

`app/build-native.sh` therefore builds llama.cpp with:

```
-DGGML_CPU_ALL_VARIANTS=ON   # one CPU backend per ARMv8 ISA level
-DGGML_BACKEND_DL=ON         # each backend is its own .so, loaded at runtime
-DGGML_CPU_REPACK=ON         # Q4_K repack still engages when dotprod is present
-DGGML_CPU_KLEIDIAI=ON       # ARM's KleidiAI micro-kernels (fetched at build time)
```

On Android the variant backends are named `libggml-cpu-android_armv8.0_1.so`
through `libggml-cpu-android_armv9.2_2.so`. `app/sync-native.sh` copies every
`lib*.so` the build produces, so all of them land in `jniLibs/arm64-v8a` and the
APK. `sync-native.sh` fails loudly if no `libggml-cpu-*.so` is present, because a
`GGML_BACKEND_DL` build with no CPU backend would start and then fail every load.

## How llama.cpp finds its dynamic backends

`ggml_backend_load_all()` (called from `llama.cpp`'s `common/arg.cpp` and
`src/llama.cpp` at init) walks a search path for files matching
`libggml-cpu-*.so`, `dlopen`s each candidate, asks it for a CPU-feature score, and
keeps the best one. The search path is, in order:

1. the compile-time `GGML_BACKEND_DIR` (not set here);
2. **the directory of the running executable**, via `readlink("/proc/self/exe")`
   on Linux/Android (`ggml/src/ggml-backend-reg.cpp`, `get_executable_path()`);
3. the current working directory.

## Does that work from `nativeLibraryDir`?

Yes, and it is why the packaging is set up the way it is:

- `android:extractNativeLibs="true"` and `packaging.jniLibs.useLegacyPackaging =
  true` keep the libraries as **real files** in
  `/data/app/<pkg>/lib/arm64`, not compressed inside the APK.
- The app execs `nativeLibraryDir/libllama-server.so`. `/proc/self/exe` resolves
  to that file, so `get_executable_path()` returns `nativeLibraryDir`.
- The variant `libggml-cpu-*.so` files sit in the same directory, so step 2 finds
  them and `dlopen`s the best one for the CPU.

If the libraries were left compressed in the APK (`useLegacyPackaging = false`),
`nativeLibraryDir` would be a path *inside the zip* and neither the exec nor the
directory scan would work — hence the explicit legacy-packaging setting.

## GGML_BACKEND_PATH

`GGML_BACKEND_PATH` is **not** set at launch. ggml treats it as the path to a
single out-of-tree backend *file*
(`ggml_backend_load(std::getenv("GGML_BACKEND_PATH"))`), not a directory, so
pointing it at `nativeLibraryDir` would try to `dlopen` a directory and fail. The
in-tree variants are found by the executable-directory scan above; no environment
variable is needed. If a future build ever fails to find its backends, the fix is
to check the extraction/exec setup, not to set this variable.

## Evidence without a phone

The per-ISA choice and the directory scan are the same code on Linux and Android.
`tools/native/backend_discovery_probe.c` (with `tools/native/run_probe.sh`) builds
ggml on the host with the same `ALL_VARIANTS` + `BACKEND_DL` flags and calls
`ggml_backend_load_all()` from a binary placed beside the variant libraries:

```
load_backend: loaded CPU backend from /home/c/ggml-host-build/bin/libggml-cpu-haswell.so
ggml_backend_reg_count = 1
  backend[0] = CPU
```

That is the executable-directory scan working: 14 variant `.so` files present, the
one matching the host CPU loaded. On-device, the same scan runs over the Android
`android_*` variants and picks the highest ISA level the phone supports.

## What still needs a device

- Confirming the child logs `load_backend: loaded CPU backend from
  .../lib/arm64/.../libggml-cpu-android_<level>.so` and which level it chose.
- Confirming a phone without dotprod starts (the ARMv8.0 backend) instead of
  SIGILLing.

## Optional OpenCL prefill backend (opt-in, off by default)

`app/build-native.sh` can also build llama.cpp's OpenCL backend:

```
PHONON_OPENCL=1 app/build-native.sh
```

That adds `-DGGML_OPENCL=ON` and, because the backend is built with
`GGML_BACKEND_DL`, produces `libggml-opencl.so` next to the CPU variants.
`sync-native.sh` copies every `lib*.so`, so it lands in `jniLibs/arm64-v8a` and
the APK. The default build is unchanged: without `PHONON_OPENCL=1` the flag is
`-DGGML_OPENCL=OFF`, no OpenCL library is produced, and the shipped APK behaves
exactly as before.

**It is expected to help prefill only on Adreno GPUs, and it has not been tested
on one.** Do not enable it by default. When the library is present the Settings
screen says so ("GPU prefill: included — untested on this GPU"); the app only
reports presence and never changes how the child is launched, so a build that
carries the library still runs the same command line.

What a device run would need to establish, and does not yet:

- that the OpenCL backend is picked up and used at all on an Adreno phone;
- that prefill throughput actually improves, without a decode regression;
- that a phone whose GPU lacks a usable OpenCL driver falls back to the CPU
  backend rather than failing to load.

