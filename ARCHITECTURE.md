# Architecture

Status: app v0 is built and was exercised on a Pixel 7 (completions and a tool
call); see [`docs/app.md`](app.md). The rest of this document is the agreed
architecture and its rationale.

## Three layers

**Phone — inference only.** One model, one slot, an OpenAI-compatible endpoint
on the LAN, a foreground service so Android does not freeze it. No agents, no
sessions, no git, no task queue.

**Gateway — LiteLLM.** The registry of phones. One deployment per phone, one
URL and one key for consumers, routing, retries, timeouts, metrics. Replaces the
old Go coordinator (discovery, health, grouping, load balancing) with
configuration.

**Worker — opencode on a host.** The harness. It points at the gateway.

Parallelism is the number of phones. Throughput per task is one phone's decode
speed. Two phones mean two simultaneous tasks, not a faster task.

## Decision 1: two kinds of model name, because of the KV cache

`llama-server` caches the KV prefix per slot. An agent's system prompt and tool
schemas are about 2.8K tokens for a trimmed toolset; the first turn prefills
them and every later turn reuses them. That reuse is most of what makes a slow
phone usable.

Round-robin across phones destroys it. So the gateway exposes:

- `phone-a`, `phone-b` — pinned, one deployment each. Agent tasks use these.
- `gemma-4-e2b` — pooled, all deployments. One-shot calls only, where there is
  no prefix worth preserving.

LiteLLM has session affinity (`optional_pre_call_checks: ["session_affinity"]`,
keyed on `x-litellm-session-id` or `metadata.session_id`), which is the cleaner
mechanism when the client can send a stable id. opencode does not by default, so
pinning is the reliable path today.

## Decision 2: LiteLLM's defaults are wrong for this hardware

The router assumes cloud latency. A phone can take minutes to first token and
half an hour on one task. Unattended defaults will time out healthy requests,
count them as failures, and cool the phone down — removing working capacity.

Explicit settings: timeouts in the thousands of seconds; no retries on agent
calls (a retry re-runs an expensive prompt); cooldowns long enough not to flap;
and health checks that do not masquerade as completions, because a phone busy
generating is not a phone that is down.

## Decision 3: context is the binding constraint, not speed

Measured with opencode 1.18.13: the default request carries ten tools and costs
about 4.4K tokens before the task text. A trimmed agent — a short system prompt
and four tools (bash, edit, read, write) — costs about 2.8K. On an 8K window
that leaves roughly 5.2K for the task and its turns.

Consequences: one file per task, no repository exploration, and a fresh session
per task. The agent searches through bash because `glob` and `grep` schemas cost
more context than they are worth at this size.

## Decision 4: the app is the product; kiosk mode replaces the ROM

Phonon ships as an Android app under Apache 2.0, run as a Device Owner in Lock
Task (kiosk) mode. The stripped-GrapheneOS ROM is on the back burner
indefinitely.

Kiosk mode provides what the ROM was for: no competing apps, no OS interference
with the server process, battery-optimisation exemption, and a pinned foreground
app. It does that without bootloader unlocks, AVB signing, vendor blobs or a
per-device porting treadmill — and community contributors can work on an app and
docs, not device trees.

One hardware constraint is not solved by policy: on Mali GPUs the Vulkan backend
parks when the app is backgrounded or the screen turns off, and inference
crashes. The app therefore holds a screen-on wake lock while serving; kiosk apps
stay visible by design. Screen-off serving falls back to CPU.

## Decision 5: battery health is an OS job

Phones stay plugged in permanently. On stock Android an unprivileged app cannot
control charging; the platform owns the charger. What exists is Pixel Charging
optimization with an 80% limit, plus thermal-aware scheduling by the app. Active
discharge/recharge cycling requires root, a switchable power source, or the ROM —
and it may not be the right goal at all, since a cycle costs battery life. The
metrics worth optimising are cell temperature and hours above 80% state of
charge. See [`battery.md`](battery.md).

## Decision 6: device support is a measurement, not an assertion

One manifest per device with SoC, RAM, model, quant, thread count, context size,
and measured prefill/decode cold and after fifteen minutes. A PR without
measured numbers is not accepted. The measurement script ships here so the
numbers are reproducible.

## Failure modes we expect

- **Thermal throttling under 24/7 load.** Expect 60–70% of the cold number at
  steady state. Design the queue for throughput, not latency.
- **Android killing the server.** Foreground service, wake lock, battery
  optimization exemption, phantom-process limit; and a host-side health check
  that stops the batch rather than blaming the task.
- **A model too weak for the task.** The runner reverts on a red check. A failed
  task costs time, never a broken tree.
- **Gateway eviction of a slow phone.** See Decision 2.
