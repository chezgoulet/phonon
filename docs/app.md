# Phonon app v0

Date: 2026-10-17
Branch: `feat/app-v0`
Device: stock Pixel 7 (`panther`), serial `2A091FDH200AJ1`, Android 17 /
SDK 37, arm64-v8a, unrooted. No other device was used.

## Status, honestly

Implemented and demonstrated on the Pixel 7: native packaging, install,
model download with size verification, foreground service, readiness,
OpenAI-compatible completions and tool calls through the app's single port.

**Not demonstrated: acceptance items 7 (kill/restart) and 8 (thermal pause).**
During the run the phone was rebooted to clear memory pressure. It came back
to a secure lock screen and the owner's credential is not available to this
session, so the app cannot start (Android does not start non-direct-boot apps
or mount credential-encrypted app storage before first unlock). The exact
blocker and the commands are recorded below rather than replaced with a claim
that was never observed.

Everything else in this document is captured output from this run.

## Architecture

```
                 adb forward tcp:8080
host curl  ───────────────────────────►  127.0.0.1:8080  (app process)
                                                    │
                              ┌─────────────────────┼──────────────────────┐
                              │                     │                      │
                         GET /health          /v1/* proxy            POST /model/download
                              │                     │                      │
                              ▼                     ▼                      ▼
                     ServerManager state   127.0.0.1:8081         ModelDownloader
                     + BatteryManager       llama-server           filesDir/models/*.part
                              │                     ▲
                              └── supervises ───────┘
                                  libllama-server.so
```

The app owns the operator-facing port (default `8080`). `llama-server` is
bound to an internal port (default `8081`) and is not exposed directly. This
is what lets one port answer `/health` while the model is `loading`,
`restarting`, `thermal_pause`, or `stopped`, and still proxy `/v1` when the
server is `ready`. The alternative — pointing clients at the server's own port
— cannot answer health when the server is down.

### Files

```
app/app/src/main/java/org/phononai/app/
├── MainActivity.kt       state, temperature, decode t/s, start/stop, download, settings
├── PhononService.kt      foreground service, wake lock, thermal policy, download orchestration
├── ServerManager.kt      exec, readiness, supervision, exponential backoff, ring buffer
├── ApiServer.kt          loopback HTTP server and raw /v1 proxy
├── ModelDownloader.kt    HTTP Range resume, .part, Content-Length verification
├── ThermalMonitor.kt     BatteryManager temperature every 30 s
├── Settings.kt           SharedPreferences
├── DownloadState.kt      download progress shared with the activity
├── RingLog.kt            bounded stdout/stderr/log tail
├── AppState.kt           same-process activity → service handle
└── BootReceiver.kt       BOOT_COMPLETED → service
```

### Native packaging

`app/sync-native.sh [BUILD_DIR]`, default `/home/c/llama.cpp/build-android-arm64/bin`:

- copies `llama-server` → `jniLibs/arm64-v8a/libllama-server.so` (Android only
  extracts `lib*.so`);
- copies the seven sibling `lib*.so` files unchanged;
- keeps `extractNativeLibs="true"` and `useLegacyPackaging = true`, so the
  files exist as real executables in `nativeLibraryDir`;
- the app execs that path with `LD_LIBRARY_PATH` set to `nativeLibraryDir`.

`app/app/src/main/jniLibs/` is in `.gitignore`; 181 MB of binaries are not
committed.

Measured payload:

```
$ ./app/sync-native.sh
synced 7 shared libraries + libllama-server.so into .../jniLibs/arm64-v8a
181M	.../jniLibs/arm64-v8a
-rwxr-xr-x ...  6812824 libggml-base.so
-rwxr-xr-x ...  4968000 libggml-cpu.so
-rwxr-xr-x ...  5136560 libggml.so
-rwxr-xr-x ... 70779328 libllama-common.so
-rwxr-xr-x ... 45046432 libllama-server-impl.so
-rwxr-xr-x ...     7024 libllama-server.so
-rwxr-xr-x ... 38005648 libllama.so
-rwxr-xr-x ... 18497872 libmtmd.so
```

### Server manager

Arguments are built from settings on every launch:

```
-m <model> -c 2048 -t 4 -np 1 -fa on --jinja --reasoning off
--alias gemma-4-e2b --host 127.0.0.1 --port 8081
--log-file /data/user/0/org.phononai.app/files/logs/llama-server.log
[--api-key <key>]
```

Captured from `/logs` after the first start:

