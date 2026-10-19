# Sustained load

A phone that serves an agent runs for hours, not for one request. This page
records what the reference Pixel 7 actually does over a long run, so capacity
numbers are not taken from short bursts.

## Method

Three regimes, in order, against the app's own OpenAI-compatible endpoint:

1. **Burst** — one 128-token greedy generation, the number quoted for
   latency-sensitive work.
2. **Sustained** — 192-token generations back to back for 15 minutes, no idle
   between them.
3. **Recovery** — five minutes of no requests, then one 128-token generation.

Every request returns `timings` from `llama-server`; decode is
`predicted_per_second`, prefill is `prompt_per_second`. Battery temperature is
sampled through `adb shell dumpsys battery` between generations. A generation
counts as throttled when decode sits at or below 80% of the sustained head for
two consecutive generations, after a one-generation warm-up.

The throttle baseline is the head of the sustained run, **not** the burst. A
one-off short generation is faster than any continuous rate on this hardware, so
measuring decay against the burst reports the difference between burst and
steady state as if it were heat.

## Result — Pixel 7, Qwen3.5-2B Q4_K_M, 32K context

Two threads pinned to cores 6–7, `GGML_CPU_REPACK=ON`, screen on, USB power.

| Regime | n | decode median | decode min–max | prefill median | battery °C |
|---|---|---|---|---|---|
| burst | 1 | 8.37 | — | 17.38 | 31.6 |
| sustained | 16 | 6.31 | 6.09–6.61 | 11.64 | 31.2–34.9 |
| recovery | 1 | 6.54 | — | 17.20 | 33.6 |

**No throttle event.** The 15-minute run declined from 6.56 (first three
generations) to 6.16 (last three) — about 6% — while battery temperature rose
3.7 °C. Decode never approached the 80% threshold.

## What it means

- **Burst is not sustainable.** Continuous serving runs at about three quarters
  of the burst rate on this phone. Quote the sustained figure for throughput
  planning and the burst figure for single-request latency.
- **The phone is not thermally limited at this load.** Fifteen minutes of
  continuous generation leaves the battery at 35 °C, far from any throttle
  point. The decline is gentle and tracks temperature; it is not a cliff.
- **Recovery does not restore the burst rate.** Five idle minutes returned
  6.54 tok/s, not 8.37, so the burst number reflects a short, cold, one-off run
  rather than a state the device can be brought back to by idling.

## 0.1.14 — runtime variants and the tuned profile

Re-run on 2026-10-19 with app 0.1.14 (runtime CPU kernel variants, sweep-chosen
profile: two decode threads unpinned, `-tb 4`), same phone, model and method:

| Regime | n | decode median | decode min–max | prefill median | battery °C |
|---|---|---|---|---|---|
| burst | 1 | **14.01** | — | 91.4 | 23.3 |
| sustained | 38 | **10.54** | 8.94–13.69 | 58.8 | 23.3–33.2 |
| recovery | 1 | **14.33** | — | 98.4 | 31.7 |

Battery temperature rose 9.9 °C over 874 s and stayed at 33.2 °C at its peak.
The harness reports a "throttle" at 398 s because its rule compares against 80%
of the sustained-head baseline; the phone was not hot, and the decline is the
steady state, not a thermal event. One agent-shaped turn — 2,251 prompt tokens,
128 generated — took **29.0 s** wall, with a 2,667 MB peak resident set.

These supersede the 6.31 tok/s sustained figure above, which was measured on a
build without dot-product kernels.
