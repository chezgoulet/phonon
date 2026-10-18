# phonon-bench

A repeatable sustained-load benchmark harness for the **Phonon** phone nodes —
two Pixel 7 phones serving `Qwen3.5-2B` at 32K context over the LAN:

| node    | base URL                    | key file            |
|---------|-----------------------------|---------------------|
| phone-a | `http://127.0.0.1:8080`      | `$HOME/.phonon-key` |
| phone-b | `http://127.0.0.1:8081`      | `$HOME/.phonon-key` |

Each node runs an OpenAI-compatible `llama.cpp` server (Vulkan backend on Mali)
and requires `Authorization: Bearer <key>`.

The harness measures a node in three regimes, samples per-generation metrics,
and writes a CSV plus a summary (median/min/max + time to first throttle).

> **Run this against one node at a time.** Both regimes and the concurrency
> lock assume a device is not being used by another build. The harness never
> runs parallel requests to a node.

---

## 1. Prerequisites

On the host that holds the phone (e.g. `sasquatch`):

- `bash` (4+), `python3` (3.8+, stdlib only) — no other Python deps.
- `curl` is *not* required (all HTTP is done by `python3`); it is optional.
- `adb` on `PATH`, if you want battery temperature via USB
  (`adb -s <serial> shell dumpsys battery`).
- The key file for the node, readable by the user running the harness.

`flock` (util-linux) is used for the per-node concurrency lock if present.

---

## 2. Quick start

```bash
# Cool-device burst only, with battery temperature from adb:
./bench.sh --node phone-a --regimes burst \
  --adb-serial 1A2B3C4D5E

# Full run: burst + 15 min sustained + 5 min recovery (≈21 min):
./bench.sh --node phone-a --adb-serial 1A2B3C4D5E

# phone-b, temperature from a probe URL instead of adb:
./bench.sh --node phone-b --temp-url http://192.0.2.10:9999/temp

# Temperature from an arbitrary command:
./bench.sh --node phone-a --temp-cmd 'ssh phone-a cat /sys/class/thermal/thermal_zone0/temp'
```

Artefacts are written to `results/<node>_<utc-stamp>/`.

### Validate locally, without a phone

```bash
./run_local_tests.sh                     # syntax + compile + dry-run + unit tests
./bench.sh --node phone-a --dry-run \
  --sustained-seconds 6 --sustained-tokens 32 \
  --recovery-idle 3 --burst-tokens 16 --outdir /tmp/phonon-demo
```

`--dry-run` performs **no network I/O and needs no key file** — it emits
deterministic synthetic metrics so the full pipeline (CSV, summary, throttle
detection, temperature sampling) can be exercised on a laptop.

---

## 3. The three regimes

| regime      | what it does                                              | why |
|-------------|-----------------------------------------------------------|-----|
| `burst`     | one generation (default **128 tokens**) from a cool device | latency-sensitive / interactive number |
| `sustained` | continuous generations (default **192 tokens**) for **15 min** | the number to plan throughput against |
| `recovery`  | idle **5 min**, then one burst generation                 | does thermal recovery actually recover? |

Regimes run in order (`burst,sustained,recovery`) and share one runtime budget
(`--max-runtime`, default 2400 s). Sustained stops as soon as its own duration
or the global budget is reached; recovery's idle is clamped to whatever budget
remains.

---

## 4. What is measured, per generation

Recorded for every generation:

| column              | meaning |
|---------------------|---------|
| `prefill_tok_s`     | prompt-processing rate (`timings.prompt_per_second`) |
| `decode_tok_s`      | generation rate (`timings.predicted_per_second`) |
| `wall_s`            | client-side wall time for the request |
| `temp_c`            | battery temperature sampled at generation time |

Prefill/decode come from the server's own `timings` block (see
`docs/measurement.md` in the `phonon-new` repo: the raw `llama-bench` loop has
been measured at roughly **2×** the real server's decode rate, so the server is
the source of truth). Each request sends `"cache_prompt": false` so prefill is a
real prefill and not a cache hit. If a server omits `timings`, decode falls back
to `completion_tokens / wall_s` and the row is stamped `source=estimated`.

**Temperature** is sampled through whatever probe you configured:

