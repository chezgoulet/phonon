# Performance modes: visible and background

Measured 2026-10-17 on the reference Pixel 7 (`panther`, serial
`<device-serial>`), stock Android 17, no root, no kiosk (Device Owner was not
available on this phone). The visible mode was produced by keeping the activity
on screen; that is the same `top-app` cpuset kiosk pins. No reboot, no data
clear, no model change.

Model and server for every row:

- `Qwen3.5-2B-Q4_K_M.gguf`, 32,768 context, `-np 1 -fa on`, mmap
- app 0.4.0 (debug), `llama-server` child of the app
- screen on, phone on USB power, no fan
- same prompt, greedy (`temperature: 0`), `max_tokens: 128`,
  `ignore_eos: true`, non-streaming, through the app's own
  `/v1/chat/completions` proxy

## What changed

1. **Runtime profile.** The service reads `Cpus_allowed_list` from
   `/proc/self/status` and each core's `cpuinfo_max_freq`, then launches the
   child with the thread count, `--cpu-range`, `--prio 1` and `--prio-batch 2`
   shown below. A field the operator overrides stops auto-tuning for that
   field only.
2. **ADPF.** A `PerformanceHintManager` session is opened for the server's pid
   for the duration of each completion, with a target of one token at the last
   observed decode rate. Missing support is reported, never fatal.
3. **Avatar cost.** While a completion is in flight the avatar draws half the
   motes at 20 fps. Idle animation stays full. Off by default.
4. **Honest UI.** Settings has a Performance card and Serve has a mode line.

## The four cells

Two runs per cell, back to back, after one discarded warm-up. The baseline
cells use `-t 4` and no affinity (the pre-profile behaviour); the only extra
arguments are `--prio 0 --prio-batch 0`, which request normal priority and are
a no-op. Tuned cells use the profile exactly as launched by the app.

| Condition | Child cpuset | Args | Run 1 | Run 2 | Mean | Wall time | Battery |
|---|---|---|---:|---:|---:|---|---|
| background, baseline | 0–5 | `-t 4`, no range | 5.372 | 5.351 | **5.362 tok/s** | 24.1 / 24.2 s | 31.8 → 32.1 °C |
| background, tuned | 4–5 | `-t 2 --cpu-range 4-5 --prio 1 --prio-batch 2` | 5.601 | 5.508 | **5.555 tok/s** | 23.3 / 23.7 s | 32.8 °C |
| visible, baseline | 0–7 | `-t 4`, no range | 7.600 | 7.582 | **7.591 tok/s** | 17.1 / 17.3 s | 32.1 → 32.5 °C |
| visible, tuned | 6–7 | `-t 2 --cpu-range 6-7 --prio 1 --prio-batch 2` | 6.478 | 6.380 | **6.429 tok/s** | 20.1 / 20.4 s | 33.4 °C |

All runs generated 128 tokens. "Battery" is the `dumpsys battery` temperature
after the run, not junction temperature.

Controls observed for each cell: the app process was in `/foreground` (allowed
0–5) for the background rows and `/top-app` (allowed 0–7) for the visible rows;
the child inherited that set and then announced its own `Cpus_allowed_list` of
4–5 or 6–7 after `--cpu-range`. The `start` line in `/logs` shows the exact
argv. The X1 cores are present only in the visible rows.

### Variance

With two samples per cell:

| Condition | Spread | Spread / mean |
|---|---:|---:|
| background, baseline | 0.021 tok/s | 0.4% |
| background, tuned | 0.093 tok/s | 1.7% |
| visible, baseline | 0.018 tok/s | 0.2% |
| visible, tuned | 0.098 tok/s | 1.5% |

Decode is repeatable within a cell to about 1–2%. The differences between cells
are much larger than that. Battery temperature rose 0.3–0.6 °C per pair.

## Reading the numbers

- **Kiosk/visible is worth about 1.4× on sustained runs here**
  (7.59 vs 5.36 tok/s, +42%), and visible mode is worth about 1.2× over the
  best background mode (7.59 vs 5.56, +37%).
- **Background tuned is the best non-kiosk mode**, but only slightly:
  5.555 vs 5.362 tok/s, **+3.6%**. Two threads on the 2.35 GHz cluster beat
  four threads spread over 0–5; prefill got slower (7.1 vs 9.1 tok/s on the
  cached-prefix runs), because there are fewer threads to process the prompt.
