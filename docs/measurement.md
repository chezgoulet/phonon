# Measurement protocol

Every number in this repository comes from this protocol, run on the device it
describes. Estimates are labelled as estimates and never enter a device
manifest.

## Before the run

Record, in the manifest:

- device model, codename, SoC, RAM;
- Android build and whether the device is rooted;
- runtime and exact build (`llama.cpp` commit or release tag);
- model file name and quantization;
- thread count (`-t`), context size (`-c`), whether MTP is enabled;
- ambient conditions and whether the phone has a fan.

## The run

1. Start `llama-server` with the configuration under test.
2. Warm up with one completion and discard it.
3. Measure twice, take the median:
   - prefill tokens/sec (`timings.prompt_per_second`);
   - decode tokens/sec (`timings.predicted_per_second`);
   - time to first token;
   - peak memory if the runtime reports it.
4. Record battery temperature before and after (`dumpsys battery`).
5. Repeat after 5 and after 15 minutes of continuous generation. A cold number
   is not a server number.
6. Repeat the whole run with MTP off, then on. The comparison is the point.

`scripts/bench_phone.sh` does steps 2–4 and writes a CSV row. It measures the
server, not `llama-bench`: the raw bench loop has been measured at roughly twice
the real server's decode rate, so a bench number is not a serving number.

## Reporting

- A cold number is labelled cold.
- A sustained number is labelled with the duration.
- An extrapolation is labelled `estimate` and never written into a manifest.
- A number from a different device is not evidence for this device. Cite it as
  external evidence, in `docs/evidence.md`, not in the manifest.

## Acceptance for a device PR

A device is supported when its manifest contains measured prefill and decode at
the reference configuration, plus a note on thermals after fifteen minutes. No
numbers, no merge.
