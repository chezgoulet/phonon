#!/usr/bin/env bash
# Runs on the HOST. Checks the phone endpoint, and optionally restarts the
# server over ssh (Termux sshd, default port 8022).
#
#   ./health.sh
#   ./health.sh --restart
#
# Exits 0 if healthy, 1 if not.
set -uo pipefail

BASE="${PHONE_URL:-http://phone.lan:8080}"
BASE="${BASE%/}"; BASE="${BASE%/v1}"
KEY="${PHONE_KEY:-}"
AUTH=()
[ -n "$KEY" ] && AUTH=(-H "Authorization: Bearer $KEY")

if curl -sS -m 10 "${AUTH[@]}" "$BASE/health" >/tmp/phone-health.out 2>/dev/null; then
  echo "healthy: $(cat /tmp/phone-health.out)"
  exit 0
fi

echo "UNHEALTHY: $BASE/health did not answer" >&2

if [ "${1:-}" = "--restart" ]; then
  SSH_TARGET="${TERMUX_SSH:-phone.lan}"
  SSH_PORT="${TERMUX_PORT:-8022}"
  echo "restarting over ssh $SSH_TARGET:$SSH_PORT" >&2
  ssh -p "$SSH_PORT" -o BatchMode=yes -o ConnectTimeout=10 "$SSH_TARGET" \
    'pkill -f llama-server; sleep 1; cd ~/phone-lane && PHONE_KEY="$PHONE_KEY" ./start-phone.sh' \
    || echo "restart failed" >&2
fi

exit 1
