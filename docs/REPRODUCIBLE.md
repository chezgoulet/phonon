# Reproducible builds

The APK must be byte-identical when rebuilt from the same commit. Without that,
nobody can check that the binary we publish matches the source — F-Droid's
upstream-binary flow is unavailable, and every release is a claim rather than a
proof.

## Status

Verified two ways.

**Same machine, two paths** — two builds at different paths on a clean GitHub
runner produced the same APK:

```
sha256 d7da6c2c... (see the run) at commit 6a7af33, NDK 30.0.16138531
```

**Different machines** — the shipped `v0.1.18` APK, built on a GitHub runner,
was compared entry by entry against a rebuild of the same tag on our own host:

```
entries compared: 120
MATCH: every non-signature entry is identical
```

The signature (`META-INF/`) is the only difference, and only because the local
rebuild is unsigned. That is the property F-Droid needs: it can rebuild the tag
and get the same code we signed.

`.github/workflows/reproducible.yml` re-runs that comparison on every change to
the app or the engine submodule, and fails the build if the bytes differ.

## How to verify

Two clean builds at **different absolute paths**, same commit:

```sh
git clone --recurse-submodules https://github.com/chezgoulet/phonon.git /tmp/a
git clone --recurse-submodules https://github.com/chezgoulet/phonon.git /tmp/b
for d in /tmp/a /tmp/b; do
  ( cd "$d" && PHONON_NATIVE_BUILD="$d/.native" ./app/build-native.sh )
  ( cd "$d" && ./app/gradlew -p app assembleRelease )
done
sha256sum /tmp/a/app/app/build/outputs/apk/release/app-release-unsigned.apk \
         /tmp/b/app/app/build/outputs/apk/release/app-release-unsigned.apk
```

The two digests must match. `.github/workflows/reproducible.yml` does exactly
this on every change to the app or the engine submodule and fails if they differ.

## What had to be fixed

Two sources of nondeterminism, both in the native build:

1. **Absolute paths in the objects.** The NDK compiles with `-g`, so the
   checkout path was embedded and every `.so` differed between two checkouts.
   `app/build-native.sh` now passes `-ffile-prefix-map` for the build directory
   and the engine source, mapping them to `/build` and `/src`.
2. **The engine build number.** `LLAMA_BUILD_NUMBER` is normally derived from
   git, and a shallow submodule has no tags: one machine reported `b1`, another
   `b11716`, so `libllama-common.so` differed in four bytes. It is pinned in
   `build-native.sh` and bumped with the submodule revision.
3. **The linker build-id.** With the paths mapped out, the only remaining
   difference was the ELF build-id note — and it was not being disabled by
   `CMAKE_SHARED_LINKER_FLAGS`, because the NDK passes `--build-id=sha1` through
   `CMAKE_CXX_FLAGS`, which is the list the variant link lines use. The script
   appends `-Wl,--build-id=none` after it, so the last flag wins.

Nothing on Android consumes a build-id, and the app does not use it.

4. **The engine's browser UI.** llama.cpp fetches and gzips its UI during the
   build, so `llama-server-impl` differed on every build. It is off
   (`LLAMA_BUILD_UI=OFF`): the phone serves the API, and F-Droid's buildserver
   has no network anyway.

Gradle's APK packaging is already deterministic: timestamps are normalised, and
the only entries that differed between builds were the native libraries.

## Signing

The release APK is signed with the project key:

```
CN=Chez Goulet, OU=Phonon, O=ChezGoulet.Org, L=Windsor, ST=VT, C=US
SHA-256: 3ba90e38603769e78fc6fbad6cdf3b3a104527491ec4d5dff52b9ea14ce42837
```

That digest is what an F-Droid metadata entry's `AllowedAPKSigningKeys` would
carry if the upstream-binary flow is used. It is public information: it is in
every APK we ship.

## What remains outside the comparison

- The signing step itself: the workflow above compares unsigned APKs. Signing
  is deterministic for the same key and the same input, but the reproducible
  check deliberately stops before it so that keys are not needed in CI.
- Two machines with different NDK builds, rather than one machine and two
  paths. F-Droid's buildserver is the real second machine; the workflow is the
  closest stand-in available here.
