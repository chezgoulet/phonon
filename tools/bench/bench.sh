#!/usr/bin/env bash
# phonon-bench - repeatable sustained-load benchmark harness for the Phonon
# phone nodes (llama.cpp servers on Pixel 7 devices).
#
# Bash + python3 (stdlib only).  Safe by design:
#   * bounded total runtime (--max-runtime)
#   * strictly sequential requests - one generation at a time, never parallel
#   * aborts if <base>/health is not ready
#   * never prints, echoes or logs the API key (it is read from disk by the
#     python helper and placed only in the Authorization header)
#
# Three regimes are measured:
#   burst      one short generation (default 128 tokens) from a cool device
#   sustained  continuous generations (default 192 tokens) for 15 minutes
#   recovery   idle (default 5 min), then one short generation again
#
# Outputs land in <outdir>/<node>_<utc-stamp>/:
#   raw.jsonl  bench.csv  summary.md  summary.json  params.txt
#
# See README.md for the method and for how to use the results.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-python3}"

# --- defaults --------------------------------------------------------------
NODE=""
BASE_URL=""
KEY_FILE=""
MODEL="${PHONON_MODEL:-qwen3.5-2b}"
REGIMES="burst,sustained,recovery"
BURST_TOKENS=128
SUSTAINED_TOKENS=192
SUSTAINED_SECONDS=900          # 15 minutes
RECOVERY_IDLE=300              # 5 minutes
RECOVERY_TOKENS=128
MAX_RUNTIME=2400               # hard ceiling for the whole invocation (s)
REQUEST_TIMEOUT=180
HEALTH_TIMEOUT=10
PROMPT_TOKENS=512              # filler-prompt size, keeps prefill consistent
INTER_GEN_SLEEP=0
THROTTLE_FRAC=0.8
THROTTLE_WINDOW=2
BASELINE_SAMPLES=5
SEED=1234
OUTDIR="results"
DRY_RUN=0
ADB_SERIAL=""
TEMP_URL=""
TEMP_CMD=""

# --- helpers ---------------------------------------------------------------
log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { printf 'phonon-bench: error: %s\n' "$*" >&2; exit 2; }

usage() {
  cat >&2 <<'EOF'
Usage: bench.sh --node phone-a|phone-b [options]

Node (pick one source of truth):
  --node NAME            preset name from PHONON_NODES (default: phone-a)
  --url BASE             base URL, e.g. http://192.0.2.10:8080
  --key-file PATH        file containing the bearer token

Regimes:
  --regimes LIST         comma list of burst,sustained,recovery (default all)
  --burst-tokens N       default 128
  --sustained-tokens N   default 192
  --sustained-seconds N  default 900 (15 min)
  --recovery-idle N      default 300 (5 min)
  --recovery-tokens N    default 128
  --inter-gen-sleep N    seconds between sustained generations (default 0)

Temperature probe (optional; column is blank if none given):
  --adb-serial SERIAL    run: adb -s SERIAL shell dumpsys battery
  --temp-url URL         GET URL, take first number in the body
  --temp-cmd CMD         run CMD, take first number in stdout

Safety / limits:
  --max-runtime N        hard ceiling for this invocation, seconds (default 2400)
  --request-timeout N    per-request timeout, seconds (default 180)
  --health-timeout N     /health timeout, seconds (default 10)

Reporting:
  --outdir DIR           default ./results
  --throttle-frac F      decode threshold as fraction of baseline (default 0.8)
  --throttle-window N    consecutive low samples (default 2)
  --baseline-samples N   head samples for baseline if no burst (default 5)

Other:
  --model ID             default qwen3.5-2b (or $PHONON_MODEL)
  --prompt-tokens N      filler prompt size, default 512
  --seed N               simulation seed (default 1234)
  --dry-run              no network, no adb; synthetic metrics for local testing
  -h, --help             this text
EOF
}

# --- argument parsing ------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --node) NODE="$2"; shift 2 ;;
    --url) BASE_URL="$2"; shift 2 ;;
    --key-file) KEY_FILE="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    --regimes) REGIMES="$2"; shift 2 ;;
    --burst-tokens) BURST_TOKENS="$2"; shift 2 ;;
    --sustained-tokens) SUSTAINED_TOKENS="$2"; shift 2 ;;
    --sustained-seconds) SUSTAINED_SECONDS="$2"; shift 2 ;;
    --recovery-idle) RECOVERY_IDLE="$2"; shift 2 ;;
    --recovery-tokens) RECOVERY_TOKENS="$2"; shift 2 ;;
    --inter-gen-sleep) INTER_GEN_SLEEP="$2"; shift 2 ;;
    --adb-serial) ADB_SERIAL="$2"; shift 2 ;;
    --temp-url) TEMP_URL="$2"; shift 2 ;;
    --temp-cmd) TEMP_CMD="$2"; shift 2 ;;
    --max-runtime) MAX_RUNTIME="$2"; shift 2 ;;
    --request-timeout) REQUEST_TIMEOUT="$2"; shift 2 ;;
    --health-timeout) HEALTH_TIMEOUT="$2"; shift 2 ;;
    --outdir) OUTDIR="$2"; shift 2 ;;
    --throttle-frac) THROTTLE_FRAC="$2"; shift 2 ;;
    --throttle-window) THROTTLE_WINDOW="$2"; shift 2 ;;
    --baseline-samples) BASELINE_SAMPLES="$2"; shift 2 ;;
    --prompt-tokens) PROMPT_TOKENS="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

