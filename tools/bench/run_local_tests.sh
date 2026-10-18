#!/usr/bin/env bash
# run_local_tests.sh - local syntax, compile, dry-run and logic checks.
#
# This script touches NO phone.  --dry-run performs no network I/O at all.
# The only network call is a deliberate health probe to a closed local port,
# to prove the unreachable path exits non-zero without touching a device.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-python3}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/phonon-selftest.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
fail=0
pass=0

ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
check(){ if [[ "$2" == "0" ]]; then ok "$1"; else bad "$1"; fi; }

echo "== 1. bash syntax"
for f in bench.sh run_local_tests.sh; do
  bash -n "$SCRIPT_DIR/$f"; check "bash -n $f" "$?"
done

echo "== 2. shellcheck (informational)"
if command -v shellcheck >/dev/null 2>&1; then
  if shellcheck -S error "$SCRIPT_DIR/bench.sh" "$SCRIPT_DIR/run_local_tests.sh"; then
    ok "shellcheck: no errors"
  else
    bad "shellcheck reported errors"
  fi
else
  echo "  SKIP  shellcheck not installed"
fi

echo "== 3. python compile"
for f in node_probe.py probe_temp.py report.py; do
  PYTHONPYCACHEPREFIX="$TMP/pyc" "$PY" -m py_compile "$SCRIPT_DIR/$f"
  check "py_compile $f" "$?"
done

echo "== 4. node_probe: health against a closed local port -> exit 4"
"$PY" "$SCRIPT_DIR/node_probe.py" health --url http://127.0.0.1:9 --timeout 2 >/dev/null 2>&1
[[ $? -eq 4 ]]; check "unreachable health exits 4" "$?"

echo "== 5. node_probe: simulate needs no key and emits metrics"
OUT="$("$PY" "$SCRIPT_DIR/node_probe.py" generate --url http://127.0.0.1:9 \
      --max-tokens 32 --fill-prompt-tokens 64 --regime sustained --sim-index 0 \
      --seed 1234 --simulate 2>/dev/null)"
printf '%s' "$OUT" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); assert d["status"]=="ok" and d["source"]=="simulated" and d["decode_tok_s"]>0'
check "simulate emits valid ok metrics" "$?"

echo "== 6. probe_temp: simulate emits a float"
"$PY" "$SCRIPT_DIR/probe_temp.py" --simulate --sim-elapsed 120 \
  | "$PY" -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d["temp_c"], float)'
check "probe_temp simulate" "$?"

echo "== 7. dry-run bench (phone-a, tiny budgets, no key file exists)"
DEMO="$TMP/demo"
"$SCRIPT_DIR/bench.sh" --node phone-a --dry-run \
  --burst-tokens 16 --sustained-tokens 32 --sustained-seconds 5 \
  --recovery-idle 2 --recovery-tokens 16 --max-runtime 60 \
  --outdir "$DEMO" >/dev/null 2>"$TMP/bench.err"
rc=$?
check "dry-run bench exit 0" "$([[ $rc -eq 0 ]] && echo 0 || echo 1)"

RUN="$(find "$DEMO" -maxdepth 1 -mindepth 1 -type d | head -1)"
[[ -n "$RUN" ]]; check "run directory created" "$([[ -n "$RUN" ]] && echo 0 || echo 1)"

for artefact in bench.csv summary.md summary.json raw.jsonl params.txt; do
  [[ -s "$RUN/$artefact" ]]; check "artefact present: $artefact" "$([[ -s "$RUN/$artefact" ]] && echo 0 || echo 1)"
done

echo "== 8. CSV shape and content"
"$PY" - "$RUN/bench.csv" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
assert rows, "no rows"
regimes = {r["regime"] for r in rows}
assert {"burst", "sustained", "recovery"} <= regimes, regimes
assert all(r["status"] == "ok" for r in rows), "non-ok row"
assert all(r["decode_tok_s"] for r in rows), "missing decode"
assert all(r["temp_c"] for r in rows), "missing temp"
PY
check "csv has all three regimes, ok rows, decode+temp populated" "$?"

echo "== 9. summary contains median/min/max and throttle field"
grep -q "median" "$RUN/summary.md" && grep -qi "time to first throttle" "$RUN/summary.md"
check "summary.md has median + throttle" "$?"
"$PY" - "$RUN/summary.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
node = s["nodes"]["phone-a"]
for regime in ("burst", "sustained", "recovery"):
    st = node["regimes"][regime]["decode_tok_s"]
    assert set(("median", "min", "max")) <= set(st), st
