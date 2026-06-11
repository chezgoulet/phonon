# extern — External dependency sources

This directory exists for tooling documentation and legacy artifact references.
LiteRT-LM (the sole inference engine) is consumed as a standard Maven artifact
from Google's Maven repository — no submodules or manual builds needed.

| Dependency | Source | Method |
|---|---|---|
| `LiteRT-LM` | `com.google.ai.edge.litertlm:litertlm-android:0.13.0` | Maven artifact (Google Maven) |

## LiteRT-LM

- SDK: https://ai.google.dev/edge/litert-lm/android
- Source: https://github.com/google-ai-edge/LiteRT-LM
- Models: https://huggingface.co/litert-community

Models are downloaded at runtime from the LiteRT Community HuggingFace
organization in `.litertlm` format (not GGUF). The SDK handles all
hardware acceleration (CPU, GPU, NPU) internally.
