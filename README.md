# Koggles

![Koggles: a person in a phone VR headset in front of neon music visuals](images/KogglesREADMEBanner.png)

Koggles is an Android music visualizer built on [projectM](https://github.com/projectM-visualizer/projectm),
the open-source reimplementation of Winamp's MilkDrop. It reacts to whatever your phone is playing
and runs full screen, or in **goggles mode**: side-by-side with lens correction, for phone VR mounts
such as Google Cardboard and its many look-alikes.

- **Bundled presets:** the Koggles Starter Pack, a selection of 299 presets from projectM's
  [classic preset pack](https://github.com/projectM-visualizer/presets-projectm-classic), four
  transition presets, and the MilkDrop texture pack.
- **Your own packs:** choose a folder once (for example `Documents/Koggles/<pack>/…`) and its
  presets join the rotation.
- **Gestures:** tap for the next preset; double-tap for the menu (playback, packs and browser,
  display, library, audio).
- **Audio:** device audio only. *Standard* uses Android's Visualizer and needs one ordinary
  permission. *High quality* (opt-in) uses playback capture: "Higher fidelity; some apps block it
  and it asks again after locking".

## How this was built

Koggles is a rapid development cycle built on several weeks of experience with a prior prototype,
which was a direct extension of projectM's legacy reference Android app. The prototype showed what
mattered (lens spacing, a usable menu, audio that works without ceremony) and what hurt (carrying
local modifications to projectM). This repo started over with those lessons and three decisions:

1. **Use projectM unmodified**, as a pinned shared library, instead of modifying a fork as the
   prototype did. Every projectM behaviour Koggles needs is reached through its public API and
   build options; a modified projectM can still be dropped in (see "Replacing libprojectM").
2. **Use Google's Cardboard SDK** for lens distortion and viewer profiles (QR scan), to make the
   goggles experience ergonomic rather than hand-tuned.
3. **Offer two audio capture paths:** a low-fidelity one with easy device permissions
   (Android's Visualizer), and a high-fidelity one (playback capture) whose permissions are more
   finicky and re-prompt on some Android versions. The user picks.

The app was then built by AI coding agents (Claude Code) working from a written spec and a
task-by-task engineering plan, with the author directing. Each task followed the same loop: write
the failing test first, make it pass, then submit the change to an independent adversarial
reviewer (OpenAI Codex) and revise until it approved. Phases ended with device gates. Most checks
that needed a real phone were automated over USB (adb-driven installs, instrumented tests, log and
pixel capture on a Samsung Galaxy S25 FE), with the author doing quick hands-on confirmation of
real-world behaviour: menu scrolling with a finger, goggles mode in a mount, audio from other apps.

The process is only worth describing because of what it caught. Some examples:

- **A wrong "no fix needed" claim.** The agent reported that every bundled preset compiled its
  shaders, based on projectM's load callback. The reviewer pointed out that projectM silently
  substitutes fallback shaders on failure, so the callback proved nothing. A diagnostics build was
  added that captures projectM's own shader messages; only then was the claim re-measured
  (and, that time, confirmed).
- **A plan revision that was never committed.** The agent's commit message said the plan had been
  revised; only the response notes had changed. The reviewer compared the commit against the base
  and refused to proceed until the real revision landed.
- **A gate marked PASS on emulator-only evidence.** The audio gate was labelled passed while its
  own record listed phone-only checks as untested. The reviewer caught it; the record was corrected
  to say exactly what had been tested.
- **A "flaky" test that was a real bug.** A timing test failed one run in three. Instead of
  loosening it, the author asked what real problem it could indicate: projectM randomises each
  preset's duration by default, so the "30 s" setting was really 28–32 s. One API call fixed it.
- **A phantom second bug from a bad measurement.** The menu-scrolling diagnosis blamed a second
  cause because its metric (a screen-text dump) misreported a plain list as not scrolling. The
  reviewer flagged the gap in evidence; re-measuring from the list's own state showed one cause.
- **Rules the agent broke and then reported.** The agent used `git add -A` despite an explicit
  rule against it, and made dozens of small decisions it had been told to raise with the author.
  Its own review of the build admitted both, and an audit of every commit followed.

Confirmed on the phone by the author: headset fit with the default settings, and Bluetooth audio
driving the visuals. Still open: rendering performance on the phone has not been measured in
goggles mode, transition durations have not been timed, and the release pipeline has not yet run
on GitHub.

## Downloads

Latest release, for a direct download:
<https://github.com/carterchadams/koggles/releases/download/current/koggles-current.apk>
(and its `koggles-current.apk.sha256`). That link always serves the newest release's APK under a
fixed name; the `current` release is refreshed by every release.

Signed APKs are published on the repository's
[Releases](https://github.com/carterchadams/koggles/releases) page for each `vX.Y.Z` tag. Each
release has six files:

- `koggles-vX.Y.Z.apk`
- `koggles-vX.Y.Z.apk.sha256`
- `koggles-vX.Y.Z-mapping.txt`, the R8 mapping for crash reports
- `koggles-vX.Y.Z-source.tar.gz`, the complete corresponding source, submodules included
- `koggles-current.apk` and `koggles-current.apk.sha256`, the same APK under the fixed name above

To verify a download before installing:

```sh
sha256sum -c koggles-vX.Y.Z.apk.sha256
```

Installing is ordinary sideloading. Android asks you to allow installs from the app that opens
the APK (a browser or file manager), then confirms the install. Every release is signed with the
same key, so newer releases install over older ones. A debug build signed with a different key
must be uninstalled first.

## Building

Prerequisites:

- JDK 21
- Android SDK at `$HOME/Android/Sdk` (or `ANDROID_HOME`) with `platforms;android-36`,
  `platforms;android-35`, `build-tools;36.0.0`, `ndk;29.0.14206865`, `cmake;3.22.1` and
  `platform-tools`

```sh
git clone --recursive https://github.com/carterchadams/koggles.git
cd koggles
source tools/env.sh            # JAVA_HOME, ANDROID_HOME, PATH
./gradlew :app:installDebug    # debug build onto a connected phone or emulator
```

The first build fetches the bundled presets and textures from their pinned upstream commits,
verifies every preset against `app/bundled-presets.txt` by SHA-256, and builds the Cardboard SDK
from its submodule.

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
| Host C++ (with TSAN) | `cmake -S native-tests -B build/host-tests && cmake --build build/host-tests -j && ctest --test-dir build/host-tests` |
| Bundled-preset shader gate | `./gradlew :app:connectedDebugAndroidTest -PkogglesShaderDiagnostics -Pandroid.testInstrumentationRunnerArguments.class=dev.koggles.BundledPresetsCompileTest` |
| Release tooling | `tools/ci/test-*.sh` |

Useful build switches for device work: `-PkogglesAppIdSuffix=.sweep` installs a build beside the
real app instead of replacing it; `-PkogglesShaderDiagnostics` turns on projectM's own
preset-debug output and captures it, so tests can tell a preset whose shaders fell back from one
that compiled.

## Replacing libprojectM

projectM is LGPL-2.1 and ships as a separate shared library (`lib/arm64-v8a/libprojectM-4.so`).
To use a modified projectM:

1. Unpack a release's `koggles-vX.Y.Z-source.tar.gz`. It contains the exact projectM revision
   under `third_party/projectm`.
2. Change projectM there.
3. Build with your own signing key (`keystore.properties`, above):
   `./gradlew :app:assembleRelease`.

Android requires every APK to be signed, so a rebuilt APK carries your key, not the original.

## Licenses

- **Koggles:** MIT, see [LICENSE](LICENSE).
- **projectM:** LGPL-2.1, used unmodified as a shared library; the corresponding source ships
  with every release.
- **Google Cardboard SDK:** Apache-2.0.
- **Koggles Starter Pack presets:** a selection from projectM's
  [classic preset pack](https://github.com/projectM-visualizer/presets-projectm-classic), whose
  repository states no license, plus four transition presets from Cream of the Crop. They are
  distributed under the projectM team's statement that MilkDrop presets are safe to treat as
  public domain, and that it removes presets on request (`LICENSE.md` in its
  `presets-cream-of-the-crop` repository). The app quotes it verbatim in
  `assets/licenses/koggles-starter-pack-presets.md`.
- **MilkDrop texture pack:** the textures shipped with MilkDrop plus others used by presets; the
  pack states no license.

All licence texts are in the app under *Library → About & licenses*.

## Known limits

- **Device audio:** some apps block playback capture, and the Visualizer API reacts differently
  across phones and audio routes. The hint "No audio detected — some apps block capture" appears
  after 10 s of silence. If another app holds the Visualizer, Koggles says so and retries until
  it is free.
- **High quality on Android 15+:** locking the phone ends the capture session, so High quality
  asks for consent again (one tap) on return. Standard does not.
- **In the headset:** there is no in-headset menu yet; settings, lens spacing and preset changes
  mean taking the phone out of the mount. Frame timing in goggles mode on a physical phone has
  not been measured.
