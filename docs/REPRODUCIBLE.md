# Reproducible builds

The APK must be byte-identical when rebuilt from the same commit. Without that,
nobody can check that the binary we publish matches the source — F-Droid's
upstream-binary flow is unavailable, and every release is a claim rather than a
proof.

## Status

Verified. Two builds at `/tmp/repro/one` and `/tmp/repro/two` on a clean GitHub
runner produced the same APK:

```
sha256 a767070286eebc9050ec25c29fce21e79447736234f42b1bb5186d35de3a8c12
commit 5881cf5, NDK 30.0.16138531
```

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
2. **The linker build-id.** With the paths mapped out, the only remaining
   difference was the ELF build-id note — and it was not being disabled by
   `CMAKE_SHARED_LINKER_FLAGS`, because the NDK passes `--build-id=sha1` through
   `CMAKE_CXX_FLAGS`, which is the list the variant link lines use. The script
   appends `-Wl,--build-id=none` after it, so the last flag wins.

Nothing on Android consumes a build-id, and the app does not use it.

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
