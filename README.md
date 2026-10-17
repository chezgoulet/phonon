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

No inference has been run on a phone under this repository's name yet. What is
here is the structure, the measurement protocol and the host-side tooling. The
first milestone is a measured number from the owner's two Pixel 7s.

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

1. **Measure** both Pixel 7s: prefill, decode, with and without the MTP drafter.
2. **Gateway**: LiteLLM with two pinned phones plus one pooled alias.
3. **App**: foreground service wrapping llama.cpp; the first decision is whether
   a bundled `llama-server` binary can be executed from the app's native library
   directory.
4. **Appliance image**: GrapheneOS plus a configuration/kernel module.
5. **ROM**: a fork of GrapheneOS, renamed and signed by this project, only if the
   module cannot reach the parts that matter.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
