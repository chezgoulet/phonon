#!/data/data/com.termux/files/usr/bin/bash
# Runs ON the phone, in Termux. Measures the two numbers that matter.
#
#   ./bench_phone.sh nomtp     # after: MTP=0 ./start-phone.sh
#   ./bench_phone.sh mtp       # after: MTP=1 ./start-phone.sh
#
# Writes one CSV row to ~/phone-bench.csv and prints it.
# Measure with the server you will actually run, not llama-bench alone:
# llama-bench has been measured at roughly 2x the real server's decode rate.
set -euo pipefail

LABEL="${1:-unnamed}"
BASE="${PHONE_URL:-http://127.0.0.1:8080}"
BASE="${BASE%/}"; BASE="${BASE%/v1}"
KEY="${PHONE_KEY:-}"
OUT="${OUT:-$HOME/phone-bench.csv}"
MODEL_ID="${MODEL_ID:-gemma-4-e2b}"

PROMPT="You are a coding assistant. Read the following task and answer briefly.

In the file src/parser.py, the function parse_header takes a list of strings and
returns a dictionary. Explain in two sentences what a caller should expect when
the list is empty. Then list three unit tests that would be worth writing for it.
Keep the whole answer under 120 words.

Context: the project is a small Python service with a CLI, a parser module, and
a test suite run with pytest. It has no third-party dependencies."

AUTH=()
[ -n "$KEY" ] && AUTH=(-H "Authorization: Bearer $KEY")

post() {
  curl -sS -m 1800 "${AUTH[@]}" "$BASE/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d "$(python3 - "$MODEL_ID" "$PROMPT" <<'PY'
import json,sys
model,prompt=sys.argv[1],sys.argv[2]
print(json.dumps({
  "model": model,
  "messages": [{"role":"user","content":prompt}],
  "max_tokens": 200,
  "temperature": 0,
  "stream": False,
}))
PY
)"
}

echo "== warm-up (not recorded)"
post >/dev/null || { echo "request failed: is llama-server up?" >&2; exit 1; }

rows=()
for i in 1 2; do
  echo "== measured run $i"
  RESP="$(post)"
  ROW="$(printf '%s' "$RESP" | python3 -c '
import json,sys,datetime
d=json.load(sys.stdin)
t=d.get("timings") or {}
print(",".join(str(x) for x in [
  datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
  t.get("prompt_n",""), round(t.get("prompt_per_second",0),2),
  t.get("predicted_n",""), round(t.get("predicted_per_second",0),2),
]))' )"
  rows+=("$ROW")
  echo "   $ROW"
done

TEMP="$(dumpsys battery 2>/dev/null | awk -F': ' '/temperature/{print $2/10" C"}' || echo n/a)"
[ -f "$OUT" ] || echo "at,label,prompt_n,prompt_tps,predicted_n,decode_tps" > "$OUT"
for r in "${rows[@]}"; do
  echo "$r,$LABEL" >> "$OUT"
done
echo "== battery: $TEMP"
echo "== appended to $OUT"