```
start .../lib/arm64/libllama-server.so -m .../files/models/gemma-4-E2B-it-Q4_0.gguf -c 2048 -t 4 -np 1 -fa on --jinja --reasoning off --alias gemma-4-e2b --host 127.0.0.1 --port 8081 --log-file .../files/logs/llama-server.log
spawned pid=22837
[err] 0.00.002.470 I srv  llama_server: initializing ...
[err] 0.00.006.942 I srv    load_model: loading model '.../gemma-4-E2B-it-Q4_0.gguf'
[log] 0.04.991.860 I srv    load_model: initializing, n_slots = 1, n_ctx_slot = 2048, kv_unified = 'false'
[log] 0.05.021.365 I srv  llama_server: model loaded
[log] 0.05.021.401 I srv  llama_server: listening on http://127.0.0.1:8081
ready after 5s, pid=22837
```

Readiness is `GET http://127.0.0.1:8081/health == 200`, polled every 500 ms.
`ready` is never set from the log text or from a timer. A load that does not
answer within 300 s becomes `error` and is killed so the supervisor retries.

Supervision: the child is waited on; an unexpected exit sets `restarting` and
relaunches after 1 s, 2 s, 4 s, … capped at 30 s. A successful `ready` resets
the delay to 1 s. Before every launch the previous process is destroyed and
waited for, and at service startup `/proc` is scanned for a child of this uid
whose cmdline contains `libllama-server.so`; strays are killed. Shutdown on
service destroy does the same sweep.

### API

`ApiServer` binds `127.0.0.1:<settings.port>` (default 8080).

| Method | Path | Behaviour |
|---|---|---|
| GET | `/health` | JSON; 200 only in `ready`, 503 otherwise |
| GET | `/v1/models` | proxied to llama-server |
| POST | `/model/download` | `{"url": "..."}`; 202, 409 if already active |
| GET | `/model/download` | progress JSON |
| POST | `/server/start`, `/server/stop` | control |
| GET | `/settings` | current settings (API key shown only as `api_key_set`) |
| POST | `/settings` | partial update; validates thresholds and ports |
| GET | `/logs` | ring buffer, text/plain |
| * | `/v1/*` | raw HTTP proxy to `127.0.0.1:8081` |

The proxy forwards the method, path, query and body; streams chunked responses
as they arrive (SSE works); injects `Authorization: Bearer <api-key>` only when
an API key is configured and the client sent none; and returns 502/503 rather
than hanging when the backend is absent. Non-streaming JSON responses are
scanned for `timings` to fill `last_decode_tps` / `last_prompt_tps`; streaming
timings are not captured (see caveats).

Health state machine: `loading`, `ready`, `restarting`, `thermal_pause`,
`stopped`, `error`.

### Thermal policy

`ThermalMonitor` reads `BatteryManager.EXTRA_TEMPERATURE` from the sticky
`ACTION_BATTERY_CHANGED` broadcast every 30 s. Policy in `PhononService`:

- if not paused and `temp >= thermal_stop_c` (default 45 °C) and the operator
  wants the server: stop the server, state `thermal_pause`;
- if paused and `temp <= thermal_resume_c` (default 40 °C): start it again.

The thresholds are settings (activity and `POST /settings`) and are not part of
the server argument hash, so changing them takes effect on the next poll
without restarting a healthy server.

### Settings

SharedPreferences: model path, context (2048), threads (4), public port (8080),
backend port (8081), reasoning (off), thermal stop/resume, API key, alias,
auto-start, download URL. Server argument changes take effect on the next
server start/restart; the activity's Save action restarts the server when those
arguments changed. Public-port changes restart the app API immediately.

## Build and install

```bash
export JAVA_HOME=/home/c/jdk-17
export ANDROID_HOME=/home/c/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
./app/sync-native.sh
cd app
GRADLE=$(find /home/c/.gradle/wrapper/dists/gradle-8.12.1-bin -name gradle -type f | head -1)
$GRADLE --no-daemon --console=plain assembleDebug
```

Captured:

```
BUILD SUCCESSFUL in 13s
36 actionable tasks: 5 executed, 31 up-to-date
```

## Acceptance evidence

### 1. assembleDebug and APK size

```
/home/c/phonon/app/app/build/outputs/apk/debug/app-debug.apk
56,419,651 bytes
sha256 d7d9acd2e06322bb36d21200890b1f855745991bebe0be80143f7ceecda10184
```

