# Koggles

Koggles is an Android music visualizer built on [projectM](https://github.com/projectM-visualizer/projectm),
the open-source reimplementation of Winamp's MilkDrop. It reacts to whatever your phone is playing
and runs full screen, or in **goggles mode**: side-by-side with lens correction, for phone VR mounts.

- **Bundled presets:** the [Cream of the Crop](https://github.com/projectM-visualizer/presets-cream-of-the-crop)
  pack (about 9800 presets) and the MilkDrop texture pack.
- **Your own packs:** choose a folder once (for example `Documents/Koggles/<pack>/…`) and its
  presets join the rotation.
- **Gestures:** tap for the next preset; double-tap for the menu (playback, packs and browser,
  display, library, audio).
- **Audio:** device audio only. *Standard* uses Android's Visualizer. *High quality*
  (opt-in) uses playback capture: "Higher fidelity; some apps block it and it asks again after
  locking".

## Downloads

Signed APKs are published on the repository's
[Releases](https://github.com/carterchadams/koggles/releases) page for each `vX.Y.Z` tag. Each
release has four files:

- `koggles-vX.Y.Z.apk`
- `koggles-vX.Y.Z.apk.sha256`
- `koggles-vX.Y.Z-mapping.txt`, the R8 mapping for crash reports
- `koggles-vX.Y.Z-source.tar.gz`, the complete corresponding source, submodules included

To verify a download before installing:

```sh
sha256sum -c koggles-vX.Y.Z.apk.sha256
```

Installing is ordinary sideloading. Android asks you to allow installs from the app that opens
the APK (a browser or file manager), then confirms the install. Every release is signed with the
same key, so newer releases install over older ones. A debug build signed with a different key
must be uninstalled first.

## Building

Prerequisites (details in [`docs/verification/toolchain.md`](docs/verification/toolchain.md)):

- JDK 21
- Android SDK packages: `platforms;android-36`, `platforms;android-35`, `build-tools;36.0.0`,
  `ndk;29.0.14206865`, `cmake;3.22.1` and `platform-tools`

```sh
git clone --recursive https://github.com/carterchadams/koggles.git
cd koggles
source tools/env.sh            # JAVA_HOME, ANDROID_HOME, PATH
./gradlew :app:installDebug    # debug build onto a connected phone or emulator
```

The first build clones the bundled content repositories at pinned commits and builds the
Cardboard SDK from its submodule.

**Release builds** are signed when a `keystore.properties` exists at the repository root. It is
gitignored and must never be committed:

```properties
storeFile=/path/to/koggles-release.jks
storePassword=…
keyAlias=koggles
keyPassword=…
```

Then run `./gradlew :app:assembleRelease`. Without the file, the release APK is left unsigned and
the build prints a warning. Publishing releases is described in [RELEASING.md](RELEASING.md).

**Tests:**

| Suite | Command |
|---|---|
| Unit | `./gradlew :app:testDebugUnitTest` |
| Instrumented (device or emulator) | `./gradlew :app:connectedDebugAndroidTest` |
| Host C++ | `cmake -S native-tests -B build/host-tests && cmake --build build/host-tests -j && ctest --test-dir build/host-tests` |

## Replacing libprojectM

projectM is LGPL-2.1 and ships as a separate shared library (`lib/arm64-v8a/libprojectM-4.so`).
To use a modified projectM:

1. Unpack a release's `koggles-vX.Y.Z-source.tar.gz`. It contains the exact projectM revision
   under `third_party/projectm`.
2. Change projectM there.
3. Build with your own signing key (`keystore.properties`, above):
   `./gradlew :app:assembleRelease`.

Android requires every APK to be signed, so a rebuilt APK carries your key, not the original.

## Project records

- [`plans/BLUEPRINT.md`](plans/BLUEPRINT.md): the engineering plan the app was built from, with
  [`AGENTS.md`](AGENTS.md).
- [`docs/superpowers/specs/2026-10-11-koggles-design.md`](docs/superpowers/specs/2026-10-11-koggles-design.md):
  the design spec.
- [`docs/verification/`](docs/verification/): gate records G1–G4 and the manual checklist, plus the execution ledgers
  and RED/GREEN test evidence.
- [`docs/diagnosis/`](docs/diagnosis/): field report 1 diagnosis plan, diagnosis, probes and evidence.
- [`docs/process/README.md`](docs/process/README.md): how the agentic workflow ran, with links to
  the records.
- [`docs/codex-review/`](docs/codex-review/) and [`docs/claude-response/`](docs/claude-response/):
  the review history.

## Licenses

- **projectM:** LGPL-2.1, used as a shared library.
- **Google Cardboard SDK:** Apache-2.0.
- **Cream of the Crop presets:** released by their authors without a specific license; the pack
  maintainers treat them as public domain and remove presets on request. See
  `assets/licenses/cream-of-the-crop-LICENSE.md` in the app.
- **MilkDrop texture pack:** the textures shipped with MilkDrop plus others used by presets.

All licence texts are in the app under *Library → About & licenses*.

## Known limits

- **Device audio:** some apps block playback capture, and the Visualizer API reacts differently
  across phones and audio routes. The hint "No audio detected — some apps block capture" appears
  after 10 s of silence.
- **High quality on Android 15+:** locking the phone ends the capture session, so High quality
  asks for consent again (one tap) on return. Standard does not.
- **Gate records:** headset fit (G3) and rendering performance on a physical phone (G4) are still
  pending; see `docs/verification/`.
