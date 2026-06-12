# NPU Acceleration

Phonon's core thesis is that phone NPUs sit idle 99% of the time. This page
explains how Phonon selects an accelerator, how to configure and verify it,
and how to report results for hardware we haven't validated.

## How it works

Each inference group can request an accelerator in `phonon.yaml`:

```yaml
groups:
  - name: "gemma-2b"
    mode: "pool"
    runtime: "litert"
    backend: "auto"        # auto (default) | npu | gpu | cpu
    model: "gemma-2b-it"
    phones: ["phone-kitchen", "phone-livingroom"]
```

The coordinator sends the requested backend with every `model_load` command.
On the phone, the sidecar builds an **attempt chain** and tries each backend
in order until one initializes:

| Requested | Chain attempted |
|---|---|
| `auto` (NPU-capable SoC) | NPU → GPU → CPU |
| `auto` (other SoC) | GPU → CPU |
| `npu` | NPU → GPU → CPU |
| `gpu` | GPU → CPU |
| `cpu` | CPU |

Two principles drive this design:

1. **A misconfigured group degrades to slow, never to dead.** Every chain
   ends in CPU. NPU initialization can fail for reasons invisible from the
   coordinator (unsupported op in the model, driver quirks, SDK version), so
   failure moves to the next candidate instead of taking the node out.
2. **The dashboard shows truth, not intent.** The phone reports the backend
   it *actually* initialized in every heartbeat. The web UI renders it as a
   badge on each phone card (NPU green, GPU blue, CPU muted), so a node
   silently falling back to CPU is immediately visible.

## Which devices attempt NPU under `auto`

The sidecar attempts NPU when the SoC is in a conservative known-good list
(see `sidecar/.../accel/BackendPlanner.kt`):

- **Google Tensor** G1–G5 (Pixel 6 and later) — Edge TPU path
- **Qualcomm Snapdragon 7/8-series** (e.g. Galaxy S23, OnePlus 11) — Hexagon/QNN path

On older Android (< API 31, where `Build.SOC_MODEL` doesn't exist) Tensor
devices are recognized by board name (`oriole`, `panther`, `lynx`, `zuma`, …).

If your SoC has a working NPU path but isn't attempted under `auto`, set
`backend: "npu"` explicitly — the operator override is always honored, and
runtime failure still falls back safely. Then please open a PR adding your
SoC to the list with your measured tokens/sec.

## Verifying which backend is active

Three ways, most to least convenient:

1. **Dashboard** — the badge next to the model name on each phone card.
2. **API** — `GET /api/v1/cluster/nodes` includes `"backend": "npu"` per node.
3. **Logcat** — `adb logcat -s ModelManager:I` shows the chain and result:
   `Backend chain for Tensor G3: [npu, gpu, cpu]` followed by
   `LiteRT-LM engine initialized: model=… backend=npu`.

## Troubleshooting

**Badge shows CPU on a Tensor/Snapdragon phone.**
Check logcat for the failure reason on the `npu` and `gpu` attempts. Common
causes: the model contains ops the NPU delegate doesn't support (try a
different quantization), or the pinned LiteRT-LM version doesn't ship an NPU
backend for your SoC (see SDK note below).

**Thermals are worse on NPU than expected.**
NPU inference draws sharp bursts; the health monitor's overheat threshold
(default 45 °C) may evict the node under sustained load. That's working as
intended — tune `cluster.health.overheat` rather than disabling it.

## SDK note (maintainers)

`accel/BackendFactory.kt` is the only file that touches the LiteRT-LM
accelerator API. NPU support in LiteRT-LM is delivered per-SoC and its
constructor has moved between releases, so the factory currently resolves
`Backend.NPU` reflectively (with a matching proguard keep rule) — meaning
the module compiles against litertlm versions with or without NPU, and
absence simply falls through to GPU. Once the project pins an SDK version
with a stable NPU API, replace the reflection with the direct call and
delete the keep rule. Everything else in the feature is SDK-independent.
