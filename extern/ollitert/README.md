# OlliteRT — NPU-accelerated inference runtime

OlliteRT is the NPU inference engine used in pool mode (smaller models like
Gemma 4 on Qualcomm Hexagon NPUs on Pixels).

## Not a git submodule

OlliteRT ships as a prebuilt APK with a native library embedded. Cloning the
source repo to cross-compile it is unnecessary — we consume the release binary.
Instead, track the release URL and SHA-256 hash here, and the sidecar build
pipeline downloads and extracts the native library as needed.

## Current pinned release

| Field | Value |
|---|---|
| Version | v0.9.5-beta.1 |
| URL | `https://github.com/NightMean/OlliteRT/releases/download/v0.9.5-beta.1/OlliteRT-v0.9.5-beta.1-arm64-v8a.apk` |
| SHA-256 | `d65ef0b35cb7fc87a7b174721972c1abab2dc547e6c1d075a8f7f9b1c0f0f976` |
| Size | 32,985,679 bytes |
| Released | 2026-04-30 |

## Update process

1. Download the new APK release
2. Compute SHA-256: `sha256sum OlliteRT-*-arm64-v8a.apk`
3. Update this file with the new version, URL, SHA-256
4. Commit and PR

```bash
# Bump example:
OLD_VERSION=v0.9.5-beta.1
NEW_VERSION=v0.10.0
curl -OL "https://github.com/NightMean/OlliteRT/releases/download/$NEW_VERSION/OlliteRT-$NEW_VERSION-arm64-v8a.apk"
sha256sum "OlliteRT-$NEW_VERSION-arm64-v8a.apk"
```

## Extraction

The native library lives inside the APK at:
`lib/arm64-v8a/libollitert.so`

The sidecar Gradle build downloads the APK, verifies the SHA-256, and extracts
the .so into `app/src/main/assets/ollitert/libollitert.so`, where the
PhononService copies it to the app's native library directory at runtime.

## Source repo

- **URL:** https://github.com/NightMean/OlliteRT
- **License:** Apache 2.0
