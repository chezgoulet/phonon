# Model families: Qwen3.5 and Gemma 4

Phonon serves both families from the same engine. Qwen3.5 is the default
because it is faster on the reference phone. Gemma 4 is a first-class option
for its multimodal path and its own tool format; the picker shows the measured
cost next to it. The user chooses; the app recommends.

The catalog is a JSON document —
[`assets/catalog.json`](../app/app/src/main/assets/catalog.json) — and it is the
single source of truth for model data. `ModelCatalog.kt` loads and parses it at
startup (`PhononApplication`); the Models screen, Setup and the RAM-tier
defaults all read that one object, and there are no model names or URLs
elsewhere in the UI. **Edit the JSON to change the catalog, not the Kotlin.**
The table below is a rendering of that file and is kept in step with it by
review, not by a generator.

## Catalog

Decode is 128 greedy tokens, `temperature: 0`, `ignore_eos: true`, through
llama.cpp `797de63` with `GGML_CPU_REPACK=ON`, 2 threads pinned to cores 6-7 on
the reference Pixel 7 (`panther`, serial `<device-serial>`, 7.83 GB reported
RAM). **Measured 2026-10-17.** Two back-to-back runs per model, reported as
run 1 / run 2.

| Family | Model | Quant | Context | Download | Pixel 7 decode | Tier |
|---|---|---|---:|---:|---|---|
| Qwen3.5 | Qwen3.5-2B | Q4_K_M | 32K | 1.28 GB | **9.20 / 8.84 tok/s** | 8 GB |
| Qwen3.5 | Qwen3.5-4B | Q4_K_M | 16K | 2.74 GB | **4.13 / 3.94 tok/s** | 8 GB (tight) |
| Qwen3.5 | Qwen3.5-9B | Q4_K_M | 48K | 5.68 GB | unmeasured | 16 GB |
| Gemma 4 | Gemma 4 E2B | Q4_0 | 16K | 2.84 GB | **7.05 / 6.74 tok/s** | 8 GB |
| Gemma 4 | Gemma 4 E4B | Q4_0 | 16K | 4.59 GB | **4.12 / 3.85 tok/s** | 8 GB (tight) |
| MiniCPM5 | MiniCPM5-2B | Q4_K_M | 32K | 1.56 GB | **6.82 / 6.57 tok/s** | 8 GB |
| Granite | Granite 4.2 3B | Q4_K_M | 32K | 2.24 GB | **4.41 / 4.39 tok/s** | 8 GB |
| Nemotron | Nemotron 3 Nano 4B | Q4_K_M | 32K | 2.84 GB | **3.57 / 3.46 tok/s** | 8 GB |

Trade-offs as shown in the picker:

- **Qwen3.5-2B** — fastest here and the 8 GB default: 32K context with headroom
  to spare.
- **Qwen3.5-4B** — better answers than the 2B at about 4 tok/s. Runs on 8 GB at
  16K with ~600 MB free; at 32K it fits but leaves ~250 MB.
- **Qwen3.5-9B** — highest-quality Qwen option; unmeasured and only sensible on
  16 GB.
- **Gemma 4 E2B** — multimodal and Gemma's own tool format, at a measured
  ~2 tok/s less than Qwen 2B.
- **Gemma 4 E4B** — the larger multimodal Gemma at about 4 tok/s. Runs on 8 GB
  at 16K with ~560 MB free.
- **MiniCPM5-2B** — Apache-2.0, 32K context in this catalog, and the strongest
  tool-use numbers in its class (97.1 on tau2-Bench Telecom); ~25% slower than
  Qwen 2B on this phone. Tool calls parse correctly through llama.cpp.
- **Granite 4.2 3B** — Apache-2.0 and IBM's tool-calling design; ~4.4 tok/s
  here, about half Qwen's speed. Tool calls parse correctly.
- **Nemotron 3 Nano 4B** — NVIDIA Open Model License (not OSI); 3.5 tok/s, the
  slowest measured. Offered for completeness, not speed.

`unmeasured` is shown verbatim. No number is inferred from the row above it,
and no download size is a guess: each is the `Content-Length` Hugging Face
declared for the pinned file.

### Why the 16 GB tier is 9B, not 8B

Earlier tier documentation named a Qwen3.5-8B. The family has no 8B GGUF; the
next size after 4B is 9B. The catalog and the tier default now say 9B. It
remains **unmeasured** — no 16 GB phone has run this build.

## Provenance

All URLs were checked with a `HEAD` request on **2026-10-17** and returned
`200` with the byte count below. The SHA-256 values are the ones Hugging Face
publishes for the same blobs; the app does not verify them at download time
(it verifies the declared size, see `ModelDownloader.kt`).

