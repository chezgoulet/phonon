# Device manifests

One YAML file per device, named by codename. The fields are fixed; a new field
needs a reason in the PR.

```yaml
device: Google Pixel 7          # marketing name
codename: panther               # Android build codename
soc: Google Tensor G2
ram_gb: 8
android: 15                     # build under test
rooted: false
runtime: llama.cpp              # + exact build below
runtime_build: b11712
model: gemma-4-E2B-it-Q4_K_M
context: 8192
threads: 4
mtp: true
measured:                       # null until measured; never estimated
  prefill_tps: null
  decode_tps: null
  ttft_s: null
  agent_turn_s: null            # wall seconds, one agent-shaped request through the app's /v1
  after_5min_tps: null
  after_15min_tps: null
  peak_mem_mb: null
  battery_temp_c: null
  watts: null                   # wall draw while serving, from a USB power meter
  app_version: null             # the app build that produced this measured block
  measured_date: null           # the date that block was taken
notes: ""
```

Rules:

- `null` means unmeasured. Do not put an estimate there.
- `measured` values come from `scripts/bench_phone.sh` at the reference
  configuration, median of two runs after a discarded warm-up.
- `after_5min_tps` and `after_15min_tps` are required for a device to be listed
  as supported: a server number is a sustained number.
- A device with `null` measurements may exist in the tree as `status: candidate`,
  but it is not listed as supported in the README.

### The serving fields

`agent_turn_s` and `watts` describe the phone as a node someone actually runs,
rather than as a benchmark target.

- **`agent_turn_s`** — wall seconds for one agent-shaped request: a system
  prompt and tool schemas of roughly 2,800 tokens, plus 128 generated tokens,
  sent through the app's own `/v1`. This is the number that decides whether the
  phone is usable for an agent, because prefill dominates that request. It is
  not a decode rate and must not be derived from one.
- **`watts`** — wall power draw while the server is serving, read from a USB
  power meter between the charger and the phone. Taken while a generation is in
  flight, not at idle.

### Provenance

`app_version` and `measured_date` say which build produced the block and when.
They exist because a number without them is not checkable: the same phone at
the same settings produces different rates under different builds, and this
project has changed its instruction selection twice. When a document elsewhere
in the tree records a measurement whose app version differs from the current
release, that is not an error — it is what these two fields are for.

Set `app_version` to the version the measuring build reported. If the source
document does not name one, `null` it and say so in `notes`; do not infer the
version from the date the document was written.
