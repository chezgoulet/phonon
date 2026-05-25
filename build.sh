#!/bin/sh
set -e

# Build the Phonon coordinator binary with embedded Web UI.
#
# Steps:
#   1. Build the React frontend (requires Node.js)
#   2. Copy dist to the coordinator source so go:embed can find it
#   3. Compile the Go binary

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Building UI..."
cd ui && npm install --cache /tmp/npm-cache && npm run build && cd ..

echo "==> Copying UI to coordinator embed path..."
rm -rf cmd/phonon-coordinator/static
cp -r ui/dist cmd/phonon-coordinator/static

echo "==> Building coordinator binary..."
go build -o phonon-coordinator ./cmd/phonon-coordinator/

echo "==> Cleaning up embed copy..."
rm -rf cmd/phonon-coordinator/static

echo "==> Done: phonon-coordinator binary built"