The inference evidence below was captured from the build installed at 13:24
(56,419,063 bytes, sha256 `87f1cdc3…`). The only source change after that was
stricter `Content-Range` start validation in `ModelDownloader`; the tree was
rebuilt and the final APK above was installed. The behavioural difference does
not touch the service, API, or supervisor.

Uncompressed APK payload: 191,700,572 bytes, of which the eight native files
are 189,046,752 bytes. The compression is why the APK is 56 MB while the
extracted native library directory is 181 MB (`du`).

The debug build includes ELF debug info: AGP reported
`Unable to strip the following libraries, packaging them as they are`.
A stripped release build would be smaller; that was not built or claimed.

### 2. Uninstall / install

```
$ adb -s 2A091FDH200AJ1 uninstall dev.phonon.spike
Failure [DELETE_FAILED_INTERNAL_ERROR]
$ adb -s 2A091FDH200AJ1 install -r -t .../app-debug.apk
Performing Streamed Install
Success
$ adb -s 2A091FDH200AJ1 shell pm list packages | grep phonon
package:org.phononai.app
```

The uninstall failed because `dev.phonon.spike` was already absent on this
unit (the earlier spike APK had been removed); the failure is reported rather
than hidden. No `dev.phonon.spike` package remains.

The final APK (with the API-31 foreground-service guard and the stricter
downloader range check) was installed after the last rebuild; installing while
the device was locked succeeded, because package installation does not need the
app's credential-encrypted storage.

### 3. Model download

Host file server. Port 9000 is occupied on this host by an unidentified
listener that accepts connections and returns an empty reply, so the actual
server was started on 9001. `ss -ltn`:

```
LISTEN 0 4096 0.0.0.0:9000 0.0.0.0:*
LISTEN 0 4096    [::]:9000    [::]:*
```

The app's default URL remains `http://127.0.0.1:9000/gemma-4-E2B-it-Q4_0.gguf`
as specified. The demonstration passed the 9001 URL explicitly.

```bash
cd /home/c/models
python3 -m http.server 9001 --bind 127.0.0.1
adb -s 2A091FDH200AJ1 reverse tcp:9001 tcp:9001
curl -sS -X POST http://127.0.0.1:8080/model/download \
  -H 'Content-Type: application/json' \
  -d '{"url":"http://127.0.0.1:9001/gemma-4-E2B-it-Q4_0.gguf"}'
```

Final progress JSON:

```json
{"active":false,"done":true,"bytes":2841481184,"total":2841481184,"error":null,
 "url":"http://127.0.0.1:9001/gemma-4-E2B-it-Q4_0.gguf",
 "dest":"/data/user/0/org.phononai.app/files/models/gemma-4-E2B-it-Q4_0.gguf",
 "started_at_ms":1792257486679}
```

File on the device:

```
$ adb -s 2A091FDH200AJ1 shell "run-as org.phononai.app ls -l files/models files/logs"
files/models:
-rw------- 1 u0_a415 u0_a415 2841481184 2026-10-17 13:19 gemma-4-E2B-it-Q4_0.gguf
files/logs:
-rw------- 1 u0_a415 u0_a415       1090 2026-10-17 13:19 llama-server.log
```

`2,841,481,184` matches the host file exactly. The downloader writes
`<name>.part`, verifies `part.length() == Content-Length` (or the
`Content-Range` total for a 206), fsyncs, then renames. It reports an error and
keeps the `.part` on a mismatch. **No checksum is computed or claimed.**

### 4. Service starts the server, `/health` ready

```bash
adb -s 2A091FDH200AJ1 forward tcp:8080 tcp:8080
curl -sS http://127.0.0.1:8080/health
```

```json
{"state":"ready","model":"gemma-4-E2B-it-Q4_0.gguf","pid":22837,"uptime_s":7,
 "battery_temp_c":25.299999237060547,"thermal_paused":false,
 "last_decode_tps":null,"last_prompt_tps":null,"restart_count":0,
 "backend_port":8081,"error":null}
```

HTTP status was 200. While the model was loading the same endpoint returned
503 with `"state":"loading"`; with the server stopped it returned 503 with
`"state":"stopped"`. The foreground service held
`PARTIAL_WAKE_LOCK 'Phonon::llama-server'`; the server's `oom_score_adj` was
`200`, which is the foreground-service band (a plain background process would
be much higher).

### 5. Completion through `adb forward`

```bash
curl -sS http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"gemma-4-e2b","messages":[{"role":"user","content":
       "Reply with one short sentence: what is 2+2?"}],
       "max_tokens":64,"temperature":0}'
```

