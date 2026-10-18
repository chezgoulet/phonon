# Kiosk / appliance mode

Date: 2026-10-17
Branch: `feat/kiosk`
Scope decision: [`scope.md`](scope.md)

Phonon runs as a **Device Owner** on a dedicated phone. Device Owner is policy,
not a custom ROM: Lock Task Mode pins the app, the battery-optimisation
exemption keeps the inference service alive, and two user restrictions make the
appliance harder to destabilise in the field.

The HTTP API and the settings semantics are unchanged by kiosk mode.

## What the app does

| Action | API | When |
|---|---|---|
| Report Device Owner | `DevicePolicyManager.isDeviceOwnerApp` | Always, in Settings |
| Enter kiosk | `Activity.startLockTask()` | Button, Device Owner only |
| Leave kiosk | `Activity.stopLockTask()` | Button, while pinned |
| Battery exemption | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Enter kiosk, and a button |
| `DISALLOW_SAFE_BOOT` | `DevicePolicyManager.addUserRestriction` | Enter kiosk |
| `DISALLOW_FACTORY_RESET` | `DevicePolicyManager.addUserRestriction` | Enter kiosk |
| Screen on while serving | `FLAG_KEEP_SCREEN_ON` on the activity | State `ready` (including generating) |

The admin component is:

```
org.phononai.app/.PhononDeviceAdmin
```

It is declared in the manifest with `android.permission.BIND_DEVICE_ADMIN`, a
`device_admin.xml` resource, and the `DEVICE_ADMIN_ENABLED` intent filter. The
receiver component is what Device Owner provisioning binds to; the policies it
may ask for are a separate question, answered below.

`res/xml/device_admin.xml` declares an **empty** `<uses-policies />` set: the
app requests none of the legacy device-admin powers.

### Why the app requests no device-admin policies

Lock Task Mode does not come from a device-admin policy.
`DevicePolicyManager.setLockTaskPackages` throws `SecurityException` unless the
caller is the device owner, the profile owner, or holds
`MANAGE_DEVICE_POLICY_LOCK_TASK` (AOSP `DevicePolicyManager.java`, javadoc on
`setLockTaskPackages`; the dedicated-device guide describes the same
allowlisting step in `developer.android.com/work/dpc/dedicated-devices/lock-task-mode`).
None of `force-lock`, `wipe-data`, `expire-password` or `reset-password` is on
that path.

Those four are destructive powers the app never implements. Requesting them
grants a larger attack surface, and misleads a reader of the manifest, for
nothing. Device Owner here buys Lock Task Mode, the battery-optimisation
exemption and two user restrictions; that needs the **component**, not the
policies.

`Leave kiosk` unpins the activity. It does **not** silently remove the two user
restrictions; they stay until the admin is removed or the phone is reset. That
matches the appliance intent: leaving the foreground view is not the same as
de-provisioning the phone.

## What the app does not control

- **HTTP API / model settings.** Kiosk mode adds no routes and changes no
  setting. A pinned phone serves exactly the same API as an unpinned one.
- **Boot before first unlock.** Models live in credential-encrypted app storage.
  `BOOT_COMPLETED` and the app's files are unavailable until the owner unlocks
  the phone once. No reboot-based provisioning is part of the kiosk flow.
- **OEM battery managers.** The app can request the AOSP exemption; it cannot
  override a vendor "cleaner" or autostart list (see per-OEM notes below).
- **Kernel, GPU governor, or DMA/ION behaviour.** The Mali screen-on constraint
  is still a driver property (`scope.md`), not something Device Owner changes.
- **Updates and bloatware in general.** AOSP restrictions such as
  `DISALLOW_SAFE_BOOT` and `DISALLOW_FACTORY_RESET` are applied. Broader update
  freeze policy is not implemented here.
- **Wipe, password, and lock-screen policy.** The app requests no device-admin
  policies at all (see above): it does not wipe the device, reset passwords,
  change the lock credential, or force a lock. The receiver exists only so the
  platform can bind the Device Owner component.
- **Screen-on while backgrounded.** `FLAG_KEEP_SCREEN_ON` belongs to a visible
  activity. Kiosk mode keeps that activity visible; if the activity is
  backgrounded, the flag is released. The service's `PARTIAL_WAKE_LOCK` keeps
  the CPU running, not the display.

## Provisioning, one phone

Prerequisites:

1. A **fresh** phone or one with **no accounts** and **one user**. Remove
   secondary users and work profiles. Existing Google, Signal, Telegram or
   other accounts make `dpm set-device-owner` fail.
2. The APK installed. Device Owner must be set before the device is considered
   provisioned by the setup wizard; on many builds a phone that has completed
   setup still accepts the command once all accounts are gone.
3. USB debugging on the host, and the phone unlocked.

```bash
adb -s <serial> install -r app/app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell dpm set-device-owner org.phononai.app/.PhononDeviceAdmin
adb -s <serial> shell dpm list-owners
```

A successful `list-owners` contains:

```
User 0: admin=ComponentInfo{org.phononai.app/org.phononai.app.PhononDeviceAdmin}
```

Then open Phonon → **Settings → Kiosk / appliance mode**:

- **Device Owner: yes** is the precondition for the controls.
- **Enter kiosk** applies both user restrictions, pins the activity, and asks
  for the battery-optimisation exemption.
- The row reports **Lock task: pinned** and **Battery optimisation: exempt**
  once each has taken effect.
- **Leave kiosk** calls `stopLockTask()`.

If the command fails, the Settings card prints the command and the warning
instead of pretending kiosk is available. The exact error observed on this
project's Pixel 7 is recorded in [Observed results](#observed-results).

## QR provisioning for bulk

