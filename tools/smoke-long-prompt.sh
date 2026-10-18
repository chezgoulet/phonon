#!/usr/bin/env bash
# Release-gate smoke test: a prompt long enough to prefill past a couple of
# minutes, through the endpoint users actually call.
#
# Why this exists: 0.1.4 shipped with a proxy that disconnected any request
# whose prefill exceeded the stall window. Every short test passed; the first
# real agent turn did not. This sends ~2,500 tokens and requires a completion.
#
#   tools/smoke-long-prompt.sh --url http://10.0.10.125:8080 \
#                              --key-file /path/to/key [--tokens 2500]
#
# Exit 0 only when the request returns 200 with a completion.
set -euo pipefail

URL=""
KEY_FILE=""
TOKENS=2500
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --key-file) KEY_FILE="$2"; shift 2 ;;
    --tokens) TOKENS="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$URL" ] || { echo "usage: $0 --url URL --key-file PATH [--tokens N]" >&2; exit 2; }

BODY=$(python3 - "$TOKENS" <<'PY'
import json, random, sys
target = int(sys.argv[1])
random.seed(11)
words = ("lattice meadow harbour lantern orchard cellar prairie copper willow granite "
         "cipher tundra falcon marrow quartz beacon thicket hollow raven summit bramble").split()
parts = []
# roughly four characters per token for this vocabulary
while sum(len(p) + 1 for p in parts) < target * 4:
    parts.append(" ".join(random.choice(words) for _ in range(12)))
prompt = "Summarise the following in one sentence: " + " ".join(parts)
print(json.dumps({"messages": [{"role": "user", "content": prompt}],
                  "max_tokens": 8, "temperature": 0}))
PY
)

start=$(date +%s)
if [ -n "$KEY_FILE" ]; then
  OUT=$(printf '%s' "$BODY" | curl -sS -m 900 -w '\n%{http_code}' \
    -H "Authorization: Bearer $(cat "$KEY_FILE")" \
    -H 'Content-Type: application/json' --data-binary @- \
    "$URL/v1/chat/completions" 2>&1) || { echo "FAIL: request did not complete" >&2; exit 1; }
else
  OUT=$(printf '%s' "$BODY" | curl -sS -m 900 -w '\n%{http_code}' \
    -H 'Content-Type: application/json' --data-binary @- \
    "$URL/v1/chat/completions" 2>&1) || { echo "FAIL: request did not complete" >&2; exit 1; }
fi
took=$(( $(date +%s) - start ))
code=$(printf '%s' "$OUT" | tail -n1)
body=$(printf '%s' "$OUT" | sed '$d')

echo "requested ~${TOKENS} tokens, completed in ${took}s, http ${code}"
printf '%s' "$body" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
t = d.get("timings") or {}
u = d.get("usage") or {}
print(f"  prompt tokens {u.get(\"prompt_tokens\")}  prefill {t.get(\"prompt_ms\", 0)/1000:.0f}s  "
      f"decode {t.get(\"predicted_per_second\", 0):.2f} tok/s")
' || true

if [ "$code" != "200" ]; then
  echo "FAIL: expected 200, got $code" >&2
  exit 1
fi
if ! printf '%s' "$body" | grep -q '"choices"'; then
  echo "FAIL: response carried no choices" >&2
  exit 1
fi
echo "PASS"
