# OlliteRT — NPU-accelerated inference runtime

OlliteRT is the NPU inference engine used in pool mode (smaller models like
Gemma 4 on Qualcomm Hexagon NPUs on Pixels).

## Not a git submodule

OlliteRT ships as a prebuilt APK with a native library embedded. Cloning the
source repo to cross-compile it is unnecessary — we consume the release binary.
Instead, track the release URL and SHA-256 hash here, and the sidecar build
pipeline downloads and extracts the native library as needed.

## Current pinned release

Check the [releases page](https://github.com/NightMean/OlliteRT/releases) for
the latest version. Update this file when bumping.

```yaml
release:
  version: "v1.0.0"                    # From git tag
  url: "https://github.com/NightMean/OlliteRT/releases/download/v1.0.0/ollitert-arm64.apk"
  sha256: "TODO — set after downloading and checksumming"
```

## Extraction

The native library lives inside the APK at:
`lib/arm64-v8a/libollitert.so`

The sidecar build pipeline extracts it as a Gradle task and bundles it as a
raw asset under `sidecar/app/src/main/assets/libollitert.so`, where the
PhononService copies it to the app's native library directory at runtime.

## Source repo

- **URL:** https://github.com/NightMean/OlliteRT
- **License:** Apache 2.0
