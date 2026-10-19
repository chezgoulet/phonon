# Submitting Phonon to F-Droid

The metadata file next to this page is the complete submission. It has been
checked with `fdroid lint` from fdroidserver 2.4.5 against fdroiddata's own
`config/categories.yml`, and passes with no findings.

There is no GitLab credential on the House host, so this cannot be opened as a
merge request from here. The steps below take a few minutes in a browser and a
terminal.

## Steps

```sh
# 1. Fork https://gitlab.com/fdroid/fdroiddata (web UI), then:
git clone git@gitlab.com:<your-user>/fdroiddata.git
cd fdroiddata
git checkout -b com.phononai.app

# 2. Add the metadata exactly as it is in this repository
cp <path-to>/docs/fdroiddata/com.phononai.app.yml metadata/com.phononai.app.yml

# 3. Commit and push
git add metadata/com.phononai.app.yml
git commit -m "New app: Phonon (org.phononai.app)"
git push -u origin com.phononai.app
```

Then open a merge request against `fdroid/fdroiddata` from that branch, using
the description below.

## Merge request description

```
New app: Phonon — org.phononai.app

Phonon turns an Android phone into an inference server for local agents. It runs
a pinned llama.cpp build as a foreground service and exposes an
OpenAI-compatible API on the LAN.

The engine is compiled from the pinned submodule during the build; nothing
prebuilt is committed. The build is offline-capable and reproducible: two clean
checkouts at different paths produce the same APK, and the APK published on
GitHub matches a rebuild of the tag in all 120 non-signature entries, so the
`Binaries:`/`AllowedAPKSigningKeys:` flow is intended.

- Licence: Apache-2.0 (app), MIT (llama.cpp)
- One permission that reaches the network: `INTERNET`, used for the LAN server
  the user starts and for downloading model weights to the phone
- No location, storage, contacts or telephony permissions
- No tracking, no ads, no third-party analytics
- NDK: 30.0.16138531 (r30-rc1) — please tell me if the buildserver cannot fetch
  it; I will move to the stable r30 and re-measure before resubmitting

Release: https://github.com/chezgoulet/phonon/releases/tag/v0.1.18
Reproducibility: https://github.com/chezgoulet/phonon/blob/main/docs/REPRODUCIBLE.md
```

## If the buildserver cannot reproduce the APK

Delete the `Binaries:` and `AllowedAPKSigningKeys:` lines and let F-Droid build
and sign it with its own key. The app is still publishable; the F-Droid build
then carries a different signature from the GitHub one, which only prevents
cross-channel updates.

## What the first buildserver run will test

Everything so far was built on our host and on GitHub runners. The buildserver
is a different machine with a different SDK/NDK install; the first run is the
real test of the recipe, not of the app. The two things most likely to need
adjustment are the NDK version (an rc) and whether `prebuild`/`build` commands
run from the repository root as written.
