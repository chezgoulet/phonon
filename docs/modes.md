# Modes: what each way of serving buys, and what it costs

Decision, 2026-11-19.

Phonon's job is to give people tools. That means more than one way to serve a
model — one phone alone, a drawer of phones behind a router, a phone plus a PC —
and it means each of those ways says what it costs before it runs. We do not
pick the topology for the user and we do not call one of them best. We price
them and let the operator choose.

## The rule: a mode states its cost before it runs

Every mode must answer three questions on the screen that starts it:

1. **What does this cost?** Tokens per second, and whatever else the mode spends
   — latency, memory, quality, a wire.
2. **Is that number measured, predicted, or unknown?** Measured on this device,
   predicted from this device's bandwidth, or unmeasured with a range and the
   reason.
3. **What would change the answer?** A wired link instead of Wi-Fi, one fewer
   node, a smaller model, a shorter context.

The catalog already works this way — `unmeasured` is shown verbatim and no
number is inferred from the row above it (`docs/models.md`). Modes inherit that
convention. **A mode that cannot state its cost does not ship.**

## The constant that prices a mode

Generation streams the resident weights once per token, so
`gen_tok_s × resident_bytes` is roughly constant per device. Call it effective
bandwidth. Measured on the reference phone:

| Device and profile | Model | Decode | Effective bandwidth |
|---|---|---:|---:|
| Pixel 7, 2 threads pinned to cores 6-7, `GGML_CPU_REPACK=ON` | Qwen3.5-2B Q4_K_M 1.28 GB | 9.20 tok/s | **11.8 GB/s** |
| Pixel 7, same profile | Qwen3.5-4B Q4_K_M 2.74 GB | 4.13 tok/s | 11.3 GB/s |
| Pixel 7, same profile | MiniCPM5-2B, Granite 4.2 3B, Nemotron 3 Nano 4B | 6.82 / 4.41 / 3.57 | 10.6 / 9.9 / 10.1 GB/s |
| Pixel 7, shipped profile: 2 threads unpinned, `-np 1 -fa on`, mmap | Qwen3.5-2B Q4_K_M, 32K | 14.01 tok/s burst | **17.9 GB/s** |
| Pixel 7, same profile | as above, sustained median | 10.54 tok/s | 13.5 GB/s |
| Xiaomi Mi 8 (Snapdragon 845), measured by PhoneBorg | Qwen2.5-0.5B / 1.5B | 14.8 / 6.8 tok/s | 7.3 / 7.6 GB/s |

Two things follow, and both are inconvenient.

**The constant is per device *and* per profile.** The same phone runs the same
model at 9.20 and at 14.01 tok/s — a 52% swing from thread pinning, flash
attention and build flags alone. So a self-test has to run the flags the serve
will actually use, or the prediction is wrong by half. This is a bigger lever
than model choice in the 1–2B band.

**Sparse models need resident bytes, not the file.** Gemma 4 E2B appears to run
at ~20 GB/s because it does not stream its whole file per token. Feed the file
size in and every MoE comes out pessimistic by roughly two. PhoneBorg hit the
same wall independently: Gemma 3n E2B predicts 4.8 tok/s from a 1446 MiB
resident set and 2.4 from the 2886 MiB file. **Rule: resident bytes for dense,
active-parameter bytes for sparse, file size for neither.**

Tiers, adopting PhoneBorg's boundaries so a phone measured by either tool reads
the same: `t1` < 4, `t2` 4–10, `t3` 10–25, `t4` ≥ 25 GB/s. The Pixel 7 is `t3`
under both profiles; the Mi 8 is `t2`.

## The modes

