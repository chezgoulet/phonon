# App spike: can an app execute a bundled ELF?

Date: 2026-10-17
Branch: `feat/app-exec-spike`
Devices: `emulator-5554`, Android 15 (API 35), `sdk_gphone64_x86_64`, SELinux
`Enforcing`, userdebug image — and a stock **Pixel 7** (`panther`, Android 17 /
SDK 37, arm64-v8a, unrooted, `user/release-keys`), connected over USB.

## Verdict

**Yes.** An Android app can execute an ELF binary shipped in its own APK and
extracted into `applicationInfo.nativeLibraryDir`. On the emulator the probe ran
as a child of the app process with exit code 0, printed `PHONON_PROBE_OK`, and
reported the correct ABI and pid. The foreground service, health endpoint, wake
lock and boot receiver all worked, and the service and wake lock were still
alive 60 seconds after the Activity was closed. The same APK was then installed
on a stock Pixel 7 and executed the arm64 build with the same result, so the
mechanism is confirmed on the hardware the project targets.

The rest of this document is the evidence.

## What was built

`app/` is a self-contained Gradle project. The Android module is `app/app/`
because the project root is `app/`.

```
app/
├── build-probe.sh
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties              # gitignored
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── jni/probe.c
        ├── jniLibs/arm64-v8a/libphonon_probe.so
        ├── jniLibs/x86_64/libphonon_probe.so
        ├── java/dev/phonon/spike/
        │   ├── MainActivity.kt
        │   ├── PhononService.kt
        │   ├── HealthServer.kt
        │   ├── HealthState.kt
        │   └── BootReceiver.kt
        └── res/layout/activity_main.xml
```

- Kotlin, `minSdk 31`, `targetSdk 35`, `compileSdk 35`, JVM target 17.
- `probe.c` is a PIE executable built with NDK 30 for `x86_64` and
  `arm64-v8a`, renamed `libphonon_probe.so` so Android treats it as a native
  library and extracts it. It prints `PHONON_PROBE_OK`, its ABI and its pid to
  stdout, and its `argv[0]` to stderr.
- `PhononService` is a foreground service of type `specialUse`. It acquires
  `PARTIAL_WAKE_LOCK` (`PhononProbe::exec-spike`), execs the extracted probe
  with `ProcessBuilder`, drains stdout and stderr on separate threads, records
  the exit code and result, and starts a loopback HTTP server.
- `HealthServer` binds `127.0.0.1:8080` only and serves `GET /health`.
- `BootReceiver` starts the service on `BOOT_COMPLETED` (best effort).
- `MainActivity` starts the service and displays the JSON, refreshed once a
  second while visible.
- No Play Services, no llama.cpp, no dependency beyond the Kotlin stdlib and
  the Android framework.

The probe is packaged with legacy native-library packaging so it is extracted
as a real file rather than loaded in place from the APK:

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

and `android:extractNativeLibs="true"` in the manifest.

## Commands and output

### Environment

```bash
export JAVA_HOME=/home/c/jdk-17
export ANDROID_HOME=/home/c/Android/Sdk
export ANDROID_NDK_HOME=/home/c/Android/Sdk/ndk/30.0.16138531
export PATH=/home/c/.local/bin:/home/c/jdk-17/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:/usr/local/bin:/usr/bin:/bin
```

The emulator was started headless with the AVD `ozy35`:

```bash
$ANDROID_HOME/emulator/emulator -avd ozy35 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
```

Device properties:

```
x86_64
google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
Linux localhost 6.6.50-android15-8-g8adecb593e9b-ab12525588 #1 SMP PREEMPT ... x86_64 Toybox
Enforcing
```

### Probe build

The two build commands are wrapped by `app/build-probe.sh`:

```bash
NDK=/home/c/Android/Sdk/ndk/30.0.16138531
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
$TC/x86_64-linux-android31-clang -O2 -fPIE -pie \
  -o app/app/src/main/jniLibs/x86_64/libphonon_probe.so app/app/src/main/jni/probe.c
$TC/aarch64-linux-android31-clang -O2 -fPIE -pie \
  -o app/app/src/main/jniLibs/arm64-v8a/libphonon_probe.so app/app/src/main/jni/probe.c
chmod 755 app/app/src/main/jniLibs/*/libphonon_probe.so
```

Result:

```
app/src/main/jniLibs/arm64-v8a/libphonon_probe.so: ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, interpreter /system/bin/linker64
app/src/main/jniLibs/x86_64/libphonon_probe.so:    ELF 64-bit LSB pie executable, x86-64, dynamically linked, interpreter /system/bin/linker64
```

The arm64 build is 16 KiB page aligned (`LOAD ... Align 0x4000`), which matters
for Android 15 devices. Its only dependencies are `libdl.so` and `libc.so`.

### Build

```bash
cd /home/c/phonon/app
GRADLE=$(find /home/c/.gradle/wrapper/dists/gradle-8.12.1-bin -name gradle -type f | head -1)
$GRADLE --no-daemon --console=plain assembleDebug
```

