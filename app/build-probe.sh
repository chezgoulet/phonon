#!/usr/bin/env bash
# Build the exec probe as libphonon_probe.so for the emulator and real phones.
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-/home/c/Android/Sdk/ndk/30.0.16138531}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/app/src/main/jni/probe.c"

for target in x86_64:x86_64 arm64-v8a:aarch64; do
  abi="${target%%:*}"
  arch="${target##*:}"
  out="$HERE/app/src/main/jniLibs/$abi/libphonon_probe.so"
  mkdir -p "$(dirname "$out")"
  "$TC/${arch}-linux-android31-clang" -O2 -fPIE -pie -o "$out" "$SRC"
  chmod 755 "$out"
  echo "built $out"
done
