# Building Phonon

This document describes how the Android app is built from source, with enough
detail for a contributor or an F-Droid buildserver to reproduce an APK. It is a
draft: values marked **REVIEW** must be confirmed against the repository before
the F-Droid merge request is opened.

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
| versionCode    | 3                                                |
| versionName    | 0.3.0                                            |

## Toolchain

* **JDK 17** (source/target 17). F-Droid's buildserver provides a FOSS JDK; do
  not require Oracle JDK.
* **Android SDK**, platform 35 and build-tools matching AGP 8.7.3.
* **Android NDK `30.0.16138531`** as pinned in `app/app/build.gradle.kts`
  (`ndkVersion`).

  **REVIEW / GAP:** `30.0.16138531` is the **r30-rc1** revision, i.e. a release
  *candidate*. The stable r30 revision is **`30.0.16248370`**. The buildserver
  installs NDKs through the SDK manager; an rc may not be offered on the stable
  channel. Prefer pinning the stable revision (and updating `ndkVersion`) unless
  the app genuinely needs the rc. Either spelling is accepted by the metadata
  `ndk:` field (`r30` or the full revision).

## Native code: llama.cpp / ggml

Phonon bundles the llama.cpp inference engine. **F-Droid requires that every
native object be built from source on their buildserver** — prebuilt `.so` files
are not acceptable, and the current repo's `app/app/src/main/jniLibs/` approach
(locally built, then copied in by `app/sync-native.sh`) does **not** satisfy this.

The required change is to vendor and build llama.cpp from source. Two supported
recipes; the metadata draft assumes the first.

### Recipe A — git submodule (preferred)

1. Add llama.cpp as a submodule under the CMake project:

       git submodule add https://github.com/ggml-org/llama.cpp \
           app/app/src/main/cpp/llama.cpp
       cd app/app/src/main/cpp/llama.cpp
       git checkout <LLAMA_CPP_40_CHAR_COMMIT_SHA>     # pin, do not track a branch

2. Build it from `app/app/src/main/cpp/CMakeLists.txt`, e.g.:

       add_subdirectory(llama.cpp)
       # and wire the phonon targets to link llama / ggml / mtmd

   The exact target list to produce (`libllama.so`, `libggml.so`,
   `libggml-base.so`, `libggml-cpu.so`, `libmtmd.so`, and the `llama-server`
   executable packaged as `libllama-server.so`) must be matched to the current
   `sync-native.sh` output. **REVIEW:** `llama-server` is an executable; Android
   only extracts files named `lib*.so`, so a rename step (a Gradle task or
   `postbuild`) is still needed, or the server must be built as a shared library.

3. In the F-Droid metadata set `submodules: true` and keep the NDK pinned.

   Pinned commit (`LLAMA_CPP_40_CHAR_COMMIT_SHA`): **REVIEW — record the exact
   SHA here and in `NOTICE`.** e.g. build measured against llama.cpp commit
   `________________________________________`.

### Recipe B — clone in the recipe

Keep llama.cpp out of the repo and let the metadata `prebuild:` clone it at a
pinned SHA (see the commented block in `fdroiddata/com.phononai.app.yml`). Less
clean; `submodules: true` is preferred.

### Building locally (mirrors what the buildserver does)

    # NDK via sdkmanager
    sdkmanager "ndk;30.0.16248370"      # or the rc revision, if using it

    # submodule checkout
    cd app && git submodule update --init --recursive

    # build the release APK
    ./gradlew :app:assembleRelease

    # output
    #   app/app/build/outputs/apk/release/app-release-unsigned.apk

AGP passes the NDK's own cmake toolchain
(`$NDK/build/cmake/android.toolchain.cmake`) to the CMake project; the CMake
version used by AGP is 3.22.1 with Ninja. `-DANDROID_PLATFORM=android-31` and
the toolchain are set automatically from `minSdk`/`ndkVersion`, so the recipe
does not pin a toolchain file by hand.

## Gradle wrapper

**GAP:** the repository contains no Gradle wrapper (`gradlew`,
`gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`). Commit one
pinned to a Gradle version compatible with AGP 8.7.3 (Gradle 8.9+), including
`distributionSha256Sum`, so the F-Droid buildserver and CI build with an
identical Gradle. Without it, the build depends on whatever Gradle the
environment happens to provide.

    gradle wrapper --gradle-version 8.9 --distribution-type bin

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
| Android NDK  | `30.0.16138531` (r30-rc1) — prefer `30.0.16248370` |
| Gradle       | pin via wrapper (8.9+); currently unpinned (GAP)   |
| CMake / Ninja| 3.22.1 / Ninja (provided by AGP + SDK)             |
| ABI          | arm64-v8a                                          |
| llama.cpp    | pinned submodule commit: **REVIEW**                |

## Validation before submitting

    fdroid rewritemeta org.phononai.app     # normalise the YAML
    fdroid lint org.phononai.app            # metadata lint
    fdroid build -v -l org.phononai.app     # reproduce the build locally

Then confirm the built APK contains the llama.cpp/ggml shared objects built by
the local toolchain (not copied), and that no `.so` under `src/main/jniLibs/`
is reachable from the source checkout.
