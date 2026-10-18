# Benchmark mode

A benchmark is one fixed 128-token request sent through the app's own
`/v1/chat/completions`, measured from the app process. It is the number to quote
for the product, because it includes the app proxy, the `llama-server` child and
whatever CPU set Android placed that child in. It is not a `llama-bench` number.

Run it from **Serve → Run benchmark** while the server is ready. The screen stays
usable, generation continues for normal clients, and **Cancel** aborts the
request. If the server stops or enters a thermal pause mid-run, the benchmark
aborts with `cancelled: server stopped` and stores nothing.

## Method

| Item | Value |
|---|---|
| Prompt | fixed prose question, `temperature: 0` |
| `max_tokens` | 128 |
| Stream | SSE (`stream: true`, `stream_options.include_usage: true`) |
| Endpoint | `http://<app host>:<app port>/v1/chat/completions` |
| Auth | the configured API key, if set |
| Sampling | child `VmRSS` every 200 ms during the request |

The request is counted as a normal client request, so keep-warm beats do not
start while it runs.

## Fields

| Field | Meaning |
|---|---|
| `timestamp` | UTC start time, ISO 8601 |
| `load` | `cold` = first benchmark against this model load; `warm` = another run against the same server process |
| `model`, `alias` | file name and OpenAI model name |
| `prompt_tokens` | prompt tokens reported by `llama-server` |
| `completion_tokens` | generated tokens, normally 128 |
| `prefill_tps` | prompt processing rate from the response `timings.prompt_per_second` |
| `decode_tps` | generation rate from `timings.predicted_per_second`; the headline number |
| `ttft_ms` | wall time from request start to the first streamed content token |
| `total_ms` | wall time for the whole request |
| `peak_rss_mb` | highest sampled resident set of the `llama-server` child during the request |
| `battery_temp_c` | battery temperature at completion |
| `context`, `threads`, `load_mode` | settings the server was running with |
| `server_pid` | child process the run used |
| `app_version` | app version that produced the row |

The last ten runs are stored in the settings store. The Serve card shows the
newest; the device report includes all stored runs.

## What the numbers mean

- **`decode_tps` is the throughput that matters** for agent work. Prefill is a
  one-time cost per new prefix; decode is paid for every output token.
- **`prefill_tps` and `ttft_ms` include prompt-cache reuse.** The second run of
  the same fixed prompt can reuse the KV prefix, so its TTFT drops sharply while
  its prefill rate reflects only the uncached tokens. That is a real serving
  effect, not an error: treat cold and warm as different measurements.
- **`peak_rss_mb` is the child's working set, not the model file size.** mmap'd
  weights count as resident only when touched. It is sampled, so a very short
  spike between samples can be missed.
- **Battery temperature is the phone's sensor**, not the SoC junction. The app's
  thermal policy also uses this sensor.
- **Screen state and cgroup change the result.** A foreground service can land
  in `/foreground` (cores 0–5 on a Pixel 7) while a visible activity lands in
  `/top-app` (cores 0–7). Keep the screen on, as the app does while serving.

## Measured on the Pixel 7 — 2026-10-17

Stock Pixel 7 (`panther`, 7.83 GB detected, 8 GB tier S), Android 17, app 0.3.0,
Qwen3.5-2B Q4_K_M, 32K context, 4 threads, mmap, screen on, phone at 28 °C.

Two runs back to back against the same model load:

| # | Load | Prompt tok | Decode tok/s | Prefill tok/s | TTFT | Peak RSS | Battery |
|---|---|---|---|---|---|---|---|
| 1 | cold | 54 | **5.92** | 16.23 | 3349 ms | 1760 MB | 28.0 °C |
| 2 | warm | 54 | **7.01** | 6.46 | 662 ms | 1801 MB | 28.3 °C |

Variance: decode +18% and TTFT −80% between the two runs. The decode difference
is run-to-run noise on the same governor and memory state; the TTFT difference is
prefix-cache reuse. A single run is not a device rating. The raw rows are in
`docs/reports/pixel-7-benchmarks.json`.

Earlier standalone and in-app measurements, including the 2.4× cpuset penalty,
are in [`model-tiers.md`](model-tiers.md). Do not compare those standalone
numbers to this table: they were not produced by this benchmark.

## Not measured

- Sustained runs longer than one 128-token request. Thermal throttling over
  10–15 minutes is still an open item in [`scope.md`](scope.md).
- Tier M (12 GB) and tier L (16 GB) devices. No such device has run this
  benchmark.
