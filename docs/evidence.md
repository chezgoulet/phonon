# External evidence

Numbers and claims gathered from outside this project. None of these has been
reproduced on our hardware. They inform decisions; they do not substitute for
the measurement protocol.

## Phone-class decode speed

- **Pixel 7 (Tensor G2), Qwen2.5-1.5B Q4_K_M, llama.cpp in Termux** — `llama-bench`
  13–21 tok/s depending on pinning; real `llama-server` decode about **9 tok/s**
  at `-t 4`. Source: `Thr45hx/Google-Tensor-Chips`, `on-device-llm/README.md`
  (MIT, October 2026). The authors note the bench-to-server gap explicitly.
- **Pixel 8a (Tensor G3, 8 GB), Qwen3.5-4B mixed-int4, LiteRT-LM CPU** — 22.1
  tok/s prefill, **6.3 tok/s decode**, 11.9 s TTFT, 4.43 GB peak. Source:
  `litert-community/Qwen3.5-4B` model card (2026-10-16).
- **Galaxy S26 Ultra, Gemma 4 E2B `.litertlm`** — 46.9 tok/s decode on CPU, 52.1
  on GPU, 2.58 GB model. Source: `litert-community/gemma-4-E2B-it-litert-lm`
  model card. A 2026 flagship; not a Pixel 7.

Implication for us: on 8 GB Tensor hardware, a 4B model is a mid-single-digit
decode rate, and a 2B is roughly two to three times faster. Measure both.

## The NPU

- The Tensor G2 NPU can be driven from a rooted device by injecting into the
  Camera process (Frida, `AddInput` / `AddOutput` / `VirtualDevice_Submit`), and
  custom int8/per-tensor models run on it through the `google-edgetpu` NNAPI
  accelerator. Source: `Thr45hx/Google-Tensor-Chips`, `g2-pixel7/`.
- Measured on the same work: a transformer FFN block took **1.81 ms on the NPU**
  against **1.57 ms on four CPU threads** (~23 vs ~26 GB/s effective), because
  decode streams weights from the shared LPDDR5 bus and the 7.73 MB SRAM cannot
  hold them. Their conclusion: for LLM decode, use the CPU.

Implication: the NPU is not a shortcut, and a driver hack cannot add memory
bandwidth. This is why the roadmap does not include NPU work.

## Runtime and tooling

- **llama.cpp Gemma 4 parser** — PR #21418, merged 2026-04-04; emits OpenAI
  `tool_calls` from Gemma 4's native format.
- **Gemma 4 MTP** — PR #23398 merged 2026-06-07 (31B, 26B-A4B); E2B support in
  PR #24282 on 2026-06-08. Unsloth ships a ~98 MB Q8_0 drafter for E2B; measured
  +22% on a B200 at 0.55 draft acceptance. Unmeasured on a phone.
- **LiteRT-LM** supports tool use with constrained decoding for supported models,
  but the `litert-community/Qwen3.5-4B` bundle declares
  `supports_function_calling: false` — its tool calls come back as text. Qwen's
  tool calling is a llama.cpp/GGUF capability.
- **LiteLLM** supports multiple deployments per `model_name`, `session_affinity`
  (`x-litellm-session-id` / `metadata.session_id`) and per-deployment timeouts.
  Router defaults assume cloud latency and must be overridden for phones.

## opencode prompt cost

Measured on the host with opencode 1.18.13 against a recording endpoint:

- default request: 10 tools, ~4.4K tokens of overhead;
- trimmed agent (short prompt, 4 tools: bash/edit/read/write): ~2.8K tokens.

On an 8K context that leaves ~5.2K for the task. This is why tasks are one file
and why the agent is not allowed to explore.