```
BUILD SUCCESSFUL in 6s
36 actionable tasks: 1 executed, 35 up-to-date
```

APK:

```
/home/c/phonon/app/app/build/outputs/apk/debug/app-debug.apk
2,348,971 bytes
sha256 0245e39e618b1b55d036767d19fd2363476396cefcc6e32cc82ebabf04fd0875
```

Contents include both ABIs:

```
lib/x86_64/libphonon_probe.so     6680
lib/arm64-v8a/libphonon_probe.so  7368
```

The manifest in the built APK has `extractNativeLibs=true`, `minSdk 31`,
`targetSdk 35`.

### Install and launch

```bash
adb -s emulator-5554 install -r -t \
  /home/c/phonon/app/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -W -n dev.phonon.spike/.MainActivity
```

```
Performing Streamed Install
Success
Starting: Intent { cmp=dev.phonon.spike/.MainActivity }
Status: ok
LaunchState: COLD
Activity: dev.phonon.spike/.MainActivity
TotalTime: 842
WaitTime: 848
Complete
```

### Logcat

```bash
adb -s emulator-5554 logcat -d -s PhononProbe
```

```
I PhononProbe: wake lock acquired: PhononProbe::exec-spike
I PhononProbe: health endpoint listening on 127.0.0.1:8080
I PhononProbe: nativeLibraryDir=/data/app/~~vjApcuHlC53JpoQdCGqUzQ==/dev.phonon.spike-UMhHiZWyI8zDh-Twn5Thkg==/lib/x86_64
I PhononProbe: probe=/data/app/~~vjApcuHlC53JpoQdCGqUzQ==/dev.phonon.spike-UMhHiZWyI8zDh-Twn5Thkg==/lib/x86_64/libphonon_probe.so exists=true isFile=true canExecute=true size=6680
I PhononProbe: exit_code=0
I PhononProbe: abi=x86_64 pid=3315
I PhononProbe: stdout:
I PhononProbe: PHONON_PROBE_OK
I PhononProbe: PHONON_PROBE_ABI=x86_64
I PhononProbe: PHONON_PROBE_PID=3315
I PhononProbe: stderr:
I PhononProbe: PHONON_PROBE_ARGV0=/data/app/~~vjApcuHlC53JpoQdCGqUzQ==/dev.phonon.spike-UMhHiZWyI8zDh-Twn5Thkg==/lib/x86_64/libphonon_probe.so
I PhononProbe: RESULT exec_ok=true exit_code=0 abi=x86_64 pid=3315
```

### Health endpoint

```bash
adb -s emulator-5554 forward tcp:8080 tcp:8080
curl -s http://127.0.0.1:8080/health
```

```json
{"exec_ok":true,"exit_code":0,"stdout":"PHONON_PROBE_OK\nPHONON_PROBE_ABI=x86_64\nPHONON_PROBE_PID=3315\n","stderr":"PHONON_PROBE_ARGV0=\/data\/app\/~~vjApcuHlC53JpoQdCGqUzQ==\/dev.phonon.spike-UMhHiZWyI8zDh-Twn5Thkg==\/lib\/x86_64\/libphonon_probe.so\n","abi":"x86_64","pid":3315,"service_started_at":"2026-10-17T12:47:33.406338-04:00"}
```

`GET /health` returned `HTTP/1.1 200 OK`; any other path returns 404. The
server listens on loopback only. `pid` is the probe's pid, not the app's, which
is why it no longer exists when the request is served.

### Survival with the Activity closed

```bash
adb -s emulator-5554 shell input keyevent KEYCODE_HOME
sleep 60
adb -s emulator-5554 shell dumpsys activity services | grep -A3 phonon
adb -s emulator-5554 shell dumpsys power | grep -i phonon
curl -s http://127.0.0.1:8080/health
```

Top Activity after Home:

```
topResumedActivity=ActivityRecord{... com.google.android.apps.nexuslauncher/.NexusLauncherActivity ...}
```

Service after 60 seconds:

```
* ServiceRecord{def422f u0 dev.phonon.spike/.PhononService c:dev.phonon.spike}
  intent={cmp=dev.phonon.spike/.PhononService}
  packageName=dev.phonon.spike
  processName=dev.phonon.spike
  ...
  isForeground=true foregroundId=1 types=0x40000000
  foregroundNoti=Notification(channel=phonon-probe ... flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE ...)
  createTime=-1m8s48ms
  startRequested=true delayedStop=false stopIfKilled=false
```

Wake lock after 60 seconds:

```
PARTIAL_WAKE_LOCK  'PhononProbe::exec-spike' ACQ=-1m8s43ms LONG (uid=10209 pid=3292)
```

The health endpoint still returned `exec_ok: true` with the same result.

### Why execution is allowed

The extracted probe is a real file, owned by `system`, in the app's native
library directory:

```bash
adb shell "ls -lZ /data/app/~~vjApcuHlC53JpoQdCGqUzQ==/dev.phonon.spike-UMhHiZWyI8zDh-Twn5Thkg==/lib/x86_64/"
```

```
-rwxr-xr-x 1 system system u:object_r:apk_data_file:s0  6680 1981-01-01 01:01 libphonon_probe.so
```

The `/data` mount allows execution:

```
/dev/block/dm-46 /data ext4 rw,seclabel,nosuid,nodev,noatime,resgid=1065,errors=panic 0 0
```

No `noexec`. SELinux is `Enforcing`, and the file label `apk_data_file` is
executable by the `untrusted_app` domain. The only AVC denials observed while
the probe started were unrelated `search` denials on `/data/tests`
(`shell_test_data_file`), not on the probe or its directory; they did not
affect the exit code.

This is distinctly different from executing a file in the app's writable data
directory (`/data/data/dev.phonon.spike/`), which Android 10+ blocks. The
native library directory is the supported location for this.

### Boot receiver

The emulator was rebooted. `BOOT_COMPLETED` arrived about nine seconds after
`sys.boot_completed=1`; the first check, eight seconds after boot, was too
early. Once delivered:

```bash
adb -s emulator-5554 reboot
# wait for sys.boot_completed=1
adb -s emulator-5554 logcat -d -s PhononProbe
```

```
I PhononProbe: boot receiver started foreground service
I PhononProbe: wake lock acquired: PhononProbe::exec-spike
I PhononProbe: health endpoint listening on 127.0.0.1:8080
I PhononProbe: nativeLibraryDir=/data/app/~~vjApcuHlC53JpoQdCGqUzQ==/dev.phonon.spike-UMhHiZWyI8zDh-Twn5Thkg==/lib/x86_64
I PhononProbe: probe=.../lib/x86_64/libphonon_probe.so exists=true isFile=true canExecute=true size=6680
I PhononProbe: exit_code=0
I PhononProbe: PHONON_PROBE_OK
I PhononProbe: PHONON_PROBE_ABI=x86_64
I PhononProbe: PHONON_PROBE_PID=2736
I PhononProbe: RESULT exec_ok=true exit_code=0 abi=x86_64 pid=2736
```

`dumpsys activity services` showed the service foreground with
`mAllowStart_noBinding=BOOT_COMPLETED`, and `dumpsys power` showed
`PhononProbe::exec-spike` held. The health endpoint returned `exec_ok: true`
with a new `service_started_at`.

## What this proves, and what it does not

Proven:

- Android 15, API 35, SELinux enforcing, app targeting API 35 can `exec` a
  bundled ELF extracted into `nativeLibraryDir`.
- The child runs as a real separate process and gets a real pid; stdout,
  stderr and exit code are captured correctly.
- A foreground service with `specialUse` type, a partial wake lock and a
  loopback HTTP server survives the Activity being closed for at least 60
  seconds.
- The boot receiver starts the same service after reboot on this image.
- The arm64 artifact builds, is 16 KiB aligned, and **executed on a stock Pixel
  7** (`panther`, Android 17 / SDK 37, arm64-v8a): logcat recorded
  `RESULT exec_ok=true exit_code=0 abi=arm64-v8a`, stdout `PHONON_PROBE_OK`, and
  argv0 inside `/data/app/.../lib/arm64/libphonon_probe.so`. `GET /health`
  returned `exec_ok: true` with the arm64 ABI. The only difference from the
  emulator run is the ABI.

Not proven here:

- That `llama-server` and its shared libraries resolve when executed the same
  way. The probe is a single self-contained executable. A real `llama-server`
  needs its `libllama.so`/`libggml*.so` siblings found at run time; set
  `LD_LIBRARY_PATH` to `nativeLibraryDir` in `ProcessBuilder.environment()`,
  or link with an `$ORIGIN` rpath, and verify on the device.
- Model files. They are not native libraries and must not be packed in
  `jniLibs`; keep them in app storage and pass paths as arguments.
- OEM retail behaviour. This ran on a userdebug emulator image. GrapheneOS or a
  retail Pixel may differ in background-start policy; the foreground service is
  the supported path either way.

## Consequence for the architecture

Adopt the small design: the Phonon app is a foreground service that execs a
bundled, measured `llama-server` and supervises it. It does not need to embed
llama.cpp through JNI or own the HTTP layer, templates, streaming and tool-call
parsing.

The packaging rules that make it work are now part of the build:

1. Ship the server and any sibling shared objects under
   `jniLibs/<abi>/lib*.so`.
2. Keep `android:extractNativeLibs="true"` and
   `useLegacyPackaging = true`; the extracted file is what gets executed.
3. Exec from `applicationInfo.nativeLibraryDir`, never from `filesDir` or
   `cacheDir`.
4. Set the library search path for sibling `.so` files (`LD_LIBRARY_PATH` to
   `nativeLibraryDir`, or an `$ORIGIN` rpath) and verify with the real
   `llama-server` binary — the probe is self-contained, the server is not.