- `--adb-serial S` → `adb -s S shell dumpsys battery`, parsing `temperature:`
  (Android reports tenths of a degree, so `251` → `25.1 °C`).
- `--temp-url U` → `GET U`, first number in the body.
- `--temp-cmd C` → run `C`, first number in stdout.
- none → the `temp_c` column is left blank (allowed).

---

## 5. Outputs

`results/<node>_<stamp>/`:

- **`raw.jsonl`** — one JSON object per generation. The durable artefact; the
  CSV and both summaries are always derived from it, so an aborted run still
  yields a usable report of the rows that completed.
- **`bench.csv`** — same rows as a table:

  ```
  ts,node,regime,gen_index,elapsed_s,prompt_tokens,completion_tokens,
  prefill_tok_s,decode_tok_s,wall_s,temp_c,status,source,note
  ```

  `elapsed_s` is seconds since the *regime* started. `status` is `ok` or
  `error`; `source` is `server`, `estimated` or `simulated`.
- **`summary.md`** — human-readable: per-node, per-regime tables with
  **median / min / max** for prefill, decode, wall time and temperature, plus
  time-to-first-throttle and a *Suggested per-device defaults* table.
- **`summary.json`** — the same numbers, machine-readable.
- **`params.txt`** — the exact configuration used. It records the key-file
  **path** only; key contents are never written anywhere.

### Throttle detection

`time to first throttle` = the `elapsed_s` of the first sustained generation
whose `decode_tok_s` sits at or below `throttle_frac × baseline` (default
**0.8**) for `throttle_window` consecutive generations (default **2**), so a
single noisy sample does not trip it. The baseline is the **burst** median
decode when a burst was run, otherwise the median of the first
`--baseline-samples` sustained generations. If decode never drops below the
threshold inside the budget it reports `not_throttled`.

---

## 6. Using the results to set per-device defaults

The point of running three regimes is that a phone has **two honest numbers**,
and they are not the same number. Read them like this:

1. **Burst decode tok/s (median)** → the *interactive* figure.
   Use it for anything latency-sensitive: a chat turn, a single completion, a
   one-shot classification. This is the number you quote when someone asks
   "how fast is this phone for a quick question?". It is a cold-device number,
   so note that it will not hold under load.

2. **Sustained decode tok/s (median)** → the *throughput* figure.
   Use it for batch/serial work: how many tasks per minute, how long a queue
   will take, whether a node can absorb a stream of requests. The **median**
   describes the run; the **min** is your worst case and is what you should
   size a deadline against.

3. **Sustained floor (min) vs burst (median)** is a *consistency ratio*.
   - Ratio near 1.0 → the device holds its speed; you can budget sustained work
     at close to the burst number.
   - Large gap, with an early **time to first throttle** → the device heats and
     slows; budget sustained work at the median or the floor, not the burst,
     and treat the throttle time as the point after which the node should be
     given a rest or moved to burst-only duty.

4. **Recovery** tells you whether a rest helps. If the post-idle burst lands
   back near the original burst number, the node's thermal budget is
   recoverable and a duty-cycle schedule (e.g. N minutes on / M minutes off)
   will work. If the recovery burst stays low, the device is still hot and the
   idle period is too short for that node.

### Concrete workflow

1. Run the full harness against each node → `summary.md`.
2. From the *Suggested per-device defaults* table, copy for each node:
   - `burst decode median` → the node's **latency default**;
   - `sustained decode median` → the node's **throughput default**;
   - `sustained floor (min)` → the node's **worst-case bound**;
   - `time to first throttle` → the node's **duty-cycle period**.
3. Write those into the node manifest (`docs/measurement.md` convention:
   estimates are labelled; a cold number is labelled cold; a sustained number is
   labelled with its duration).
4. If the two nodes differ, that is a *finding*, not noise: set defaults
   **per device**, never one global number for "the phones".

A worked example of the shape (numbers are illustrative, not measured):

| node    | latency default (burst) | throughput default (sustained median) | worst case (sustained min) | rest after |
|---------|------------------------|---------------------------------------|----------------------------|-----------|
| phone-a | 14.6 tok/s             | 12.0 tok/s                            | 8.3 tok/s                  | ≈6 min    |
| phone-b | 13.1 tok/s             | 11.4 tok/s                            | 7.9 tok/s                  | ≈5 min    |

