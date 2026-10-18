# F-Droid readiness

Current state of the repository against F-Droid main-repo acceptance. This
replaces the 2026-10-17 gap analysis; the blockers recorded there are closed in
the repository.

## Closed

- **Native code is built from source.** llama.cpp is a pinned submodule at
  `third_party/llama.cpp`, commit `797de63b2fa7396dcc8caca76834c699ac84e1c3`.
  `app/build-native.sh` builds it for `arm64-v8a` with `GGML_CPU_REPACK=ON` and
  installs the artifacts into `jniLibs`. Nothing prebuilt is committed;
  `jniLibs/` and `.native-build/` are ignored.
- **`llama-server` packaging.** `app/sync-native.sh` installs the executable as
  `libllama-server.so`, so Android extracts it and the app `exec`s it from
  `nativeLibraryDir`.
- **Gradle wrapper.** `app/gradlew` with the wrapper jar committed and the
  distribution pinned; CI invokes `./app/gradlew -p app`.
- **Version scheme.** `versionCode = 1`, `versionName = "0.1.0"`. The release
  workflow refuses a versionCode that is not greater than the highest previously
  tagged one.
- **Release pipeline.** `.github/workflows/release.yml` checks out submodules,
  installs the pinned NDK, builds the native artifacts, derives the version,
  enforces monotonicity, decodes the keystore and attaches the signed APK.
- **Default branch.** `main` and `testing` carry the release work; the metadata,
  workflow and wrapper are on the default branch.
- **Fastlane metadata.** Title, short and full description, icon, screenshots
  and a changelog for `versionCode = 1` are present.
- **App icon.** Adaptive and legacy launcher icons are in the app and
  `fastlane/.../images/icon.png` is the listing icon.

## Open

1. **NDK pin is a release candidate.** `30.0.16138531` is r30-rc1 and produced
   every measured number. Confirm the F-Droid buildserver's `sdkmanager` can
   fetch it; if not, move to stable `30.0.16248370` in `ndkVersion`,
   `ANDROID_NDK_VERSION` and this document together, and re-run the benchmarks
   before publishing figures.
2. **Reproducible builds are not proven.** Build the APK twice from clean
   checkouts and compare before asking for the upstream-binary flow.
3. **The `fdroiddata` metadata file is not written yet.** It needs
   `submodules: true`, a `prebuild` running `./app/build-native.sh`, the NDK
   pin, `versionCode`/`versionName`, and the licence.
4. **Screenshots should be refreshed** to the current UI (six avatar shapes,
   five model families) before submission.
5. **Signing decision.** F-Droid signs with its own key by default. Keeping one
   signature across GitHub Releases, Obtainium and F-Droid requires reproducible
   builds plus `AllowedAPKSigningKeys`; decide before an install base exists.
6. **Device-admin policy breadth.** `res/xml/device_admin.xml` requests
   `force-lock`, `wipe-data`, `expire-password`, `reset-password` while the
   receiver implements no password/wipe callbacks. Trim the unused policies and
   keep the kiosk rationale in the description.
7. **NOTICE should carry the llama.cpp licence text and the pinned commit**, not
   only the name.

## Notes

- F-Droid names changelogs by **versionCode**, so
  `fastlane/metadata/android/en-US/changelogs/1.txt` belongs to `versionCode = 1`.
- ABI is arm64-v8a only; no split versionCodes.
- DeviceAdmin is **not** an F-Droid anti-feature; do not add the key. Explain the
  behaviour in the description and `MaintainerNotes` instead.
- Models are downloaded on user request and are data, not executables; Qwen3.5,
  Gemma 4, MiniCPM5, Granite and Nemotron are all permissively licensed.