# --- node presets ----------------------------------------------------------
case "$NODE" in
  phone-a) BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"; KEY_FILE="${KEY_FILE:-$HOME/.phonon-key}" ;;
  phone-b) BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"; KEY_FILE="${KEY_FILE:-$HOME/.phonon-key}" ;;
  "") : ;;
  *) die "unknown --node '$NODE' (expected phone-a or phone-b)" ;;
esac

[[ -n "$BASE_URL" ]] || die "no base URL: pass --node phone-a|phone-b or --url"
# Accept a URL given with the /v1 suffix; the helpers append their own paths.
BASE_URL="${BASE_URL%/}"
BASE_URL="${BASE_URL%/v1}"

if [[ "$DRY_RUN" != "1" ]]; then
  [[ -n "$KEY_FILE" ]] || die "no key file: pass --key-file (or use --node preset)"
  [[ -r "$KEY_FILE" ]] || die "key file not readable: $KEY_FILE"
fi

[[ "$REGIMES" =~ (burst|sustained|recovery) ]] || die "no valid regime in '$REGIMES'"

# --- concurrency guard: never run two benches against the same node --------
NODE_TAG="${NODE:-node}"
LOCKFILE="${TMPDIR:-/tmp}/phonon-bench-${NODE_TAG}.lock"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCKFILE"
  if ! flock -n 9; then
    die "another phonon-bench run holds $LOCKFILE (refusing to run in parallel)"
  fi
else
  log "warning: flock not found; cannot guard against concurrent runs"
fi

# --- run dir + artefacts ---------------------------------------------------
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="$OUTDIR/${NODE_TAG}_${STAMP}"
mkdir -p "$RUN_DIR"
RAW_JSONL="$RUN_DIR/raw.jsonl"
: > "$RAW_JSONL"

# params.txt records configuration only - never key material.
{
  echo "node        : $NODE_TAG"
  echo "base_url    : $BASE_URL"
  echo "key_file    : ${KEY_FILE:-(none)}   # path only, contents never recorded"
  echo "model       : $MODEL"
  echo "regimes     : $REGIMES"
  echo "burst_tokens: $BURST_TOKENS"
  echo "sustained_tokens: $SUSTAINED_TOKENS"
  echo "sustained_seconds: $SUSTAINED_SECONDS"
  echo "recovery_idle: $RECOVERY_IDLE"
  echo "recovery_tokens: $RECOVERY_TOKENS"
  echo "max_runtime : $MAX_RUNTIME"
  echo "request_timeout: $REQUEST_TIMEOUT"
  echo "prompt_tokens: $PROMPT_TOKENS"
  echo "throttle_frac: $THROTTLE_FRAC"
  echo "throttle_window: $THROTTLE_WINDOW"
  echo "dry_run     : $DRY_RUN"
  echo "temp_probe  : adb=${ADB_SERIAL:-none} url=${TEMP_URL:-none} cmd=${TEMP_CMD:+set}"
} > "$RUN_DIR/params.txt"

START_EPOCH="$(date +%s)"
FINALIZED=0
ABORT_REASON=""
TRUNCATED=0

# --- temperature probe args ------------------------------------------------
TEMP_ARGS=()
if [[ "$DRY_RUN" == "1" ]]; then
  TEMP_ARGS+=(--simulate)
else
  [[ -n "$ADB_SERIAL" ]] && TEMP_ARGS+=(--adb-serial "$ADB_SERIAL")
  [[ -n "$TEMP_URL" ]] && TEMP_ARGS+=(--url "$TEMP_URL")
  [[ -n "$TEMP_CMD" ]] && TEMP_ARGS+=(--cmd "$TEMP_CMD")
fi