| Model | Repository | File | Bytes | SHA-256 |
|---|---|---|---:|---|
| Qwen3.5-2B | `unsloth/Qwen3.5-2B-GGUF` | `Qwen3.5-2B-Q4_K_M.gguf` | 1,280,835,840 | `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223` |
| Qwen3.5-4B | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-Q4_K_M.gguf` | 2,740,937,888 | `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4` |
| Qwen3.5-9B | `unsloth/Qwen3.5-9B-GGUF` | `Qwen3.5-9B-Q4_K_M.gguf` | 5,680,522,464 | `03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8` |
| Gemma 4 E2B | `ggml-org/gemma-4-E2B-it-GGUF` | `gemma-4-E2B-it-Q4_0.gguf` | 2,841,481,184 | `8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52` |
| Gemma 4 E4B | `ggml-org/gemma-4-E4B-it-GGUF` | `gemma-4-E4B-it-Q4_0.gguf` | 4,590,807,392 | `a555b900214b477d8880e7832e0b8925e139b0159640036b09fe472b6f2097f2` |

Direct URL pattern:
`https://huggingface.co/<repo>/resolve/main/<file>`.

The 2.84 GB Gemma E2B file matches the `gemma-4-E2B-it-Q4_0.gguf` already used
for the Pixel 7 measurements and for the on-device tool-call test.

## Switching family

Choosing an entry on the Models screen:

1. sets the model path, default context and alias together
   (`qwen3.5-2b`, `qwen3.5-4b`, `qwen3.5-9b`, `gemma-4-e2b`, `gemma-4-e4b`);
2. marks the choice as the operator's, so RAM-tier auto-config cannot replace
   it;
3. reloads settings, which changes the config hash and restarts
   `llama-server` with the new `-m`, `-c` and `--alias`.

Auto-config on first start still selects the tier recommendation — Qwen3.5-2B
on this phone. It runs once and never overrides a model the operator picked.

## Test results

See `docs/models-test.md` for the acceptance run: both families served through
the app proxy, decode twice each, and one `get_weather` tool call per family
with the observed `finish_reason` and arguments.

## Download integrity

Every catalog entry carries the byte count and the SHA-256 that Hugging Face
declares for the pinned file. The downloader:

- resumes a `.part` with an HTTP Range request and refuses a server that resumes
  at the wrong offset;
- checks the declared size before renaming into place;
- computes SHA-256 and deletes the file rather than installing a model whose
  hash does not match (a previously downloaded file is re-hashed too);
- refuses to start when the destination volume cannot hold the remaining bytes
  plus a 64 MB margin.

A model whose RAM tier outranks the device is not silently downloaded: the
picker asks first, states the device's RAM and the model's tier, and allows an
explicit override.

## 4B class on an 8 GB phone

Both 4B models were measured on the reference Pixel 7 rather than assumed
impossible. At 16K context they load, serve and return tool calls at about
4 tok/s:

| Model | Context | Decode | RSS | Free after load |
|---|---|---|---|---|
| Qwen3.5-4B | 16K | 4.13 / 3.94 tok/s | 3.54 GB | 616 MB |
| Gemma 4 E4B | 16K | 4.12 / 3.85 tok/s | 3.73 GB | 562 MB |

Qwen3.5-4B at 32K also runs, but leaves about 250 MB free, which is not enough
headroom for anything else on the phone. The catalog therefore defaults both to
16K on 8 GB devices: the point is to offer a configuration that works, not a
bigger context that does not.

## What a catalog entry must say

Every entry carries the same profile, and the UI shows all of it:

| Field | Why |
|---|---|
| architecture | dense, hybrid or MoE, with active parameters where relevant |
| licence and OSI status | a non-OSI licence is not a blocker for sideloading, but it is a fact |
| job fit | what the model is good at, in the operator's terms |
| measured decode and prefill | only when measured; otherwise `unmeasured` |
| measured on | the device the numbers came from |
| vendor claims | labelled as vendor-reported, with a source |
| weaknesses | the trade-off text |

**Provenance rule.** A number is never presented as "this phone's" unless it was
measured on the device showing it. Reference figures from the Pixel 7 are
labelled "(reference)" wherever another device sees them. When a device runs its
own benchmark, that result replaces the reference figure for that entry.

**Vendor claims are not measurements.** MiniCPM5-2B's τ²-Bench Telecom score of
97.1 is reported by OpenBMB and has not been reproduced here; it appears on the
card with that label and its source. The same rule applies to any future claim:
vendor number, source, and "not measured here".

**Multimodal is not delivered.** The app never passes `--mmproj`, so no model can
accept an image regardless of what the model itself supports. Gemma 4 E2B/E4B
and Qwen3.5 are text-only in this build until the projector is downloaded and
wired; the catalog does not claim otherwise.