- **Pinning two X1 threads in visible mode shows no clear gain over leaving
  four threads unpinned over all eight cores.** The matrix gave 6.43 vs 7.59,
  but two later single runs of the same tuned cell reached 7.20 and 7.70 tok/s
  (33.3 °C), so the difference is within the drift seen between sessions. The
  model is bandwidth-bound; two threads do not saturate it, and the unpinned
  scheduler can still place work on 4–7. This is why the Performance card
  reports the running values rather than promising a speed-up.
- **The 14.4 tok/s figure in earlier notes is a short-completion number, not a
  128-token number.** The same device returned 14.03 tok/s on a three-token
  "say hello" completion (`design/renders/README.md`); the older 14.36 figure
  came from a 1.19 s "Hello!" completion (`docs/lan.md`). A raw 8-thread run
  over 0–7 on this phone, with no app UI, reached 7.30 / 7.08 tok/s on the same
  128-token prompt, so ~7 tok/s is the sustained ceiling observed here, not 14.
  Two later single runs of the tuned visible cell reached 7.20 and 7.70 tok/s,
  which is why the UI quotes a 6.4–7.7 range for that cell. The UI says both
  things explicitly instead of choosing the flattering one.
- `--prio 1` and `--prio-batch 2` were **passed but refused by the kernel**:
  `warn: failed to set thread priority 1 : Operation not permitted (1)`.
  Raising nice/priority needs `CAP_SYS_NICE`, which an unprivileged app does
  not have. They are harmless and become effective only on a privileged
  build; the measured rows above did not get a priority boost.

### Guidance

- Use kiosk / keep the activity visible when throughput matters: it buys the
  X1 cores and ~1.4× on sustained generation.
- If the phone must stay locked in the background, the tuned profile is the
  best non-kiosk configuration: it keeps the whole generation on the 2.35 GHz
  cores and avoids spreading four threads across the little cores.
- Do not quote 14 tok/s as a sustained rate for this device and model.

## ADPF evidence

During a proxied completion, `/health` reported:

```json
{"requests_in_flight":1,"perf_hint_active":true,
 "perf_hint_target_ms":141.591787,"perf_hint_detail":"active"}
```

and logcat showed the session lifetime:

```
ADPF hint session open: tid=14927 target=200ms      # first request, no rate yet
ADPF hint session closed after 1 session(s)
ADPF hint session open: tid=14515 target=141ms      # 1 / 7.06 tok/s
ADPF hint session closed after 6 session(s)
```

`tid` is the `llama-server` pid. The target is derived from
`last_decode_tps` (200 ms until the first response is recorded), and the
session is closed in `requestFinished`, including on proxy failure. If
`createHintSession` is refused (some vendors restrict sessions to the calling
process), `/health` reports `perf_hint_active: false` and the reason in
`perf_hint_detail`; serving continues.

## Avatar CPU cost

App process CPU, sampled from `/proc/<app pid>/stat` (`utime+stime`, 100 Hz)
over 10 s. This excludes the `llama-server` child.

| State | Avatar | App CPU | Sample |
|---|---|---:|---|
| idle, serving, no request | full idle animation | **66.9%** | 10.1 s |
| idle, serving, no request (repeat) | full idle animation | **66.0%** | 8.1 s |
| generating | full (`Animate avatar while generating` on) | **66.3%** | 10.1 s |
| generating | reduced (setting off, default) | **19.6%** | 10.0 s |

The 19.6% row is a 128-token visible/tuned run at 6.478 tok/s; the 66.3% row
is the same state with the setting on (6.827 tok/s). The reduction is ~70% of
the avatar's cost while generating. Idle stays at ~66% by design: the setting
only applies to the generating state, and the avatar is restored to full
animation when the request ends.

### Decode cost (paired)

Throughput, not CPU: same model (Qwen3.5-2B), same prompt, 128 greedy tokens,
with the app on the **Serve** tab (avatar composed) against the **Settings** tab
(avatar not composed), alternating within one app session so drift affects both
arms equally.

| Session | Serve tab | Settings tab | Paired difference |
|---|---:|---:|---:|
| reduced (default: half motes, 20 fps while generating) | 6.44 tok/s | 6.63 tok/s | **-0.19 tok/s (-2.9%)** |
| full animation (setting on) | 5.97 tok/s | 6.72 tok/s | **-0.62 tok/s (-9.2%)** |

Six pairs per session. The absolute rates are lower than the standalone
benchmark because the device had been serving for about forty minutes and
settled at 34-35 C; the pairing is what makes the comparison usable. The default
therefore costs about 3% of decode and full animation about 9%, which is why the
setting ships off.

## Regression checks on the final build