assert "time_to_first_throttle" in node
assert "throttle_status" in node
PY
check "summary.json stats + throttle keys" "$?"

echo "== 10. no API key material anywhere in the artefacts"
# No real key exists here; assert none of the forbidden forms appear.
if grep -rIl -E 'Bearer [A-Za-z0-9._-]{8,}|api[_-]?key[":= ]' "$RUN" 2>/dev/null | grep -q .; then
  bad "possible key material found in artefacts"
else
  ok "no key-like material in artefacts"
fi
grep -q "contents never recorded" "$RUN/params.txt"
check "params.txt records path only, not contents" "$?"

echo "== 11. throttle detection unit test (synthetic, no bench run)"
"$PY" - "$TMP/throttle.jsonl" <<'PY'
import json, sys
rows = []
# burst baseline ~12.5
for i in range(2):
    rows.append(dict(ts="x", node="unit", regime="burst", gen_index=i, elapsed_s=i*3,
                     prompt_tokens=512, completion_tokens=128, prefill_tok_s=70.0,
                     decode_tok_s=12.5, wall_s=20.0, temp_c=31.0, status="ok",
                     source="server", note=""))
# sustained: fast for 6, then drops below 0.8*12.5=10 starting at gen 6
for i in range(12):
    dec = 12.2 if i < 6 else (8.0 if i < 10 else 12.0)
    rows.append(dict(ts="x", node="unit", regime="sustained", gen_index=i,
                     elapsed_s=i*18.0, prompt_tokens=512, completion_tokens=192,
                     prefill_tok_s=70.0, decode_tok_s=dec, wall_s=25.0,
                     temp_c=33.0 + i*0.5, status="ok", source="server", note=""))
with open(sys.argv[1], "w") as fh:
    for r in rows:
        fh.write(json.dumps(r) + "\n")
PY
"$PY" "$SCRIPT_DIR/report.py" report --jsonl "$TMP/throttle.jsonl" \
  --csv "$TMP/t.csv" --md "$TMP/t.md" --json "$TMP/t.json" >/dev/null
"$PY" - "$TMP/t.json" <<'PY'
import json, sys
node = json.load(open(sys.argv[1]))["nodes"]["unit"]
assert node["throttle_status"] == "throttled", node["throttle_status"]
d = node["time_to_first_throttle"]
assert d["gen_index"] == 6, d          # first of the 2-sample run at/below 10
assert abs(d["elapsed_s"] - 6*18.0) < 1e-6, d
assert abs(node["baseline_decode_tok_s"] - 12.5) < 1e-6, node["baseline_decode_tok_s"]
PY
check "throttle flagged at window start with burst baseline" "$?"

echo "== 12. not-throttled path"
"$PY" - "$TMP/flat.jsonl" <<'PY'
import json, sys
rows = [dict(ts="x", node="flat", regime="sustained", gen_index=i, elapsed_s=i*18.0,
             prompt_tokens=512, completion_tokens=192, prefill_tok_s=70.0,
             decode_tok_s=12.0, wall_s=25.0, temp_c=33.0, status="ok",
             source="server", note="") for i in range(8)]
open(sys.argv[1], "w").write("\n".join(json.dumps(r) for r in rows) + "\n")
PY
"$PY" "$SCRIPT_DIR/report.py" report --jsonl "$TMP/flat.jsonl" \
  --csv "$TMP/f.csv" --md "$TMP/f.md" --json "$TMP/f.json" >/dev/null
"$PY" - "$TMP/f.json" <<'PY'
import json, sys
node = json.load(open(sys.argv[1]))["nodes"]["flat"]
assert node["throttle_status"] == "not_throttled", node["throttle_status"]
assert node["time_to_first_throttle"] is None
PY
check "flat run reports not_throttled" "$?"

echo "== 13. empty JSONL still yields a valid report (aborted-run path)"
: > "$TMP/empty.jsonl"
"$PY" "$SCRIPT_DIR/report.py" report --jsonl "$TMP/empty.jsonl" \
  --csv "$TMP/e.csv" --md "$TMP/e.md" --json "$TMP/e.json" >/dev/null
[[ -s "$TMP/e.csv" && -s "$TMP/e.md" && -s "$TMP/e.json" ]]
check "empty run produces header-only CSV + summaries" "$?"

echo
echo "-----------------------------------------"
printf 'passed: %d   failed: %d\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]] && echo "ALL LOCAL CHECKS PASSED" || echo "SOME CHECKS FAILED"
exit "$(( fail > 0 ? 1 : 0 ))"
