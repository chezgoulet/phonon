# Phonon

**Old phones as inference servers for local agentic AI.**

Phonon turns spare Android phones into a private, OpenAI-compatible inference
pool on your LAN. The pool's parallelism is the number of phones. Adding a phone
adds a worker, not speed: a two-phone pool runs two tasks at once, each at one
phone's pace.

This is a ground-up rebuild. The previous Phonon (2026) was a broad project with
a Go coordinator, an NPU story and an e-waste framing. It is retired. What
survives is the measurement discipline and a small amount of code.

## Status: v0.1.15

The app packages `llama-server` (built from the pinned llama.cpp submodule at
compile time), downloads a GGUF into app storage, supervises the server from a
foreground service and serves an OpenAI-compatible API. On the reference Pixel 7
with Qwen3.5-2B Q4_K_M at 32K context it measures **14.01 tok/s** for a burst and
**10.54 tok/s** sustained across thirty-eight generations over fifteen minutes,
and one agent-shaped turn — a 2.3K prompt plus 128 generated tokens — in **29.0 s**.
The method and the numbers are in [`docs/performance.md`](docs/performance.md) and
[`docs/sustained-load.md`](docs/sustained-load.md).

Five model families ship with measured rates: Qwen3.5, Gemma 4, MiniCPM5,
Granite and Nemotron. Every one returns structured `tool_calls` on device.
Kiosk, thermal pause and resume, an on-device benchmark and device-report export
are implemented; the Device Owner path needs a device with no accounts
configured and says so when it cannot be used.

Released: **v0.1.15** — see the releases page, where every release carries the
APK and its `SHA256SUMS`. Not yet done: a second phone has not been measured,
`watts` needs a USB power meter, reproducible builds are unproven, and the
F-Droid metadata is not written. Those are tracked in
[`docs/FDROID-GAPS.md`](docs/FDROID-GAPS.md).

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
| Google Pixel 7 | `panther` | owned; measured — 14.01 tok/s burst, 10.54 sustained, 29.0 s agent turn |

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
