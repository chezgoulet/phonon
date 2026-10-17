# Rules for automated builders

These apply to any agent run against this repository, in addition to the task
brief.

1. **Never reboot, wipe, or factory-reset a test device.** A reboot can land on a
   lock screen, and Android will not mount credential-encrypted app storage or
   deliver `BOOT_COMPLETED` before first unlock. That strands the app and blocks
   every device test. If memory pressure is the problem, stop and say so.
2. **Use only the device serial in the brief.** Never another adb device, and
   never the emulator unless told.
3. **Never guess a lock credential** and never change one. If the device locks,
   stop and report it.
4. **Do not commit binaries, models, or keystores.** `jniLibs/` is populated by
   `app/sync-native.sh`; models live in app storage.
5. **A blocked item is reported as not run**, with the commands that would
   finish it. Never substitute an expected result for an observed one.
6. **Measure the server you will run.** Benchmarks are not serving numbers.
