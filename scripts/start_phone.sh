#!/data/data/com.termux/files/usr/bin/bash
# Runs ON the phone, in Termux. Starts llama-server in the background.
#
#   ./start-phone.sh                # MTP on (default)
#   MTP=0 ./start-phone.sh          # baseline, no drafter
#   CTX=4096 ./start-phone.sh       # smaller context if memory is tight
#
# Stop with: ./stop-phone.sh  (or: pkill -f llama-server)
set -euo pipefail

MODELS="${MODELS:-$HOME/models}"
MODEL="${MODEL:-$MODELS/gemma-4-E2B-it-Q4_K_M.gguf}"
DRAFTER="${DRAFTER:-$MODELS/mtp-gemma-4-E2B-it-Q8_0.gguf}"
PORT="${PORT:-8080}"
CTX="${CTX:-8192}"
THREADS="${THREADS:-4}"
MTP="${MTP:-1}"
API_KEY="${PHONE_KEY:-}"

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

ARGS=(
  -m "$MODEL"
  -c "$CTX"
  -t "$THREADS"
  -np 1
  -fa on
  --jinja
  --alias gemma-4-e2b
  --host 0.0.0.0
  --port "$PORT"
  --metrics
)

if [ "$MTP" = "1" ]; then
  [ -f "$DRAFTER" ] || { echo "missing drafter: $DRAFTER" >&2; exit 1; }
  ARGS+=(-md "$DRAFTER" --spec-type draft-mtp --spec-draft-n-max 4)
fi

if [ -n "$API_KEY" ]; then
  ARGS+=(--api-key "$API_KEY")
else
  echo "WARNING: PHONE_KEY unset — the endpoint is unauthenticated on the LAN" >&2
fi

LOG="$HOME/llama-server.log"
PIDFILE="$HOME/llama-server.pid"

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "already running (pid $(cat "$PIDFILE")); stop it first" >&2
  exit 1
fi

nohup llama-server "${ARGS[@]}" >"$LOG" 2>&1 &
echo $! > "$PIDFILE"
sleep 3

if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "llama-server exited immediately; last lines:" >&2
  tail -20 "$LOG" >&2
  exit 1
fi

echo "llama-server pid $(cat "$PIDFILE"), http://0.0.0.0:$PORT/v1 (mtp=$MTP, ctx=$CTX, threads=$THREADS)"
