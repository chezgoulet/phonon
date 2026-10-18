# Phonon

**Old phones as inference servers for local agentic AI.**

Phonon turns spare Android phones into a private, OpenAI-compatible inference
pool on your LAN. The pool's parallelism is the number of phones. Adding a phone
adds a worker, not speed: a two-phone pool runs two tasks at once, each at one
phone's pace.

This is a ground-up rebuild. The previous Phonon (2026) was a broad project with
a Go coordinator, an NPU story and an e-waste framing. It is retired. What
survives is the measurement discipline and a small amount of code.

## Status: pre-alpha

**2026-10-17 — app v0.** The app in `app/` now packages `llama-server`,
downloads a GGUF into app storage, supervises the server from a foreground
service and serves an OpenAI-compatible API on the phone's loopback interface.
The first on-device completions and a tool call are recorded in
[`docs/app.md`](docs/app.md). The measured decode rate in that run was
5.1–5.3 tok/s, below the spike baseline; the cause is not yet established and
is documented rather than averaged away. Kill/restart and thermal-pause
acceptance were blocked by the phone locking before they ran; see the same
document.

This is the first inference run under the repository's name, on one of the two
Pixel 7s. The remaining milestone is a repeatable measurement on both units,
with the supervisor and thermal paths exercised.

Nothing in this repository claims a speed that has not been measured on the
device it applies to. Device files carry `null` until someone runs the script.

## The shape

```
opencode (host)  ->  LiteLLM gateway  ->  phone-a:8080
                                      ->  phone-b:8080
```

- **Phone** — an Android app running llama.cpp as a foreground service, one
  model, one slot, an OpenAI-compatible endpoint on the LAN. It knows nothing
  about agents or sessions.
- **Gateway** — LiteLLM. One URL, one key, routing, retries, timeouts, metrics.
  This replaces the old Go coordinator entirely.
- **Worker** — opencode on a host. The harness, the session, the tools, the git
  work. It points at the gateway, never at a phone directly.

The full reasoning, including the two traps that decide whether this works, is
in [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Hardware assumptions

Phones run continuously, **plugged into USB power with wired Ethernet**. They
are appliances, not handhelds: airflow, no case, no stacking. Battery health is
a safety and longevity requirement, handled primarily by the host OS (Pixel
Charging optimization, 80% limit) and by thermal-aware scheduling — see
[`docs/battery.md`](docs/battery.md).

## Supported devices

| Device | Codename | Status |
|---|---|---|
| Google Pixel 7 | `panther` | owned; measurements pending |

Community device support is data, not prose: one manifest per device, with
measured numbers produced by the repository's own script. See
[`devices/`](devices/) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## What this is not

- Not a donation or e-waste programme. No credits, no marketplace.
- Not distributed inference. A model runs whole on one phone; phones add slots.
- Not a training cluster.
- Not NPU-accelerated. The phone NPU is measurably not faster for LLM decode
  (it shares the memory bus); see [`docs/evidence.md`](docs/evidence.md).

## Roadmap

The app is the product. The stripped-ROM idea is on the back burner: Device Owner
kiosk mode delivers the same operational discipline through policy, without
unlocking bootloaders or breaking verified boot. See
[`docs/scope.md`](docs/scope.md) for the decision and its consequences.

1. **App (now).** Compose UI, a particle avatar as the state indicator (six
   idle shapes, chosen in Settings), hardware detection and auto-config,
   benchmark mode, device report export.
2. **Kiosk deployment.** Device Owner, Lock Task Mode, battery exemption, plus
   provisioning docs and QR provisioning for bulk — see
   [`docs/kiosk.md`](docs/kiosk.md).
3. **Distribution.** Obtainium now; F-Droid main repo once reproducible builds
   and the metadata are ready. One signing key for both.
4. **Sustained-load tuning.** Per-device thermal profiles (burst vs sustained).
5. **Optional, later.** A stripped GrapheneOS image as a reference
   implementation — never a prerequisite for shipping.

Hard constraints that shape the app: Mali GPUs park when the app is backgrounded
or the screen turns off, so the app holds a screen-on wake lock while serving.
CPU-only is the fallback when the screen must be off.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
