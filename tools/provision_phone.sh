#!/usr/bin/env bash
# Provision a phone as a Phonon inference node.
#
#   ./provision_phone.sh <serial> <host_port> <alias> [model_url]
#
# Installs the app, fetches the model over the USB reverse link, sets the
# runtime configuration, starts the server and forwards the operator port to
# the host. Idempotent: re-running it on a provisioned phone changes nothing
# that is already correct.
#
# Prerequisites on the host:
#   - adb on PATH
#   - a file server for the model on port 9002 (see docs)
#   - the debug APK at app/app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

# Non-interactive shells do not source the login PATH.
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:${PATH}"
command -v adb >/dev/null 2>&1 || { echo "adb not found on PATH" >&2; exit 1; }

SERIAL="${1:?usage: provision_phone.sh <serial> <host_port> <alias> [model_url]}"
HOST_PORT="${2:?host port}"
ALIAS="${3:?model alias, e.g. qwen3.5-2b}"
MODEL_URL="${4:-http://127.0.0.1:9002/Qwen3.5-2B-Q4_K_M.gguf}"
MODEL_NAME="$(basename "$MODEL_URL")"
APK="${APK:-$(dirname "$0")/../app/app/build/outputs/apk/debug/app-debug.apk}"
CONTEXT="${CONTEXT:-32768}"
DEVICE_MODEL_DIR="/data/user/0/org.phononai.app/files/models"

say() { printf '%s\n' "==> $*"; }

say "device $SERIAL"
adb -s "$SERIAL" get-state >/dev/null || { echo "device not online" >&2; exit 1; }
MODEL_PROP=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
say "model: $MODEL_PROP"

say "install $APK"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
adb -s "$SERIAL" install -r "$APK" >/dev/null

say "file server for the model over USB"
# The model is served from the host's loopback and pulled through adb reverse,
# so the phone needs no network of its own.
adb -s "$SERIAL" reverse tcp:9002 tcp:9002 >/dev/null

say "start the app and its operator port"
adb -s "$SERIAL" forward --remove "tcp:${HOST_PORT}" >/dev/null 2>&1 || true
adb -s "$SERIAL" forward "tcp:${HOST_PORT}" tcp:8080 >/dev/null
adb -s "$SERIAL" shell am start -n org.phononai.app/.MainActivity >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
  curl -s -m 2 "http://127.0.0.1:${HOST_PORT}/health" >/dev/null 2>&1 && break
  sleep 1
done

have_model=$(adb -s "$SERIAL" shell "run-as org.phononai.app ls ${DEVICE_MODEL_DIR}/${MODEL_NAME} 2>/dev/null" | tr -d '\r')
if [ -z "$have_model" ]; then
  say "download ${MODEL_NAME}"
  curl -s -m 10 -X POST "http://127.0.0.1:${HOST_PORT}/model/download" \
    -H 'Content-Type: application/json' -d "{\"url\":\"${MODEL_URL}\"}" >/dev/null
  for _ in $(seq 1 240); do
    size=$(adb -s "$SERIAL" shell "run-as org.phononai.app ls -l ${DEVICE_MODEL_DIR}/${MODEL_NAME} 2>/dev/null | awk '{print \$4}'" | tr -d '\r')
    [ -n "$size" ] && { say "downloaded: ${size} bytes"; break; }
    sleep 2
  done
else
  say "model already present"
fi

say "configure"
curl -s -m 10 -X POST "http://127.0.0.1:${HOST_PORT}/settings" -H 'Content-Type: application/json' \
  -d "{\"model_path\":\"${DEVICE_MODEL_DIR}/${MODEL_NAME}\",\"alias\":\"${ALIAS}\",\"context\":${CONTEXT}}" >/dev/null

say "start the server"
curl -s -m 10 -X POST "http://127.0.0.1:${HOST_PORT}/server/start" >/dev/null || true
for _ in $(seq 1 90); do
  H=$(curl -s -m 2 "http://127.0.0.1:${HOST_PORT}/health" || true)
  echo "$H" | grep -q '"state":"ready"' && { echo "$H"; break; }
  sleep 2
done

# Keep the screen awake while charging. On a Pixel this is what keeps the app
# in the top-app cpuset, which is worth about 2.4x decode. Verify it stuck.
say "hold the device awake (top-app cpuset)"
adb -s "$SERIAL" shell svc power stayon usb >/dev/null 2>&1 || true
adb -s "$SERIAL" shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true

PID=$(curl -s -m 3 "http://127.0.0.1:${HOST_PORT}/health" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("pid") or "")' 2>/dev/null || true)
if [ -n "$PID" ]; then
  adb -s "$SERIAL" shell "grep -E 'cpuset|Cpus_allowed_list' /proc/${PID}/cgroup /proc/${PID}/status" 2>/dev/null | tr -d '\r' || true
fi

cat <<EOF

==> phone provisioned
    serial:    $SERIAL
    alias:     $ALIAS
    host port: http://127.0.0.1:${HOST_PORT}/v1
    model:     $MODEL_NAME at ${CONTEXT} context

Add this to the gateway's model_list:

  - model_name: ${ALIAS}
    litellm_params:
      model: openai/${ALIAS}
      api_base: http://127.0.0.1:${HOST_PORT}/v1
      api_key: sk-none
      timeout: 1800
      stream_timeout: 1800
      max_retries: 0
EOF
