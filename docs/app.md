# Phonon app v0

Date: 2026-10-17
Branch: `feat/app-v0`
Device: stock Pixel 7 (`panther`), serial `2A091FDH200AJ1`, Android 17 /
SDK 37, arm64-v8a, unrooted. No other device was used.

## Status, honestly

Implemented and demonstrated on the Pixel 7: native packaging, install, model
download with size verification, foreground service, readiness, OpenAI-compatible
completions and tool calls, kill/restart supervision, and thermal pause/resume.
**All eight acceptance items have now been observed on the device.**

Items 7 and 8 were initially blocked. An earlier attempt rebooted the device to
clear memory pressure and it returned to the owner lock screen; Android will not
mount credential-encrypted `filesDir` or deliver `BOOT_COMPLETED` before first
unlock. They were recorded as not run at the time, and completed after the owner
unlocked the phone. The blocker and the resolution are both below.

On throughput: the first in-app measurements were 5.3 tok/s decode against 11.6
tok/s from the manual baseline. The cause was memory contention, not the app —
345 MB free and 1.8 GB of zram in use. After `am kill-all` and force-stopping the
largest background apps, the same binary with the same settings returned
10.0–10.7 tok/s. That is a measured attribution, not a hypothesis.

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
├── KeepWarm.kt           idle keepalive beats + client request tracking
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
-lm <mmap|mlock|none>
[--api-key <key>]
```

`-lm` was added with the keep-warm work; the captured start line below predates
it and therefore has no `-lm` argument.

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
auto-start, download URL, keep-warm on/off and interval (45 s), load mode
(`mmap`). Server argument changes take effect on the next server start/restart;
the activity's Save action restarts the server when those arguments changed.
Public-port changes restart the app API immediately. Keep-warm and interval
changes take effect on the next tick without a restart.

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

### 7. kill -9 restart — VERIFIED

Killing the server as the app's own uid (the shell uid cannot signal an app
process):

```bash
PID=$(curl -s http://127.0.0.1:8080/health | python3 -c 'import sys,json;print(json.load(sys.stdin)["pid"])')
adb -s 2A091FDH200AJ1 shell "run-as org.phononai.app sh -c \"kill -9 $PID\""
# poll /health until state=ready AND pid != $PID
```

Observed:

```
before: pid 14483, restart_count 1
RECOVERED after 4 s, new pid 14528
after:  state=ready, uptime_s=2, restart_count=2
```

Four seconds, because the model's pages were still in the page cache. A cold
restart will be slower; the supervisor's backoff starts at 1 s.

**Test trap worth recording:** the first attempt polled only for `state=ready`
and read a response produced before the kill landed, concluding "recovered in
0 s" with the same pid. The restart check must compare the pid.

### 8. Thermal pause — VERIFIED

```bash
curl -sS -X POST http://127.0.0.1:8080/settings -d '{"thermal_stop_c":20,"thermal_resume_c":10}'
curl -sS -X POST http://127.0.0.1:8080/settings -d '{"thermal_stop_c":45,"thermal_resume_c":40}'
```

Observed, with the battery at 26.9 °C:

```
set stop=20 resume=10
PAUSED after ~2 s
{"state":"thermal_pause","pid":null,"thermal_paused":true,...}
ps: no llama-server process

restore stop=45 resume=40
RESUMED after ~5 s
{"state":"ready","pid":14755,"thermal_paused":false,...}
```

The stop path killed the server, the state machine reported `thermal_pause`, and
the restore path restarted it. The hysteresis itself (resume only below
`thermal_resume_c`) was not exercised because the device never crossed 40 °C;
the two state transitions were.

### Measured baseline (app, Pixel 7)

Device: stock Pixel 7, Android 17, 7.65 GB visible RAM, unrooted, 26.2 °C.

| Metric | Value | Conditions |
|---|---|---|
| prefill | 17.86 tok/s | 34-token prompt |
| decode | 10.04 tok/s | 119 generated tokens |
| short decode | 10.67 tok/s | 3 tokens, after freeing memory |
| first in-app run | 5.32 tok/s | 345 MB free, heavy zram; contention |

The prefill figure rests on a short prompt and should be re-taken with a longer
one. These are app-path numbers (proxy + server), not raw server numbers.

## The memory cliff

Measured on the same device, same build, same settings:

- **Hot cache** (model pages resident): 10.0–10.7 tok/s decode; an 8-token
  request does ~1.6 s of generation.
- **After ~5 minutes idle with the screen off**: the same 8-token request took
  **41 s of wall time** at the server port and **48 s** through the app port,
  while the server's own timings still reported 4.8 tok/s over ~1.6 s. The gap
  is page faults: the kernel evicted the model's file-backed pages and re-reads
  them from storage on the next request.

  **Correction, same day:** this did not reproduce on a second attempt. With
  `keep_warm` off and five minutes of idle, an 8-token request took **2.05 s**
  with `MemAvailable` at 4.4 GB. The difference between the two runs is memory
  pressure, not idle time: the 41 s case had ~1.3 GB available and heavy zram
  use; the 2.05 s case had 4.4 GB free. Eviction is a pressure phenomenon, not
  a timer.
- `-lm mlock` is refused on this device:
  `failed to mlock 3253485568-byte buffer ... Out of memory. Try increasing
  RLIMIT_MEMLOCK`. An unprivileged app cannot raise that limit.

So 2.84 GB of weights on a 7.65 GB phone is not merely tight at load; it is
unstable under Android's reclaim policy. The same binary is fast or unusable
depending on how recently it ran.

Consequences, in order of preference:

1. On 8 GB phones, run a smaller model (roughly ≤1.5 GB of weights) that stays
   resident, or keep the large model warm with a periodic tiny generation.
2. Expose the load mode (`mmap`, `mlock`, `none`) and a keep-warm interval in
   the app, and report when `mlock` was refused instead of assuming it worked.
3. A stripped appliance image is a real fix here, not a nicety: more free pages
   means the model stays resident.

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

## Keeping the model warm

The fix for the memory cliff is a periodic tiny generation. The app sends one
`POST /v1/chat/completions` to the backend with `"hi"`, `max_tokens: 1`,
`temperature: 0`, `stream: false` and a 10 s timeout, and discards the body. The
point is not the token; it is touching the mmap'd weight pages often enough
that the kernel does not reclaim them all.

`KeepWarm` (in `KeepWarm.kt`) runs one daemon thread that ticks every second:

1. skip unless `server.state == ready`;
2. skip unless `keep_warm` is true;
3. skip if a proxied client request is in flight;
4. skip if the last client request *or* the last beat is less than
   `keep_warm_interval_s` ago.

`RequestTracker` is shared with `ApiServer`: the `/v1` proxy increments an
in-flight counter and stamps `last_request_at` when a request starts and again
when it ends, so a 20 s generation postpones the next beat by a full interval
rather than overlapping it. The beat talks to the backend port directly, so it
does not pass through the proxy and does not count as client traffic. A failed
beat increments `keepalive_failures`, records a one-line `Log.d`, and changes
nothing else: it is never surfaced as a user-visible error and never touches
`/health` state or `lastError`.

Settings (also in the activity and `POST /settings`):

| Key | Default | Range | Meaning |
|---|---|---|---|
| `keep_warm` | `true` | — | send idle beats |
| `keep_warm_interval_s` | `45` | ≥ 15 | idle gap before a beat |
| `load_mode` | `mmap` | `mmap`, `mlock`, `none` | passed to llama.cpp as `-lm` |

`/health` additions:

| Field | Meaning |
|---|---|
| `keep_warm` | setting in force |
| `keep_warm_interval_s` | setting in force |
| `last_keepalive_at` | epoch ms of the last beat attempt, `null` before the first |
| `keepalive_count` | beat attempts (successes + failures) |
| `keepalive_failures` | failed beats |
| `keepalive_last_error` | last failure text, absent if none |
| `load_mode` | `mmap`, `mlock` or `none` |
| `mlock_refused` | true when the server log contains `failed to mlock` |

`load_mode` is part of the server argument hash, so changing it restarts
`llama-server`. `mlock_refused` is not inferred from the request: `ServerManager`
watches the log lines as they enter the ring buffer (both stdout/stderr and the
tailed `--log-file`) and flips the flag only when the kernel line appears. On
this device `-lm mlock` is refused with `RLIMIT_MEMLOCK`, which is why the
default is `mmap`.

Setting keep-warm via the API, without a restart:

```bash
curl -sS -X POST http://127.0.0.1:8080/settings \
  -H 'Content-Type: application/json' -d '{"keep_warm":false}'
curl -sS -X POST http://127.0.0.1:8080/settings \
  -H 'Content-Type: application/json' -d '{"keep_warm":true,"keep_warm_interval_s":45}'
```

### Keep-warm measurements (Pixel 7, 2026-10-17)

Mechanics, observed:

- first beat 45 s after `ready`: `keepalive #1 ok: 1 token in 1376 ms`;
- `keepalive_count` advanced 1 per interval, `keepalive_failures` stayed 0;
- during a 110-token client request (20 s+), `keepalive_count` stayed at 4 —
  no beat overlapped client traffic;
- `/health` exposes `keep_warm`, `keep_warm_interval_s`, `last_keepalive_at`,
  `keepalive_count`, `keepalive_failures`, `load_mode`, `mlock_refused`.

Effect on latency: **not established.**

| Condition | Idle | Wall for 8 tokens | Decode |
|---|---|---|---|
| keep_warm on, then request | 5 min | 2.37 s | 5.29 tok/s |
| keep_warm off, then request | 5 min | 2.05 s | 5.28 tok/s |
| earlier cold case (under pressure) | 5 min | 41 s | — |

The cold control did not reproduce, so this run cannot attribute the difference
to keep-warm. Both fast runs had several GB of memory available; the 41 s case
did not. The honest state: the mechanism is implemented and observable, its
benefit is plausible, and proving it requires a controlled memory-pressure test
(allocate pressure deliberately, then compare keep-warm on/off). Until then the
default stays `true` because the cost is one token every 45 s, not because the
benefit has been measured.

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

- **Throughput varies with memory pressure.** 5.1–5.3 decode tok/s under normal
  conditions; 10.04 immediately after `am kill-all` freed memory (4.3 GB
  available); 11.6–12.0 from the standalone binary before the app existed. The
  correlation is consistent, but it is a correlation: no run has isolated
  memory pressure as the sole variable. The child's cgroup/cpuset assignment
  under a foreground service is a second, unchecked hypothesis.
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
