#!/usr/bin/env bash
# Download, verify, and extract OlliteRT release APK.
#
# Fetches the pinned APK from GitHub releases, verifies the SHA-256,
# and extracts libollitert.so into the sidecar assets directory.
#
# Run from the repo root (phonon/):
#   bash sidecar/scripts/fetch-ollitert.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OLLITERT_DIR="$REPO_ROOT/extern/ollitert"

# ── Pinned release ─────────────────────────────────────────────
VERSION="v0.9.5-beta.1"
URL="https://github.com/NightMean/OlliteRT/releases/download/${VERSION}/OlliteRT-${VERSION}-arm64-v8a.apk"
SHA256_EXPECTED="d65ef0b35cb7fc87a7b174721972c1abab2dc547e6c1d075a8f7f9b1c0f0f976"
APK_NAME="OlliteRT-${VERSION}-arm64-v8a.apk"

# Output locations
CACHE_DIR="$REPO_ROOT/.build-cache/ollitert"
ASSETS_DIR="$REPO_ROOT/sidecar/app/src/main/assets/ollitert"
APK_PATH="$CACHE_DIR/$APK_NAME"

# ── Setup ──────────────────────────────────────────────────────
echo "==> OlliteRT $VERSION"
mkdir -p "$CACHE_DIR" "$ASSETS_DIR"

# ── Download ───────────────────────────────────────────────────
if [ -f "$APK_PATH" ]; then
    echo "    APK already cached at $APK_PATH"
else
    echo "==> Downloading $URL ..."
    curl -fsSL -o "$APK_PATH" "$URL"
    echo "    Downloaded $APK_NAME ($(du -h "$APK_PATH" | cut -f1))"
fi

# ── Verify ─────────────────────────────────────────────────────
echo "==> Verifying SHA-256..."
COMPUTED=$(sha256sum "$APK_PATH" | cut -d' ' -f1)
if [ "$COMPUTED" != "$SHA256_EXPECTED" ]; then
    echo "ERROR: SHA-256 mismatch!"
    echo "  Expected: $SHA256_EXPECTED"
    echo "  Got:      $COMPUTED"
    exit 1
fi
echo "    SHA-256 OK"

# ── Extract libollitert.so ─────────────────────────────────────
echo "==> Extracting libollitert.so..."
# APK is a ZIP — extract the native library for arm64-v8a
EXTRACTED=$(unzip -l "$APK_PATH" "lib/arm64-v8a/libollitert.so" 2>/dev/null | grep "libollitert.so" | head -1) || true
if [ -z "$EXTRACTED" ]; then
    APK_LIBS=$(unzip -l "$APK_PATH" "lib/arm64-v8a/*" 2>/dev/null) || true
    echo "ERROR: libollitert.so not found in APK"
    echo "Available lib/arm64-v8a files:"
    echo "$APK_LIBS"
    exit 1
fi

unzip -o -j "$APK_PATH" "lib/arm64-v8a/libollitert.so" -d "$ASSETS_DIR" > /dev/null 2>&1
chmod +x "$ASSETS_DIR/libollitert.so"

echo "==> Done. Extracted:"
echo "    $ASSETS_DIR/libollitert.so ($(du -h "$ASSETS_DIR/libollitert.so" | cut -f1))"