---

## 7. Thermal reality on these phones

- **llama.cpp Vulkan on Mali requires the screen to be ON.** With the screen
  off, Vulkan/GPU work is throttled to near-zero and the numbers are garbage.
  Before a run, keep the screen awake:

  ```bash
  adb -s <serial> shell svc power stayon true
  adb -s <serial> shell settings put global stay_on_while_plugged_in 7
  adb -s <serial> shell input keyevent KEYCODE_WAKEUP
  ```

- **Plug in / expect thermal steering.** Sustained GPU load on a Pixel 7 will
  eventually hit the thermal governor. That is exactly what the `sustained`
  regime and `time to first throttle` are for — do not "fix" it by waiting, it
  is the measurement.
- **Keep ambient conditions consistent** and note them (fan, room, case) in the
  manifest; the numbers are only comparable across runs taken the same way.
- A cold number is not a server number. The burst figure describes a cool
  device; the sustained figure describes the device you actually operate.

---

## 8. Safety design

| guarantee | how |
|-----------|-----|
| bounded total runtime | `--max-runtime` (default 2400 s) checked before every regime and every sustained generation; recovery idle is clamped to the remaining budget. Exit code `3` marks a truncated run. |
| no parallel requests to one node | strictly sequential loop (one request in flight) **and** an `flock` lock per node (`--node`-scoped) refusing a second concurrent invocation. Exit `2`. |
| stops if `/health` is not ready | the node is polled before burst, before every sustained generation, and after the recovery idle. A non-ready node aborts (exit `1`); completed rows are still reported. |
| never prints API keys | the key is read from its file by `node_probe.py` and placed **only** in the `Authorization` header. It is never echoed, logged, or written to CSV/JSON/`params.txt` (which record the key-file *path* only). Requests use `urllib`, so the key is never in a process argument list either. |
| per-request timeout | `--request-timeout` (default 180 s) so a wedged node cannot stall the run. |

Exit codes: `0` clean, `1` aborted (health/other), `2` usage or lock error,
`3` finished but truncated by the runtime budget.

---

## 9. CLI reference

```
Node:      --node phone-a|phone-b   --url BASE   --key-file PATH
Regimes:   --regimes LIST           --burst-tokens N   --sustained-tokens N
           --sustained-seconds N    --recovery-idle N  --recovery-tokens N
           --inter-gen-sleep N
Temperature: --adb-serial S   --temp-url U   --temp-cmd CMD
Limits:    --max-runtime N    --request-timeout N   --health-timeout N
Reporting: --outdir DIR   --throttle-frac F   --throttle-window N
           --baseline-samples N
Other:     --model ID   --prompt-tokens N   --seed N   --dry-run   --help
```

Defaults: burst 128 tokens, sustained 192 tokens for 900 s, recovery idle 300 s,
max runtime 2400 s, request timeout 180 s, health timeout 10 s, 512-token
filler prompt, `throttle_frac` 0.8, `throttle_window` 2, model `qwen3.5-2b`
(override with `$PHONON_MODEL` or `--model`).

---

## 10. Files

| file | role |
|------|------|
| `bench.sh` | orchestrator: CLI, presets, health gate, regime loop, runtime budget, lock, temperature sampling, finalisation |
| `node_probe.py` | `health` and one `generate` (real or `--simulate`); reads the key, never prints it |
| `probe_temp.py` | battery temperature via adb / URL / command / simulate |
| `report.py` | `record` rows to JSONL; `report` → CSV + `summary.md` + `summary.json` |
| `run_local_tests.sh` | syntax, compile, dry-run and throttle-detection checks — local only |
| `README.md` | this document |

## Notes

This is the harness that produced [`docs/sustained-load.md`](../../docs/sustained-load.md).
Two behaviours matter when reading results:

- the health probe accepts `{"state":"ready"}` as well as `{"status":"ok"}`;
- the throttle baseline is the head of the sustained run, not the burst, because
  a one-off short generation is always faster than continuous service on a phone.
