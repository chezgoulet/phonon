# Phonon Cluster — GrapheneOS First-Run Setup

This guide walks through configuring a phone running GrapheneOS for use in a
Phonon inference cluster.

**Prerequisites:**
- Phone is flashed with [GrapheneOS](https://grapheneos.org) (latest stable)
- You have ADB access to the phone
- The phone is connected to power and Ethernet (when possible)

## 1. Initial setup

1. Boot the phone and complete the Android first-run wizard:
   - Skip Google account sign-in (you don't need it)
   - Skip fingerprint setup (optional — this is a server)
   - Skip WiFi — we'll use Ethernet
2. Go to **Settings → About phone** and tap "Build number" 7 times to enable
   Developer options.
3. Go to **Settings → System → Developer options**:
   - Enable **USB debugging**
   - Set **Stay awake** to ON (keeps screen on while charging — useful for
     setup, disable later)
4. Connect the phone to your computer via USB.
5. Verify: `adb devices` should show the device.

## 2. ADB authorization

The first time you connect via ADB, the phone prompts for RSA key
authorization. Check "Always allow from this computer" and accept.

If you're setting up 6 phones:
```bash
# Authorize all phones at once
for device in $(adb devices | grep -w device | cut -f1); do
  echo "Authorizing $device..."
  adb -s "$device" wait-for-device
done
```

**One-time key setup** for the coordinator machine:
```bash
# On the coordinator, generate ADB keys if you don't have them
# (ADB auth directory: ~/.android/ on Linux)
ls -la ~/.android/adbkey*
```

## 3. Install the Phonon Sidecar APK

```bash
# Build or download the sidecar APK
cd phonon/sidecar
ANDROID_NDK=/path/to/ndk ./gradlew assembleRelease

# Install on all connected devices
for device in $(adb devices | grep -w device | cut -f1); do
  echo "Installing on $device..."
  adb -s "$device" install -r app/build/outputs/apk/release/app-release.apk
done
```

## 4. Permissions

### Required permissions

**Notification access** (for foreground service):
```bash
# Grant via ADB — the app needs to show a persistent notification
# (Android 13+ requires runtime permission for notifications)
adb shell appops set com.chezgoulet.phonon POST_NOTIFICATIONS allow
```

**Battery optimization exclusion** (critical — without this, Android kills
the service):

Go to **Settings → Apps → Phonon → Battery → Battery optimization →
Don't optimize**.

Or via ADB:
```bash
adb shell dumpsys deviceidle whitelist +com.chezgoulet.phonon
adb shell cmd appops set com.chezgoulet.phonon RUN_ANY_IN_BACKGROUND allow
```

### Optional permissions

**WiFi/BT scanning** (for mDNS discovery — skip if using Ethernet):
```bash
adb shell appops set com.chezgoulet.phonon ACCESS_FINE_LOCATION allow
```

## 5. Battery optimization

This is the make-or-break step. GrapheneOS is aggressive about battery
management, and it will kill the Phonon service if not whitelisted.

```bash
# Verify battery optimization exclusion
adb shell dumpsys deviceidle | grep -A5 "Whitelist"

# You should see com.chezgoulet.phonon in the user whitelist
```

Also disable:
- **Settings → Battery → Adaptive Battery** (interferes with long-running services)
- **Settings → Apps → Phonon → Open by default → Open supported links**
  → Clear defaults (not needed, but doesn't hurt)

## 6. Boot behavior

The Phonon sidecar is designed to start automatically on boot via a
`BOOT_COMPLETED` broadcast receiver. However, Android 13+ restricts
auto-start behavior.

### Enable auto-start

1. **Settings → Apps → Phonon** → Make sure `Allow background activity` is ON
2. **Settings → Apps → Special app access → Device admin apps** → Not needed
   (we don't use admin features)
3. **Settings → System → Developer options → Always keep Wi-Fi on** → N/A for
   Ethernet

### Verify boot receiver is registered

```bash
adb shell dumpsys package com.chezgoulet.phonon | grep -i boot
# Should show:
#   android.permission.RECEIVE_BOOT_COMPLETED
```

If the receiver isn't firing, check logcat at next boot:
```bash
adb logcat -s PhononService:BootReceiver
```

### ADB reboot pattern for the cluster

```bash
# Reboot all phones in sequence (not all at once — or the switch gets confused)
for device in $(adb devices | grep -w device | cut -f1); do
  echo "Rebooting $device..."
  adb -s "$device" reboot
  sleep 10  # stagger reboots
done
```

## 7. Verify connectivity

Once everything is set up:

```bash
# Check that the sidecar is running
adb shell ps | grep phonon
# Should show the com.chezgoulet.phonon process

# Check logs
adb logcat -s PhononService:CoordinatorClient

# Expected: "Connected to coordinator" and periodic heartbeats
```

On the coordinator, verify registration:
```bash
curl -s http://coordinator:8080/api/v1/cluster/nodes | jq '.data | length'
# Should show 6 nodes
```

## 8. Troubleshooting

### "Phonon keeps stopping"

Most likely cause: battery optimization. Re-check step 5.

```bash
# Re-whitelist
adb shell cmd deviceidle whitelist +com.chezgoulet.phonon
adb shell am force-stop com.chezgoulet.phonon
adb shell am start-foreground-service com.chezgoulet.phonon/.PhononService
```

### "No route to coordinator"

1. Check Ethernet connection: `adb shell ip addr show eth0`
2. Verify DHCP: `adb shell ip route show`
3. Test coordinator reachability: `adb shell ping -c3 <coordinator-ip>`
4. Check mDNS: `adb shell ping -c3 phonon.local` (if using mDNS)

### Service stops after screen off

GrapheneOS may still kill services even with battery whitelist:

```bash
# Keep screen on trick for headless operation
adb shell svc power stayon true

# Or use a type-C to HDMI dummy plug that keeps the phone "active"
```

### Thermal throttling

```bash
# Check thermal stats
adb shell dumpsys thermalservice
# Target: <40°C. If >45°C: add fans, reduce load, or space phones
```

### ADB doesn't see the phone

```bash
# On Linux, check udev rules
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", ATTR{idProduct}=="4ee7", MODE="0666"' \
  | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules
```

Google's vendor ID is `18d1`. For other OEMs see the [GrapheneOS USB guide](https://grapheneos.org/install/cli#enabling-usb-permissions).

### Notification not showing

```bash
# Force notification re-creation
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED \
  -n com.chezgoulet.phonon/.BootReceiver
```

## 9. Maintenance checklist

| Frequency | Task |
|---|---|
| Weekly | Check `curl coordinator:8080/api/v1/cluster/health` — all phones online? |
| Monthly | `adb reboot` each phone to clear memory fragmentation |
| Quarterly | Update GrapheneOS (over-the-air) and re-install Phonon APK |
| As needed | Replace phones with thermal damage (>55°C sustained) |

## See also

- `HARDWARE_SETUP.md` — BOM, racking, power, networking
- `NETWORK_TUNING.md` — Performance tuning for inference traffic (future)
