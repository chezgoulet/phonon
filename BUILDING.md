# Building Phonon

This document describes how the Android app is built from source, with enough
detail for a contributor or an F-Droid buildserver to reproduce an APK.

## Repository layout (relevant parts)

```
app/                                   Gradle project root (settings.gradle.kts)
  build.gradle.kts                     plugin versions (AGP 8.7.3, Kotlin 1.9.24)
  gradle.properties
  app/
    build.gradle.kts                    applicationId, ndkVersion, versionCode/Name
    src/main/AndroidManifest.xml
    src/main/java/org/phononai/app/...
    src/main/cpp/                       CMake project for the fadvise shim
      CMakeLists.txt
      fadvise.c
    src/main/jniLibs/arm64-v8a/         .gitignore'd prebuilt .so (LOCAL ONLY)
```

## Application facts

| Field          | Value                                            |
|----------------|--------------------------------------------------|
| applicationId  | `org.phononai.app`                               |
| namespace      | `org.phononai.app`                               |
| compileSdk     | 35                                               |
| targetSdk      | 35                                               |
| minSdk         | 31 (Android 12)                                  |
| ABI            | `arm64-v8a` only                                 |
| Java/Kotlin    | 17                                               |
| AGP / Kotlin   | 8.7.3 / 1.9.24                                   |
| versionCode    | 1                                                |
| versionName    | 0.1.0                                            |

## Toolchain

* **JDK 17** (source/target 17). F-Droid's buildserver provides a FOSS JDK; do
  not require Oracle JDK.
* **Android SDK**, platform 35 and build-tools matching AGP 8.7.3.
* **Android NDK `30.0.16138531`** as pinned in `app/app/build.gradle.kts`
  (`ndkVersion`).

  This is the **r30-rc1** revision and it is pinned deliberately: every number in
  `docs/performance.md` was produced with it, and it is the NDK installed on the
  reference build host. If the F-Droid buildserver cannot fetch an rc through
  `sdkmanager`, switch to the stable r30 revision **`30.0.16248370`**, update
  `ndkVersion` and `ANDROID_NDK_VERSION` together, and re-run the benchmark
  before publishing numbers — a different NDK is a different compiler.

## Native code: llama.cpp / ggml

Phonon bundles the llama.cpp inference engine. **F-Droid requires that every
native object be built from source on their buildserver** — prebuilt `.so` files
are not acceptable, and the current repo's `app/app/src/main/jniLibs/` approach
(locally built, then copied in by `app/sync-native.sh`) does **not** satisfy this.

llama.cpp is a **pinned submodule** at `third_party/llama.cpp`, and the native
artifacts are built from it before Gradle runs.

    git submodule update --init --recursive

Pinned engine revision: **`797de63b2fa7396dcc8caca76834c699ac84e1c3`**
(`797de63`, "hexagon: add support for Q1_0 and Q2_0"). Do not float a branch;
the measured numbers in `docs/performance.md` belong to this revision.

### Building the native artifacts

    export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/30.0.16138531
    ./app/build-native.sh

That script configures llama.cpp for `arm64-v8a`, `android-31`, shared libraries,
`GGML_CPU_REPACK=ON` and `LLAMA_BUILD_SERVER=ON`, builds `llama-server`, then
installs the executable as `libllama-server.so` plus the `libggml*`/`libllama*`
shared objects into `app/app/src/main/jniLibs/arm64-v8a/`. The rename is
required because Android only extracts files named `lib*.so`.

`GGML_CPU_REPACK=ON` is not optional: without it Q4_K weights are not repacked
for ARM and decode is 50–60% slower on identical weights.

`.native-build/` and `app/app/src/main/jniLibs/` are both ignored; nothing
prebuilt is committed.

### F-Droid

The metadata sets `submodules: true` and a `prebuild` step that runs
`./app/build-native.sh` with the pinned NDK. Because the APK is built entirely
from source in the repository, the previous prebuilt-binary blocker no longer
applies.

### Building locally (mirrors what the buildserver does)

    git submodule update --init --recursive
    export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/30.0.16138531
    ./app/build-native.sh
    ./app/gradlew -p app :app:assembleDebug      # or :app:assembleRelease

## Gradle wrapper

The wrapper lives in `app/` (`app/gradlew`, `app/gradle/wrapper/`), with the
jar committed and the distribution pinned. Invoke it from the repository root
with `-p app`, which is what CI does.

    ./app/gradlew -p app :app:assembleDebug

## Signing

The release workflow (`.github/workflows/release.yml`) signs with the project
key. For F-Droid main-repo listing the default is that F-Droid signs with *their*
key; to keep the project key across GitHub Releases, Obtainium and F-Droid, the
project must reach reproducible builds and enable F-Droid's upstream-binary flow
(`Binaries:` / `Builds.binary` plus `AllowedAPKSigningKeys`). Until then, expect
F-Droid- and GitHub-signed APKs to have different signatures.

## Environment summary (for reproduction)

| Component    | Version / value                                    |
|--------------|----------------------------------------------------|
| JDK          | 17                                                 |
| Android SDK  | platform 35, build-tools for AGP 8.7.3             |
| Android NDK  | `30.0.16138531` (r30-rc1), pinned and measured     |
| Gradle       | wrapper in `app/`, distribution pinned             |
| CMake / Ninja| 3.22.1 / Ninja (provided by AGP + SDK)             |
| ABI          | arm64-v8a                                          |
| llama.cpp    | `797de63b2fa7396dcc8caca76834c699ac84e1c3`         |

## Validation before submitting

    fdroid rewritemeta org.phononai.app     # normalise the YAML
    fdroid lint org.phononai.app            # metadata lint
    fdroid build -v -l org.phononai.app     # reproduce the build locally

Then confirm the built APK contains the llama.cpp/ggml shared objects built by
the local toolchain (not copied), and that no `.so` under `src/main/jniLibs/`
is reachable from the source checkout.