| Mode | Documented | Buys | Costs | Status |
|---|---|---|---|---|
| **Single** — one phone, one model | `docs/scope.md`, `docs/models.md` | Lowest latency; no network, no scheduler, no wire | Capped by one phone's RAM; holds a screen-on wake lock because the Mali driver parks the GPU when the screen sleeps | **Shipped; measured** on Pixel 7 |
| **Pool** — whole model per phone, router in front | `docs/multi-node.md`, `docs/gateway-registration.md` | Parallel throughput: N agents on N phones, prefix-cache affinity per agent, one URL and one key | Quality, not speed: a node below a task's floor must be excluded, not deprioritised. No failover today (`max_retries: 0`). Agent turns must be pinned, never pooled | **Written and registered**; one phone measured, two-phone configuration **unverified** |
| **Split** — llama.cpp RPC layer shard | this document; not implemented | A model that fits nowhere alone — a 4-bit MoE across two phones | Every token crosses every node in sequence, so it can only ever be slower than the same model on the biggest node that holds it. Needs a wired link | **Unmeasured** on our hardware |
| **Job split** — subtasks to different phones | not designed | Wall-clock on parallel subtasks | Same quality caveat as the pool, plus partial results and a bigger failure surface | **Not started** |

Measured elsewhere, for pricing rather than for bragging: gpt-oss-20b MoE runs
2.6–2.8 tok/s across two phones; dense 24–32B runs 0.3–1.2 tok/s; and a 30B that
does 7.0 tok/s on one PC drops to 3.9 split (PhoneBorg, DistAi respectively).
Pinning the output layer on the coordinator took one cluster from 3.5 to 5.3
tok/s (DistAi).

**Mixed fleets are not a fifth mode.** A PC joins the pool as another
OpenAI-compatible endpoint with its own tier, or joins a split as an RPC worker.
The mechanisms are the same two; only the node list changes.

## What we will not claim

- **That split mode is faster than one big node.** It is a pipeline. Adding
  phones adds memory, not speed, and a single conversation is never faster than
  the fastest device that could hold the whole model.
- **That spreading work across phones improves quality.** It improves wall clock
  and can lower quality: three agent tasks finished in 9.9 s spread over three
  phones against 23.8 s queued on one, but the pool answered 1 of 3 correctly
  and the single phone 3 of 3, because the pool included 0.5B nodes.
- **That a predicted number is a measured one.** Every prediction carries its
  label, and where no device has been measured — a Pixel 9, any SoC we have not
  run — the app shows a range and says why.
- **That an unmeasured row is evidence.** Already the catalog's rule.

## Security, stated plainly

llama.cpp's RPC backend is upstream-labelled a proof of concept, "fragile and
insecure", with no authentication and no encryption. Split mode therefore binds
to loopback plus adb forward, or terminates TLS in our own gateway, and is
documented as trusted-LAN only. Pool mode's router is our own surface and takes
the same rule: keys per node, no RPC exposed to a network we do not own.

## What each mode needs before it can be honest in the UI

- **All modes:** a self-test at first serve that measures effective bandwidth
  with the profile about to be used, recorded in `devices/<soc>.yaml`, and a
  prediction per catalog model computed from it. After a real run, measured
  sits next to predicted and the delta is kept so the prediction can be
  corrected rather than trusted.
- **Pool:** tier per node in the gateway listing, so the picker says "fits, and
  predicts X tok/s — below this task's floor" instead of only checking RAM.
- **Split:** measured per-hop latency between the paired nodes, the predicted
  rate shown before start, and a refusal on Wi-Fi unless the operator confirms.
- **Job split:** a quality floor per task class, and node eligibility derived
  from it.

## Sources

- `docs/models.md` — catalog decode measurements, Pixel 7, 2026-10-17.
- `devices/panther.yaml` — shipped-profile measurements, Pixel 7, 2026-10-19.
- `docs/scope.md` — Mali/GPU screen-state constraint and RAM tiers.
- `docs/multi-node.md`, `docs/gateway-registration.md` — pool mode as built.
- PhoneBorg (`github.com/dzaczek/phoneborg`, Apache-2.0) — effective-bandwidth
  tiers, Mi 8 measurements, pool-versus-single agent timings.
- DistAi (`github.com/alex8819/DistAi`, MIT) — RPC split costs, output-layer
  pinning, weighted split planner.