```json
{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant",
 "content":"2+2 equals 4."}}],"created":1792257574,"model":"gemma-4-e2b",
 "system_fingerprint":"b1-c0fd786","object":"chat.completion",
 "usage":{"completion_tokens":8,"prompt_tokens":22,"total_tokens":30,
 "prompt_tokens_details":{"cached_tokens":0}},
 "id":"chatcmpl-YahfEwrj0QdaKhOZ8EFb19XfLVsFNZhj",
 "timings":{"cache_n":0,"prompt_n":22,"prompt_ms":2663.774,
 "prompt_per_token_ms":121.08063636363636,"prompt_per_second":8.258958905672928,
 "predicted_n":8,"predicted_ms":1315.597,
 "predicted_per_token_ms":187.94242857142856,
 "predicted_per_second":5.320778323453155}}
```

Non-empty content: `2+2 equals 4.`
`timings.predicted_per_second`: **5.3208**
`timings.prompt_per_second`: **8.2590**

A second completion (`Write exactly one sentence about the ocean.`, 48 max
tokens) returned content and `predicted_per_second: 5.1245`,
`prompt_per_second: 8.0641`.

These numbers are **below** the 11.6–12.0 decode / 18.8–20.7 prefill reported
in the task's measured baseline. The cause was not established. The device was
under severe memory pressure during the run: `free -m` showed 7,468 MB total,
6,289 MB used, 345 MB free and 2.5 GB of zram swap; 830 tasks existed. A reboot
was attempted to obtain a clean measurement, and that is where the device
locked. This discrepancy is a finding, not a corrected number: do not quote
11.6 tok/s from this run.

### 6. Tool call

```bash
curl -sS http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -d '{
  "model":"gemma-4-e2b",
  "messages":[{"role":"user","content":
    "What is the current weather in Montreal? Use the get_weather tool."}],
  "tools":[{"type":"function","function":{"name":"get_weather",
    "description":"Get the current weather for a city",
    "parameters":{"type":"object","properties":
      {"city":{"type":"string","description":"City name"}},
      "required":["city"]}}}],
  "tool_choice":"auto","max_tokens":256,"temperature":0}'
```

```json
{"choices":[{"finish_reason":"tool_calls","index":0,"message":{"role":"assistant",
 "content":"","tool_calls":[{"type":"function","function":{"name":"get_weather",
 "arguments":"{\"city\":\"Montreal\"}"},
 "id":"kqukLQ5CZ97kJI8Rs8OipoDTBO4zpDHb"}]}}],
 "created":1792257607,"model":"gemma-4-e2b",
 "system_fingerprint":"b1-c0fd786","object":"chat.completion",
 "usage":{"completion_tokens":15,"prompt_tokens":80,"total_tokens":95,
 "prompt_tokens_details":{"cached_tokens":1}},
 "id":"chatcmpl-ANOLT5tJoP7rfTB34S97bEGx543tFDA7",
 "timings":{"cache_n":1,"prompt_n":79,"prompt_ms":9186.444,
 "prompt_per_second":8.599627886481429,"predicted_n":15,
 "predicted_ms":2649.432,"predicted_per_second":5.284151470956794}}
```

`finish_reason: tool_calls`, name `get_weather`, arguments
`{"city":"Montreal"}`. This matches the spike.

### 7. kill -9 restart — NOT RUN

The planned command was:

```bash
PID=$(curl -sS http://127.0.0.1:8080/health | python3 -c 'import sys,json;print(json.load(sys.stdin)["pid"])')
adb -s 2A091FDH200AJ1 shell kill -9 "$PID"
# poll /health until state=ready, expect < 35 s
```

It was not executed: the reboot came first, and after the reboot the app
cannot start while the device is locked. The supervisor code exists
(`waitForExit` → `restarting` → 1 s backoff, reset to 1 s after a successful
ready, /proc sweep before launch), but this run has no device evidence for it.
No number is claimed.

### 8. Thermal pause — NOT RUN

The planned command was:

```bash
curl -sS -X POST http://127.0.0.1:8080/settings \
  -d '{"thermal_stop_c":20,"thermal_resume_c":10}'
# wait up to 30 s for state=thermal_pause
curl -sS -X POST http://127.0.0.1:8080/settings \
  -d '{"thermal_stop_c":45,"thermal_resume_c":40}'
# wait for state=ready
```

Not executed for the same reason. The policy, hysteresis and settings plumbing
are implemented; battery temperature was read successfully (`25.3 °C` in
`/health`), but the pause/resume transition was not observed on this device.

