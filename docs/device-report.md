# Device report

**Settings → Export device report** builds a report from the running app, shows
the exact text in a preview, and only then offers to share it.

The same report is written to the app's external files directory before the
preview opens:

```
/sdcard/Android/data/org.phononai.app/files/phonon-device-report.txt
/sdcard/Android/data/org.phononai.app/files/phonon-device-report.json
```

Pull it without root:

```bash
adb -s <serial> shell cat \
  /sdcard/Android/data/org.phononai.app/files/phonon-device-report.txt \
  > pixel-7-report.txt
```

Share uses `ACTION_SEND` with `text/plain` and the full report as `EXTRA_TEXT`.
If the phone has no share target, the files above are still present; the app
shows the path in the preview.

## Fields

**Device**

- `model`, `soc` (`Build.SOC_MODEL`, falling back to `Build.HARDWARE`),
  `hardware`
- `android_release`, `sdk_int`
- `abis`
- `ram_total_bytes`, `ram_gb`, `tier`, plus the tier's default model, context
  and thread count

**App**

- package name
- `versionName` and `versionCode`

**Model**

- file name, SHA-256, size in bytes
- context, threads, load mode (`mmap`, `mlock`, `none`)

**Server**

- state, bind address, port, backend port, alias
- whether an API key is set — the boolean only
- keep-warm enabled/interval (the interval in force, i.e. the measured value)
- `keep_warm_measured_s` and `keep_warm_last_ttft_ms`
- context fit label and whether the context was auto-sized
- thermal pause/resume thresholds
- `endpoint`: the URL a gateway should point at (`http://<lan-ip>:<port>/v1`
  when bound to `0.0.0.0`)

**SoC block** (`soc`)

This device's own core layout with frequencies, CPU features, RAM and the
thermal signal in use — the same detection the `devices/<soc>.yaml` snippet is
built from, so the report carries one phone's contribution:

- `soc`, `codename`, `hardware`, `ram_gb`, `ram_total_bytes`, `android`, `sdk_int`
- `cpu_features`
- `clusters` (e.g. `0-3 @ 1.80 GHz`, `4-5 @ 2.35 GHz`, `6-7 @ 2.85 GHz`)
- `allowed_cpus`, `thermal_source`

**Tuning profile** (`tuning_profile`), the stored per-SoC profile or `null`.

**Field report** (`field_report`), the rendered `devices/<soc>.yaml` (its file
name, saved path and text) — see "Contribute this phone's profile" below.

**GPU prefill** (`gpu_prefill`): whether this APK carries `libggml-opencl.so` and
the status line.

## Contribute this phone's profile

**Settings → Contribute this phone's profile** builds a `devices/<soc>.yaml`
snippet from live detection (SoC, core layout with frequencies, CPU features,
RAM, thermal source) plus the stored tuning profile when one exists (threads,
CPU range, prefill threads, context, measured decode/prefill). The shape matches
`devices/panther.yaml` and the field list in `devices/README.md` exactly.

It is previewed, then copyable or shareable, and written next to the other files
for a pull:

```
/sdcard/Android/data/org.phononai.app/files/phonon-<soc>.yaml
```

The same YAML is embedded in the device report JSON under `field_report`, so one
pull carries everything a reviewer needs.


**Benchmarks**

Every stored benchmark row with its timestamp, load kind, tokens, prefill and
decode rates, TTFT, peak RSS and battery temperature.

## Privacy rules

- **The API key is never included.** The report reads only `apiKey.isNotBlank()`;
  it never reads the key value, and the preview shows the same bytes that are
  shared.
- **No request content, prompts, completions or logs** are included.
- **No model path** beyond the file name, and no account, IMEI, serial or MAC
  address.
- The LAN endpoint is included because the report exists to debug gateways.
  Binding loopback produces a loopback endpoint.

The report is written to app-scoped external storage, which other apps cannot
read without permission. Sharing is an explicit user action from the preview.

## Measured example

The checked-in Pixel 7 report is
[`reports/pixel-7-report.txt`](reports/pixel-7-report.txt), with the JSON form
next to it and the preview screenshot in
[`reports/pixel-7-report-preview.png`](reports/pixel-7-report-preview.png).
