#!/usr/bin/env bash
# Regression tests for the app's HTTP proxy (ApiServer.kt), run on a desktop
# JVM against a fake llama-server. No phone, no Android SDK.
#
# Each case is a failure that shipped or nearly shipped:
#   slow-prefill   a backend silent for PREFILL_S seconds (default 150, i.e.
#                  past the 120 s stall window) must still get its response.
#                  0.1.4 dropped it at 120 s.
#   stalled-reader a client that stops reading a fast stream must still be
#                  dropped by the stall watchdog.
#   preauth-body   unauthenticated 60 MB bodies against a 256 MB heap (a
#                  separate instance, about an Android app's default) must be
#                  refused without being buffered (no OutOfMemoryError).
#   body-cap       an authorized body over the cap gets 413.
#   slow-head      a client dripping its headers is closed by the 10 s head
#                  deadline (measured at 5 s granularity, so pass is <= 20 s).
#   client-abandon a client that gives up during a long prefill must make the
#                  proxy close the backend, so the phone stops working on a
#                  request nobody will read.
#
# Usage: tools/proxy-harness/run.sh            (about 3 minutes)
#        PREFILL_S=130 tools/proxy-harness/run.sh
# Needs: java 17+, python3, curl. Downloads kotlinc into .build/ unless KOTLINC
# points at one.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
BUILD="$HERE/.build"
PREFILL_S=${PREFILL_S:-150}
KOTLIN_VERSION=2.0.21
BACKEND_PORT=${BACKEND_PORT:-9190}
PORT=${PORT:-8180}
KEY=harness-key
mkdir -p "$BUILD"

if [ -z "${KOTLINC:-}" ]; then
  KOTLINC="$BUILD/kotlinc/bin/kotlinc"
  if [ ! -x "$KOTLINC" ]; then
    echo "fetching kotlinc $KOTLIN_VERSION"
    curl -sSfL -o "$BUILD/kc.zip" \
      "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
    (cd "$BUILD" && unzip -qo kc.zip && rm kc.zip)
  fi
fi

echo "compiling ApiServer.kt"
"$KOTLINC" -nowarn "$REPO/app/app/src/main/java/org/phononai/app/ApiServer.kt" \
  "$HERE/stubs" -include-runtime -d "$BUILD/harness.jar" 2>&1 | grep -v JAVA_TOOL_OPTIONS
[ -f "$BUILD/harness.jar" ] || { echo "compile failed"; exit 2; }

LOG="$BUILD/api.log"
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done; }
trap cleanup EXIT

# Two proxy instances: the memory case runs against its own small-heap JVM so
# an OutOfMemoryError there cannot disable anything the other cases rely on.
MEM_PORT=$((PORT + 1))
MEM_LOG="$BUILD/api-mem.log"
python3 -I "$HERE/fake_llama.py" "$BACKEND_PORT" & pids+=($!)
PORT=$PORT BACKEND_PORT=$BACKEND_PORT API_KEY=$KEY \
  java -jar "$BUILD/harness.jar" >"$LOG" 2>&1 & pids+=($!)
PORT=$MEM_PORT BACKEND_PORT=$BACKEND_PORT API_KEY=$KEY \
  java -Xmx256m -jar "$BUILD/harness.jar" >"$MEM_LOG" 2>&1 & pids+=($!)
for p in $PORT $MEM_PORT; do
  for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$p/health" && break; sleep 0.2; done
done

fails=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; fails=$((fails + 1)); }
URL="http://127.0.0.1:$PORT"
AUTH=(-H "Authorization: Bearer $KEY")

# --- long-running cases, in the background --------------------------------
SLOW_OUT="$BUILD/slow.out"
( start=$(date +%s)
  code=$(curl -s -m $((PREFILL_S + 60)) "${AUTH[@]}" -d '{}' -o "$BUILD/slow.body" \
    -w '%{http_code}' "$URL/v1/slow?s=$PREFILL_S")
  echo "$code $(( $(date +%s) - start ))" >"$SLOW_OUT" ) & slow_pid=$!

# A client that sends a request for a fast stream and never reads it.
python3 -I - "$PORT" "$KEY" <<'EOF' & stall_pid=$!
import socket, sys, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
s.connect(("127.0.0.1", int(sys.argv[1])))
s.sendall(("POST /v1/flood HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer %s\r\n"
           "Content-Length: 2\r\n\r\n{}" % sys.argv[2]).encode())
time.sleep(200)
EOF

# --- quick cases ------------------------------------------------------------
head -c 60000000 /dev/zero >"$BUILD/big.bin"
curls=()
for _ in $(seq 8); do
  curl -s -m 60 -H 'Expect:' -o /dev/null -w '%{http_code} ' \
    --data-binary @"$BUILD/big.bin" "http://127.0.0.1:$MEM_PORT/v1/chat/completions" >>"$BUILD/codes" &
  curls+=($!)