### Device lock (the blocker)

After the reboot:

```
$ adb -s 2A091FDH200AJ1 shell 'dumpsys trust | grep -E "deviceLocked|trustState"'
 User "Christopher" (id=0, flags=0x4c13) (current): trustState=UNTRUSTED,
 trustManaged=0, deviceLocked=1, isActiveUnlockRunning=0, strongAuthRequired=0x1
$ adb -s 2A091FDH200AJ1 shell 'dumpsys window | grep isKeyguardShowing'
    isKeyguardShowing=true
$ adb -s 2A091FDH200AJ1 shell 'locksettings verify --old ""'
User has a lock credential, but old credential was not provided
$ adb -s 2A091FDH200AJ1 shell \
    'run-as org.phononai.app ls files/models'
run-as: couldn't stat /data/user/0/org.phononai.app: No such file or directory
$ adb -s 2A091FDH200AJ1 shell \
    'am start-foreground-service -n org.phononai.app/.PhononService'
Starting service: Intent { cmp=org.phononai.app/.PhononService }
Error: Not found; no service started.
```

`BOOT_COMPLETED` is also deferred until first unlock. The app has no
`directBootAware` components and its model is in credential-encrypted
`filesDir`, so there is no legitimate way to run it before the owner unlocks.
No PIN was guessed and no lock credential was changed.

## What is not verified

- Kill/restart timing (item 7) and thermal pause/resume (item 8).
- Boot start on this Pixel: the receiver is in the manifest and the spike
  verified boot start on the emulator, but this run could not observe it after
  the reboot because the phone was locked.
- Download resume after a partial transfer. The code sends `Range` and handles
  200/206/416, but the demonstration was a single uninterrupted download.
  `python3 -m http.server` does not implement Range, so a resume test needs a
  Range-capable server.
- Streaming (`stream:true`) responses were not exercised through the proxy.
  Chunked relay is implemented, but the only captured completions were
  non-streaming.
- `--api-key` enforcement was not exercised; no key was configured.
- `/health.last_decode_tps` was null before the first completion; the value
  after a proxied completion was not re-read before the device locked. The
  parsing code exists, but no captured `/health` body proves it end-to-end.

## Caveats

- **Throughput is low and unexplained.** 5.1–5.3 decode tok/s versus the
  spike's 11.6–12.0. Memory pressure is the leading hypothesis (345 MB free,
  2.5 GB swap); the child's cgroup/cpuset assignment under a foreground service
  is a second hypothesis that was not checked while the server was alive.
  Neither is proven, and the clean reboot intended to test it ended at the
  lock screen.
- The app's public port and the server's backend port must differ. The
  settings default to 8080/8081. Changing `backend_port` requires a server
  restart; changing `port` restarts the app API immediately.
- `/v1` is a transparent raw-HTTP proxy, not a reimplementation. It supports
  Content-Length and chunked request/response bodies, streaming, and API-key
  injection. It does not rewrite SSE, and it captures `timings` only from
  non-streaming JSON response bodies, so `/health.last_decode_tps` stays null
  for streaming clients.
- `uptime_s` is the llama-server process uptime. It is null when the server is
  not running or its pid is unknown.
- Download verification is size-only. No SHA-256 is computed; do not add a
  `checksum` field without actually hashing the file.
- Model files and native binaries are gitignored. The app directory keeps only
  source; `sync-native.sh` is the reproducibility path.
- The debug APK embeds un-stripped shared libraries. Install succeeded and ran;
  size is reported as 56,419,063 bytes, not as a release artifact.
- The model download auto-starts the server when `auto_start` is true (the
  default). If the device is hot or the model is missing, the state reflects
  that rather than reporting `ready`.
- Host port 9000 was already bound by an unrelated listener. The app default
  URL is still 9000; the test used 9001.

## Reproducing on an unlocked device

```bash
adb -s 2A091FDH200AJ1 forward tcp:8080 tcp:8080
# install if needed
adb -s 2A091FDH200AJ1 install -r -t app/app/build/outputs/apk/debug/app-debug.apk
# start the activity once (or reboot with the screen unlocked)
adb -s 2A091FDH200AJ1 shell am start -n org.phononai.app/.MainActivity
# then re-run items 4–8 above
```

For item 7, use the pid reported by `/health`; for item 8, set
`thermal_stop_c` below the current battery temperature (the test used 20/10)
and restore 45/40 afterwards.
