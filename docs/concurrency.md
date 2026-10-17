# Two phones, one gateway

Verified 2026-10-17 with both Pixel 7s serving on the LAN.

| Node | Device | Address | Key |
|---|---|---|---|
| `phone-a` | stock Pixel 7 | `10.0.10.125:8080` | per-phone, stored outside the repo |
| `phone-b` | GrapheneOS Pixel 7 | `10.0.10.195:8080` | per-phone, stored outside the repo |

Both run the same app, Qwen3.5-2B Q4_K_M at 32K context, bound to `0.0.0.0`
with a mandatory API key, screen held awake so the service stays in the
`top-app` cpuset. Neither is attached to the host for serving; USB was used only
to provision them.

## Concurrency

The same prompt (64 generated tokens) sent to each phone:

| Run | Wall |
|---|---|
| sequential, phone-a then phone-b | 5.81 s + 5.14 s = **10.95 s** |
| concurrent, one request to each | **4.46 s** total; each completed in ~4.45 s |

Decode during the concurrent run: **13.21 tok/s** on phone-a and **12.94 tok/s**
on phone-b. The wall time is the max of the two, not the sum: the phones ran in
parallel, and aggregate throughput is roughly double a single phone.

## Pooled routing

The pooled name `qwen3.5-2b` carries both deployments. Four sequential requests
returned two distinct `x-litellm-model-id` values, i.e. both phones served
traffic. Pinned names (`phone-a`, `phone-b`) remain the right choice for agent
tasks, where the KV prefix cache has to stay on one device.

## Resolved: settings race

Setting `api_key` used to restart the operator server, and a `bind_address`
change posted immediately after got `http=000` and was lost. Fixed on
`fix/settings-race` (2026-10-17): a key change no longer rebinds the listener,
multiple fields may be posted together, and control endpoints answer `503` with
`Retry-After: 1` while a reload is in flight. Observed run and HTTP codes are in
[`lan.md`](lan.md#settings-changes-never-drop-the-connection-2026-10-17).
Writing the key and the bind address in one POST is no longer required, but it
is still the fewest requests.

## Two agent tasks at once

The point of the pool: asynchronous work, one task per phone.

Two `opencode2` runs were started in the same second, one against
`litellm/phone-a` and one against `litellm/phone-b`, each asked to create a file
containing a unique marker in its own scratch directory.

| Task | Wall | Exit | File |
|---|---|---|---|
| phone-a | **209 s** | 0 | `PHONE_A_DONE` |
| phone-b | **173 s** | 0 | `PHONE_B_DONE` |

Both started at epoch `1792273375`; they finished at `1792273584` and
`1792273548`. The pair took 209 s end to end, not 382 s — the two agent loops
ran in parallel on separate phones, with LiteLLM as the only shared component.

Each task is dominated by prompt prefill: opencode's trimmed agent prompt is
about 3,000 tokens at roughly 20 tok/s. That is the number to attack next if
turnaround matters; it is not a decode problem.
