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
  after_5min_tps: null
  after_15min_tps: null
  peak_mem_mb: null
  battery_temp_c: null
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

## Contributing a device from the phone

The app's **Settings → Contribute this phone's profile** action renders exactly
this YAML from live detection and the phone's own tuning profile, previews it,
and saves it to the app's files (and the device report JSON) for a pull. It
always writes `status: candidate` and `units: 1`: a phone contributing itself is
a starting point for review, not a review. Copy the snippet into the repo as
`devices/<soc>.yaml` and follow the rules above before promoting `status`.

New keys are **not** added by the app: it emits only the fields in the template,
so a contributed file diffs cleanly against the schema.