done
wait "${curls[@]}"
codes=$(cat "$BUILD/codes" 2>/dev/null); rm -f "$BUILD/codes"
health=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$MEM_PORT/health")
if grep -q OutOfMemoryError "$MEM_LOG"; then
  fail "preauth-body: OutOfMemoryError in log (curl codes: $codes)"
elif [ "$health" != 200 ]; then
  fail "preauth-body: /health answered $health afterwards"
else
  pass "preauth-body: 8 x 60 MB unauthenticated, no OOM (curl codes: $codes)"
fi

head -c 9000000 /dev/zero >"$BUILD/nine.bin"
code=$(curl -s -m 30 "${AUTH[@]}" -o /dev/null -w '%{http_code}' \
  --data-binary @"$BUILD/nine.bin" "$URL/v1/chat/completions")
[ "$code" = 413 ] && pass "body-cap: 9 MB authorized -> 413" || fail "body-cap: got $code, want 413"

python3 -I - "$PORT" "$KEY" <<'EOF'
import socket, sys, time
s = socket.socket()
s.connect(("127.0.0.1", int(sys.argv[1])))
s.sendall(("POST /v1/slow?s=30 HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer %s\r\n"
           "Content-Length: 2\r\n\r\n{}" % sys.argv[2]).encode())
time.sleep(2)
s.close()
EOF
abandoned=0
for _ in $(seq 30); do
  abandoned=$(curl -s -m 2 "http://127.0.0.1:$BACKEND_PORT/abandoned" || echo 0)
  [ "$abandoned" != "0" ] && break
  sleep 0.5
done
[ "$abandoned" != "0" ] && pass "client-abandon: backend closed when the client left" \
  || fail "client-abandon: backend kept working after the client left"

held=$(python3 -I - "$PORT" <<'EOF'
import socket, sys, time
s = socket.socket(); s.connect(("127.0.0.1", int(sys.argv[1])))
s.sendall(b"GET /v1/models HTTP/1.1\r\n")
start = time.time()
s.settimeout(1)
try:
    while time.time() - start < 60:
        s.sendall(b"X")
        try:
            if s.recv(1) == b"":
                break
        except socket.timeout:
            pass
        time.sleep(4)
except OSError:
    pass
print(int(time.time() - start))
EOF
)
[ "$held" -le 20 ] && pass "slow-head: dripping client closed after ${held}s" \
  || fail "slow-head: dripping client held a worker for ${held}s"

# A memory warning is a 200 with the whole body, not an error. Only `force` -
# in the query or the JSON body - starts the download, so a caller cannot
# ignore the warning by accident.
dl() { curl -s -o "$1" -w '%{http_code}' "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "$2" "$URL/model/download$3"; }
warn_code=$(dl "$BUILD/dl1.json" '{"id":"warn"}' '')
force_code=$(dl "$BUILD/dl2.json" '{"id":"warn"}' '?force=1')
body_code=$(dl "$BUILD/dl3.json" '{"id":"warn","force":true}' '')
plain_code=$(dl "$BUILD/dl4.json" '{"id":"qwen"}' '')
if [ "$warn_code" = 200 ] && grep -q '"warning"' "$BUILD/dl1.json" \
   && [ "$force_code" = 202 ] && grep -q '"force":true' "$BUILD/dl2.json" \
   && [ "$body_code" = 202 ] && grep -q '"force":true' "$BUILD/dl3.json" \
   && [ "$plain_code" = 202 ] && grep -q '"force":false' "$BUILD/dl4.json"; then
  pass "download-force: warning -> 200, ?force=1 and body force -> 202, plain -> 202 unforced"
else
  fail "download-force: warning=$warn_code force=$force_code body=$body_code plain=$plain_code"
fi

# --- collect the background cases -------------------------------------------
echo "waiting for slow-prefill (${PREFILL_S}s) and stalled-reader"
wait "$slow_pid"
read -r code took <"$SLOW_OUT"
if [ "$code" = 200 ] && grep -q done "$BUILD/slow.body" 2>/dev/null; then
  pass "slow-prefill: ${PREFILL_S}s of backend silence -> 200 after ${took}s"
else
  fail "slow-prefill: got http=$code after ${took}s"
fi

for _ in $(seq 60); do grep -q "dropping stalled client" "$LOG" && break; sleep 2; done
grep -q "dropping stalled client" "$LOG" && pass "stalled-reader: watchdog dropped a client that stopped reading" \
  || fail "stalled-reader: no drop within the stall window"
kill "$stall_pid" 2>/dev/null

rm -f "$BUILD/big.bin" "$BUILD/nine.bin"
echo
[ "$fails" = 0 ] && echo "all passed" || echo "$fails failed; log: $LOG"
exit "$fails"
