# Contributing

## The one rule

**No number without a measurement.** Speed claims, memory claims and thermal
claims must come from `scripts/bench_phone.sh` or an equivalent reproducible
run on the device they describe. Estimates are welcome in discussion and are
labelled as estimates; they do not enter documentation or device manifests.

## How work lands

`main` is what ships; `testing` is where work integrates. They are not
interchangeable, and the difference is what keeps a half-finished feature off a
release.

- **`feature/*`** — branched from `testing`, pull-requested back into `testing`.
  A feature that is not release-ready still has a home here.
- **`hotfix/*`** — branched from `main` for a fault that is shipping; pull-requested
  into `main` and mirrored into `testing` in the same breath, so the next release
  does not undo it.
- **`main`** — releases only. It moves when `testing` is merged into it for a cut,
  and the tag goes there. Nothing is committed to it directly.
- **`testing`** — never behind `main`. A `testing` branch that lags is a defect in
  the branch, not permission to work on `main`; realign it before starting anything.

Every pull request names its base, and a pull request based on another feature
branch is not reviewable — it is closed with a request to rebase onto `testing`.

CI follows the same split: `.github/workflows/proxy-harness.yml` runs on pull
requests that touch the API surface, and `.github/workflows/reproducible.yml` runs
on any push to `main` or `testing` that touches the app. A red check is the answer,
not an obstacle to merge around.

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