Android's managed-device provisioning accepts a JSON payload as a QR code
scanned by the setup wizard on a freshly reset phone. It can install the APK
and set the Device Owner without a host-side `dpm` command.

Minimum payload shape:

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "org.phononai.app/.PhononDeviceAdmin",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "https://github.com/<org>/phonon/releases/download/<tag>/app-debug.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM":
    "<base64url SHA-256 of the APK>",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
    "<base64url SHA-256 of the signing certificate>",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false,
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
```

Notes:

- Checksums are **base64url, unpadded** SHA-256 digests, not hex.
- The download URL must be HTTPS and reachable by the phone before setup.
- Wi-Fi can be supplied in the same payload
  (`PROVISIONING_WIFI_SSID`, `PROVISIONING_WIFI_PASSWORD`, and the security
  type) so the phone can fetch the APK without manual setup.
- The same account/user constraints apply: a QR scan on a device with an
  existing account is refused by the setup wizard.
- This repository has **not** exercised QR provisioning. Treat the payload
  above as the documented starting point, not as a tested artifact.
- Generating the QR image is deployment tooling, not part of the app. Keep the
  signing-checksum generation in the release script so the QR and the APK
  cannot drift apart.

## Bandwidth and update policy

Kiosk mode is compatible with Obtainium and F-Droid installs. Updating the app
while it is Device Owner keeps the admin component and the ownership; the
package name and signing key must not change. Reproducible builds from one
signing key are what make this safe across channels.

## Per-OEM battery-exemption notes

The AOSP request is the only one the app can issue. Vendor skins add their own
kill lists on top, and a Device Owner does not bypass them. After entering
kiosk, check the vendor setting as well.

| Vendor | Setting path (names vary by version) |
|---|---|
| Pixel / AOSP | Settings → Apps → Phonon → App battery usage → **Unrestricted** |
| Samsung (One UI) | Settings → Battery → Background usage limits → **Never sleeping apps** → add Phonon; turn off **Put unused apps to sleep** |
| Xiaomi / Redmi (MIUI, HyperOS) | Settings → Apps → Manage apps → Phonon → **Battery saver → No restrictions**; enable **Autostart**; lock Phonon in Recents |
| OnePlus / Oppo / Realme (OxygenOS, ColorOS) | Settings → Battery → **App battery management** → Phonon → allow background activity; turn off **Sleep standby optimization**; enable **Auto-launch** |
| Huawei / Honor (EMUI, MagicOS) | Settings → Battery → **App launch** → Phonon → manage manually; allow secondary and background launches |
| Vivo (OriginOS/Funtouch) | Settings → Battery → **Background power consumption management** → Phonon → allow; enable auto-start |

On a pinned appliance the app is normally in the foreground, so the vendor
cleaner matters most across a reboot or after an unexpected unpin. Keep the
phone on a charger; the AOSP exemption does not defeat a vendor thermal or
low-power shutdown.

## Screen-on constraint

`docs/scope.md` records the Mali behaviour: when the screen turns off, the
Vulkan backend parks the GPU and inference hangs. Two mechanisms cover it:

1. **Activity**: `FLAG_KEEP_SCREEN_ON` while `/health` state is `ready`
   (generating included). Kiosk mode keeps the activity visible.
2. **Service**: `PARTIAL_WAKE_LOCK` named `Phonon::llama-server` for the life
   of the foreground service, so the CPU keeps running when the activity is
   not visible.

The flag and the wake lock are independent. Backgrounding the activity drops
the screen-on flag but not the partial wake lock; with kiosk enabled the
activity is pinned, so this does not happen in normal operation.

### Observed on the Pixel 7

With the server `ready`, the activity window reported `fl=KEEP_SCREEN_ON` and
`dumpsys power` listed:

```
PARTIAL_WAKE_LOCK      'Phonon::llama-server' ...
SCREEN_BRIGHT_WAKE_LOCK 'WindowManager/displayId:0' ON_AFTER_RELEASE ...
    ws=WorkSource{10415 org.phononai.app}
```

After `KEYCODE_HOME` the launcher became focused, the
`SCREEN_BRIGHT_WAKE_LOCK` disappeared, and only the partial wake lock remained.
After 20 s `/health` was still `ready`, the app process was alive, and
`libllama-server.so` was still running. The service keeps working when the
activity is backgrounded; the screen-on flag does not apply there. This is why
kiosk pinning matters for the Mali constraint.

`mStayOn=true` also appeared, but the unit has
`mStayOnWhilePluggedInSetting=2` from earlier provisioning, so `mStayOn` alone
is not evidence that the app kept the display awake. The window flag and the
app-attributed screen wake lock are.

## Observed results

Device: stock Pixel 7 (`panther`), serial `<device-serial>`, Android 17.
No reboot and no lock-credential change was performed.

### Device Owner could not be entered on this phone

The phone is the owner's daily driver: `dumpsys account` showed Google, Signal,
Telegram and Pocket Casts accounts, and `pm list users` showed one user. The
exact command and error:

```
$ adb -s <device-serial> shell dpm set-device-owner org.phononai.app/.PhononDeviceAdmin

Exception occurred while executing 'set-device-owner':
java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device.
	at com.android.server.devicepolicy.DevicePolicyManagerService.enforceCanSetDeviceOwnerLocked(DevicePolicyManagerService.java:10613)
	at com.android.server.devicepolicy.DevicePolicyManagerService.setDeviceOwner(DevicePolicyManagerService.java:9323)
	...
```

`dpm list-owners` returned no owners. The Settings card therefore reported
**Device Owner: no** and **Can provision now: no**, and displayed the
provisioning command instead of enabling the kiosk controls. Kiosk mode was
**not** entered or claimed on this device. It needs a fresh phone, or all
accounts removed and a single user, before the same command can be retried.
