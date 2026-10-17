# Scope: the app is the product

Decision, 2026-10-17.

Phonon ships as an **Android app**, distributed under Apache 2.0, run as a
**Device Owner in kiosk mode**. The stripped-GrapheneOS ROM is on the back
burner indefinitely: it was a performance idea, and policy now solves it. If a
ROM ever happens, it is a reference implementation, not the product.

## Why kiosk mode replaces the ROM

The ROM's promise was performance through subtraction. Device Owner mode
achieves the same through policy:

- **Lock Task Mode** pins the server app and blocks escape into settings or
  competing apps.
- **Battery optimisation exemption** stops Android killing the inference service.
- `DISALLOW_SAFE_BOOT` and `DISALLOW_FACTORY_RESET` keep the appliance stable.
- Update and bloatware popups can be suppressed.

What is given up: kernel-level governor tuning and hard-coded service
persistence. What is gained: device-agnostic deployment, supported security
patches, no AVB signing burden, and no per-device porting treadmill. Community
scaling depends on this — people contribute to apps and docs, almost never to
device trees and vendor blobs.

## Hard constraint: Mali GPU and screen state

Tensor G2 (Pixel 7) and G3 (Pixel 9) use Mali GPUs. The llama.cpp Vulkan backend
has a known power-management failure: when the app is backgrounded or the screen
turns off, the driver parks the GPU, inference hangs, and the process crashes.
Observed rates: ~0% foregrounded with the screen on, ~50% backgrounded with the
screen on, ~100% backgrounded with the screen off.

**Consequence:** the app must hold a screen-on wake lock while serving. Kiosk
mode makes that natural because kiosk apps are designed to stay visible. If
serving with the screen off is ever required, the only stable fallback is
CPU-only inference at a significant throughput cost. This is a driver-level
behaviour; no amount of ROM stripping fixes it.

## Hardware tiers

Two reference devices, chosen for RAM-tier and thermal diversity:

| Device | SoC | RAM | Realistic ceiling |
|---|---|---|---|
| Pixel 7 | Tensor G2 | 8 GB | Qwen3.5-4B Q4_K_M, 32K context |
| Pixel 9 | Tensor G3 | 12 GB | Qwen3.5-8B Q4, 48–64K context |

The Pixel 9 is the better long-term reference: Pixel 7 security support ends
October 2027, Pixel 9 runs to roughly 2030. State this plainly so early adopters
buy the right hardware.

The app must **auto-detect the RAM tier** and select model, quantization,
context size and parallel slots accordingly. Our two devices exercise that logic
before any community member touches it, and it is the single highest-leverage
feature for adoption.

## Provisioning (no root)

1. No Google accounts, no secondary users (accounts can be removed without a
   factory reset).
2. Install the APK.
3. `adb shell dpm set-device-owner org.phononai.app/.DeviceAdminReceiver`

Bulk deployment later uses **QR provisioning**: a payload scanned during initial
setup installs the app and sets Device Owner. Provisioning scripts and per-OEM
battery-exemption documentation (Xiaomi, Samsung, OnePlus all differ) are
high-value community contribution targets.

## Distribution

Two channels, one signing key:

- **Obtainium** via GitHub Releases — primary, available immediately.
- **F-Droid main repo** — target. No IzzyOnDroid intermediate step.

Reproducible builds from day one. This is what makes the two channels
compatible: one signing key across GitHub and F-Droid means users can move
between them without uninstall/reinstall, and there is no signature-mismatch
support burden. F-Droid's default is to sign with their key; reproducible builds
let us keep ours.

F-Droid requirements to plan for:

- Builds entirely from source on their build server. **No prebuilt `.so`
  files** — llama.cpp and ggml must compile inside their environment with a
  pinned NDK version and pinned submodule commits in the metadata YAML. This is
  the most common first-submission rejection for NDK-heavy apps; validate
  locally with `fdroid build` before opening the MR.
- **Anti-feature flags will apply** — expect DeviceAdmin from the
  `BIND_DEVICE_ADMIN` receiver. Document the rationale in the listing rather
  than treating it as a problem.
- Model licensing is clean: Qwen3 is Apache 2.0 and Hugging Face is FOSS, so
  runtime model downloads do not trigger NonFreeAssets.
- Review cycles are measured in weeks. Open the MR early, even as a placeholder;
  Obtainium carries users in the meantime.

Licensing: Apache 2.0 is F-Droid-compatible, carries no anti-feature flag and
adds a patent grant. Ship a `NOTICE` enumerating bundled components and their
licences (Apache 2.0 §4(d)). Verify the Caduceus template's licence before
bundling or redistributing it; if ambiguous, ship it as a user-installable
artifact or document the source URL. Greenfield means no upstream fork
obligations — keep it that way by recording every dependency's origin in
`NOTICE` as it is added.

## Pre-v1.0 checklist

- `LICENSE` (Apache 2.0) and `NOTICE` in the repo root
- `fastlane/metadata/android/en-US/`: title, short description, full
  description, changelogs/<versionCode>, 2–4 screenshots
- Monotonic `versionCode` enforcement in CI
- `BUILDING.md` documenting NDK version, submodule commits, environment
- GitHub Actions: build → sign → attach APK to release
- Drafted and locally tested `fdroiddata` metadata YAML

## Features required before community arrival

- Hardware detection + auto-config (RAM tier → model/quant/context/parallel)
- Benchmark mode: N tokens, t/s, thermal state, peak RAM — becomes the
  crowd-sourced compatibility matrix
- One-button device report export: SoC, RAM, Android version, llama.cpp build,
  model hash, template version, benchmark numbers
- Template/version pinning surfaced in the UI, so "tool calls broke" reports can
  be triaged immediately

## Open items

- **Thermal profiling under sustained load.** Two phones pegged at ~15 t/s will
  throttle within 10–15 minutes in cases. Measure sustained versus burst per
  device and ship per-device defaults rather than one global config.
- **Mali fallback path.** Decide whether CPU-only mode is a shipped feature or a
  documented limitation.
- **Multi-node tooling.** The current setup is two phones behind LiteLLM with
  least-busy routing and health checks. If node count grows, that configuration
  becomes part of the product story.
