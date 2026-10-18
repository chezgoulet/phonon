# Model families: Qwen3.5 and Gemma 4

Phonon serves both families from the same engine. Qwen3.5 is the default
because it is faster on the reference phone. Gemma 4 is a first-class option
for its multimodal path and its own tool format; the picker shows the measured
cost next to it. The user chooses; the app recommends.

The catalog lives in
[`ModelCatalog.kt`](../app/app/src/main/java/org/phononai/app/ModelCatalog.kt).
The Models screen, Setup and the RAM-tier defaults all read that one object;
there are no model names or URLs elsewhere in the UI.

## Catalog

Decode is 128 greedy tokens, `temperature: 0`, `ignore_eos: true`, through
llama.cpp `797de63` with `GGML_CPU_REPACK=ON`, 2 threads pinned to cores 6-7 on
the reference Pixel 7 (`panther`, serial `<device-serial>`, 7.83 GB reported
RAM). **Measured 2026-10-17.** Two back-to-back runs per model, reported as
run 1 / run 2.

| Family | Model | Quant | Context | Download | Pixel 7 decode | Tier |
|---|---|---|---:|---:|---|---|
| Qwen3.5 | Qwen3.5-2B | Q4_K_M | 32K | 1.28 GB | **9.20 / 8.84 tok/s** | 8 GB |
| Qwen3.5 | Qwen3.5-4B | Q4_K_M | 32K | 2.74 GB | unmeasured | 12 GB |
| Qwen3.5 | Qwen3.5-9B | Q4_K_M | 48K | 5.68 GB | unmeasured | 16 GB |
| Gemma 4 | Gemma 4 E2B | Q4_0 | 16K | 2.84 GB | **7.05 / 6.74 tok/s** | 8 GB |
| Gemma 4 | Gemma 4 E4B | Q4_0 | 16K | 4.59 GB | unmeasured | 12 GB |
| MiniCPM5 | MiniCPM5-2B | Q4_K_M | 32K | 1.56 GB | **6.82 / 6.57 tok/s** | 8 GB |
| Granite | Granite 4.2 3B | Q4_K_M | 32K | 2.24 GB | **4.41 / 4.39 tok/s** | 8 GB |
| Nemotron | Nemotron 3 Nano 4B | Q4_K_M | 32K | 2.84 GB | **3.57 / 3.46 tok/s** | 8 GB |

Trade-offs as shown in the picker:

- **Qwen3.5-2B** — fastest here and the 8 GB default: 32K context with headroom
  to spare.
- **Qwen3.5-4B** — better answers than the 2B; unmeasured here and tight on
  8 GB RAM.
- **Qwen3.5-9B** — highest-quality Qwen option; unmeasured and only sensible on
  16 GB.
- **Gemma 4 E2B** — multimodal and Gemma's own tool format, at a measured
  ~2 tok/s less than Qwen 2B.
- **Gemma 4 E4B** — the larger multimodal Gemma; unmeasured here and a 12 GB
  choice.
- **MiniCPM5-2B** — Apache-2.0, 131K context, and the strongest tool-use
  numbers in its class (97.1 on tau2-Bench Telecom); ~25% slower than Qwen 2B
  on this phone. Tool calls parse correctly through llama.cpp.
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