| Check | Result |
|---|---|
| `GET /health` | 200, `"state":"ready"` |
| `GET /settings` without key | 401 |
| `GET /settings` with key | 200 |
| `POST /settings {"bind_address":"0.0.0.0","api_key":""}` | 400, `"bind_address requires api_key"` |
| settings after the refusal | 200; key still set, bind unchanged |
| `GET /v1/models` without key / with key | 401 / 200 |
| completion through `/v1/chat/completions` | 200, 128 tokens, non-empty content |

The API key was not changed by the tests and is not printed here. No model file
was added, removed or renamed.

## Reproduce

Use the app's own proxy, with the key in a shell variable that is never echoed:

```bash
KEY=$(cat /tmp/phone_a_key)
curl -sS -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.5-2b","messages":[{"role":"user","content":"<fixed prompt>"}],
       "max_tokens":128,"temperature":0,"ignore_eos":true,"stream":false}' \
  http://127.0.0.1:18080/v1/chat/completions
```

Read the rate from `timings.predicted_per_second`, not from the wall clock.
For the background cells press Home and restart the server so the new child
inherits `/foreground`; for the visible cells bring the activity forward and
restart it. Verify with `Cpus_allowed_list` in `/proc/<pid>/status` before
trusting a row.

Screenshots: `design/renders/app-serve-performance.png` (Serve mode line and
7.6 tok/s last rate), `design/renders/app-settings-performance.png`
(Performance card in visible mode) and
`design/renders/app-settings-performance-background.png` (the same card for a
child launched in background, including the "Apply or restart" warning).

## Repacking: the real lever (2026-10-18)

Decode on this phone is bandwidth-bound, so the optimization that matters is the
one that changes how weights are read, not how many cores read them. llama.cpp's
ARM **online repacking** rearranges quantized weights in memory for friendlier
access patterns and explicitly aims at effective bandwidth.

The earlier build (llama.cpp `c0fd786`) compiled with `GGML_CPU_REPACK=ON` but
contained repack kernels only for **Q4_0** and **Q1_0**. Qwen3.5-2B is Q4_K_M,
so it went unrepacked. Gemma 4 E2B, which measured 11–12 tok/s earlier, was
Q4_0 — it was being repacked.

Rebuilding at llama.cpp **`797de63`** with `GGML_CPU_REPACK=ON` includes Q4_K
repack (87 symbol references in `libggml-cpu.so`). Same phone, 32K context,
`-t 2 --cpu-range 6-7`, 128 greedy tokens:

| Engine | Model | Decode |
|---|---|---|
| `c0fd786` | Qwen3.5-2B Q4_K_M | 5.5–5.9 tok/s |
| `797de63` (raw server) | Qwen3.5-2B Q4_K_M | **9.20 / 8.84** tok/s |
| `797de63` (raw server) | Qwen3.5-2B Q4_0 | **9.47** tok/s |
| `797de63` (through the app) | Qwen3.5-2B Q4_K_M | **8.59 / 8.25** tok/s |

A **~50–60% improvement with no quantization change**, and Q4_0 versus Q4_K_M is
now within noise — so keep Q4_K_M and its better quality.

Requirement for any future build: `GGML_CPU_REPACK=ON` **and** a llama.cpp
revision that carries the Q4_K repack kernels. The engineering sample that
preceded this measured 5.5 tok/s precisely because it lacked them.

## Speculative decoding: a negative result (2026-10-18)

Tried a 0.8B Qwen3.5 draft against the 2B target, same engine (`797de63`), same
prompt, `--spec-type draft-simple --spec-draft-n-max 4`:

- draft acceptance **0.361** (56 accepted / 155 generated), mean accepted run 2.44
- decode **2.51 / 2.43 tok/s** against a 9.0 tok/s baseline

The draft model's own forward passes cost more than the verification passes they
save, because every pass reads weights from the same memory bus. On this class
of device a standalone draft is a loss. Not shipped. A tiny model-native MTP
head (tens of megabytes, not hundreds) is the only version of this worth
retrying, and llama.cpp has no Qwen3.5 MTP path at this revision.

## Gemma 4 as a second family (measured)

Same engine, 2 threads on cores 6-7, 128 greedy tokens:

| Model | Context | Decode |
|---|---|---|
| Qwen3.5-2B Q4_K_M | 32K | **9.20 / 8.84 tok/s** |
| Gemma 4 E2B Q4_0 | 16K | **7.05 / 6.74 tok/s** |

Qwen is the faster default at equal tool-calling support; Gemma 4 is worth
offering for its multimodal path and its own tool format, with the measured cost
stated in the picker.

The catalog, download provenance and RAM-tier recommendations are in
[`models.md`](models.md). The device acceptance run for both families, including
the `get_weather` tool calls, is in [`models-test.md`](models-test.md).
