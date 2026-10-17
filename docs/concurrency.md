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

## Known issue

Setting `api_key` restarts the operator server. A `bind_address` change posted
immediately after gets `http=000` and is lost — the request races the restart.
Set the key, wait for `ready`, then set the bind address. Worth fixing in the
app: control requests should either wait for the new listener or return `503`
with a retry hint rather than dropping the connection.