read_temp() {
  local elapsed="$1" out
  if [[ "$DRY_RUN" == "1" ]]; then
    out=$("$PY" "$SCRIPT_DIR/probe_temp.py" --simulate --sim-elapsed "$elapsed" 2>/dev/null) \
      || out='{"temp_c": null, "source": "sim_error"}'
  elif [[ ${#TEMP_ARGS[@]} -gt 0 ]]; then
    out=$("$PY" "$SCRIPT_DIR/probe_temp.py" "${TEMP_ARGS[@]}" 2>/dev/null) \
      || out='{"temp_c": null, "source": "probe_error"}'
  else
    out='{"temp_c": null, "source": "none"}'
  fi
  printf '%s' "$out"
}

# --- health gate -----------------------------------------------------------
health_ok() {
  if [[ "$DRY_RUN" == "1" ]]; then return 0; fi
  "$PY" "$SCRIPT_DIR/node_probe.py" health --url "$BASE_URL" \
    --key-file "$KEY_FILE" --timeout "$HEALTH_TIMEOUT" >/dev/null 2>&1
}

# --- one generation --------------------------------------------------------
run_gen() {
  local regime="$1" index="$2" maxtok="$3" elapsed="$4"
  local metrics temp_json args
  args=(generate --url "$BASE_URL" --model "$MODEL" --max-tokens "$maxtok"
        --timeout "$REQUEST_TIMEOUT" --fill-prompt-tokens "$PROMPT_TOKENS"
        --regime "$regime" --sim-index "$index" --seed "$SEED")
  [[ -n "$KEY_FILE" ]] && args+=(--key-file "$KEY_FILE")
  [[ "$DRY_RUN" == "1" ]] && args+=(--simulate)

  if ! metrics=$("$PY" "$SCRIPT_DIR/node_probe.py" "${args[@]}"); then
    metrics='{"status": "error", "note": "probe_exit"}'
  fi
  temp_json="$(read_temp "$elapsed")"

  "$PY" "$SCRIPT_DIR/report.py" record --jsonl "$RAW_JSONL" \
    --metrics-json "$metrics" --temp-json "$temp_json" \
    --node "$NODE_TAG" --regime "$regime" --gen-index "$index" --elapsed-s "$elapsed"
}

# --- finalise on any exit --------------------------------------------------
finalize() {
  [[ "$FINALIZED" == "1" ]] && return 0
  FINALIZED=1
  local elapsed=$(( $(date +%s) - START_EPOCH ))
  log "elapsed ${elapsed}s - building reports"
  "$PY" "$SCRIPT_DIR/report.py" report --jsonl "$RAW_JSONL" \
    --csv "$RUN_DIR/bench.csv" --md "$RUN_DIR/summary.md" \
    --json "$RUN_DIR/summary.json" \
    --throttle-frac "$THROTTLE_FRAC" --throttle-window "$THROTTLE_WINDOW" \
    --baseline-samples "$BASELINE_SAMPLES" >&2 \
    || log "warning: report generation failed"
  log "artefacts in $RUN_DIR (bench.csv, summary.md, summary.json, raw.jsonl, params.txt)"
}
trap finalize EXIT

abort() { ABORT_REASON="$1"; log "ABORT: $1"; }

# --- regimes ---------------------------------------------------------------
run_burst() {
  if ! health_ok; then abort "health not ready before burst"; return; fi
  log "burst: 1 generation of ${BURST_TOKENS} tokens"
  run_gen burst 0 "$BURST_TOKENS" 0
}

run_sustained() {
  local start i elapsed now
  start=$(date +%s)
  i=0
  log "sustained: up to ${SUSTAINED_SECONDS}s of ${SUSTAINED_TOKENS}-token generations"
  while :; do
    now=$(date +%s)
    elapsed=$(( now - start ))
    if (( elapsed >= SUSTAINED_SECONDS )); then break; fi
    if (( now - START_EPOCH >= MAX_RUNTIME )); then
      TRUNCATED=1; log "sustained: total runtime budget reached"; break
    fi
    if ! health_ok; then abort "health not ready during sustained (generation $i)"; break; fi
    run_gen sustained "$i" "$SUSTAINED_TOKENS" "$elapsed"
    i=$(( i + 1 ))
    if (( INTER_GEN_SLEEP > 0 )); then sleep "$INTER_GEN_SLEEP"; fi
  done
  log "sustained: completed $i generations"
}

run_recovery() {
  local now remain idle
  now=$(date +%s)
  remain=$(( MAX_RUNTIME - (now - START_EPOCH) ))
  if (( RECOVERY_IDLE > remain )); then
    TRUNCATED=1
    idle=$(( remain > 0 ? remain : 0 ))
    log "recovery: idle clamped to ${idle}s by runtime budget"
  else
    idle="$RECOVERY_IDLE"
  fi
  log "recovery: idle ${idle}s, then one ${RECOVERY_TOKENS}-token generation"
  sleep "$idle"
  if ! health_ok; then abort "health not ready after recovery idle"; return; fi
  run_gen recovery 0 "$RECOVERY_TOKENS" 0
}

regime_selected() { case ",$REGIMES," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

log "phonon-bench starting: node=$NODE_TAG url=$BASE_URL dry_run=$DRY_RUN regimes=$REGIMES"
log "runtime ceiling ${MAX_RUNTIME}s; request timeout ${REQUEST_TIMEOUT}s"

if regime_selected burst; then
  (( $(date +%s) - START_EPOCH >= MAX_RUNTIME )) && { abort "runtime budget exhausted before burst"; } || run_burst
fi
if [[ -z "$ABORT_REASON" ]] && regime_selected sustained; then run_sustained; fi
if [[ -z "$ABORT_REASON" ]] && regime_selected recovery; then run_recovery; fi

if [[ -n "$ABORT_REASON" ]]; then
  log "finished with abort: $ABORT_REASON"
  exit 1
fi
if (( TRUNCATED == 1 )); then
  log "finished, but the run was truncated by the runtime budget"
  exit 3
fi
log "finished cleanly"
exit 0
