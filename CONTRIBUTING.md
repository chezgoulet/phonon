# Contributing

## The one rule

**No number without a measurement.** Speed claims, memory claims and thermal
claims must come from `scripts/bench_phone.sh` or an equivalent reproducible
run on the device they describe. Estimates are welcome in discussion and are
labelled as estimates; they do not enter documentation or device manifests.

## Adding a device

1. Copy `devices/panther.yaml` to `devices/<codename>.yaml`.
2. Fill in the static fields: marketing name, codename, SoC, RAM, Android build,
   root state, runtime build, model, quantization, context, threads, MTP.
3. Run the measurement protocol in [`docs/measurement.md`](../docs/measurement.md).
4. Fill `measured` with the results — prefill, decode, TTFT, 5-minute and
   15-minute decode, peak memory, battery temperature.
5. Open a PR. Include the raw `/v1/chat/completions` responses or the
   `phone-bench.csv` rows in the PR description.

A device PR without measurements is closed with a request for them. A device
whose numbers turn out to be copied from another device is rejected.

## Code

- The phone side is expected to stay small: a foreground service, a model
  manager, an HTTP surface, a watchdog.
- The gateway is configuration (LiteLLM), not code. Changes that add a routing
  server belong in a different project.
- The host-side worker is opencode plus the runner in `tools/`.
- Anything that binds a service to the LAN must default to requiring an API key.

## Licensing

Contributions are accepted under the Apache License 2.0. There is no CLA;
by opening a pull request you agree that your contribution is licensed under the
same terms.

## The ROM

If and when the project forks GrapheneOS, the fork must be renamed and signed
with the project's own keys, and it must not present itself as GrapheneOS.
That is a requirement of the upstream project, not a preference of ours.
