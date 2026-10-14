# Koggles

**Immersive music visuals for Android, on your screen or in a phone headset.**

[![Koggles: a person in a phone VR headset in front of neon music visuals](images/KogglesREADMEBanner.png)](https://github.com/carterchadams/koggles/releases/download/current/koggles-current.apk)

Koggles reacts to whatever your phone is playing and runs full screen, or in **goggles mode**:
side-by-side with lens correction, for phone VR mounts such as Google Cardboard and its many
look-alikes. The visuals come from [projectM](https://github.com/projectM-visualizer/projectm),
the open-source reimplementation of Winamp's MilkDrop, with 299 presets bundled and room for your
own.

[**Download for Android**](https://github.com/carterchadams/koggles/releases/download/current/koggles-current.apk) ·
[**Watch the demo**](<!-- TODO(carter): URL of the 45–60 s demo video (GitHub release asset or
issue-attached .mp4) -->) ·
[Releases](https://github.com/carterchadams/koggles/releases)

## See it

<!-- TODO(carter): demo video, 45–60 s, real captured output (no illustrative animation), readable
without sound. Suggested shots in order: visuals reacting to music → double-tap opens the menu →
pick a preset or change the auto-advance interval → turn on goggles mode → the side-by-side view
in the mount. GitHub renders an .mp4 dropped into the README editor; otherwise link it and use
the GIF below as the inline preview. -->

![Demo: visuals, the menu, and goggles mode](images/demo.gif)
<!-- TODO(carter): images/demo.gif, ≤ 10 MB, same content as the video. -->

| | | |
|---|---|---|
| ![Full-screen visuals](images/screenshot-visualizer.png) | ![The menu](images/screenshot-menu.png) | ![Goggles mode, side by side](images/screenshot-goggles.png) |
| Full screen | The menu (double-tap) | Goggles mode |

<!-- TODO(carter): three phone screenshots, portrait for the first two and landscape for the
third: (1) a preset mid-song, (2) the menu open on the Display section, (3) goggles mode with
Wide + window on. Same preset in (1) and (3) if possible. -->

## What it does

- **Bundled presets:** the Koggles Starter Pack, a selection of 299 presets from projectM's
  [classic preset pack](https://github.com/projectM-visualizer/presets-projectm-classic), and the MilkDrop texture pack.
- **Your own packs:** choose a folder once (for example `Documents/Koggles/<pack>/…`) and its
  presets join the rotation.
- **Goggles mode:** side-by-side with Cardboard lens correction and viewer profiles (QR scan).
  **Wide view** opens the picture out to the edges of the screen instead of stopping at the
  viewer's nominal field of view; *Wide + window* also gives each eye its own side of the frame.
- **Lens spacing without taking the phone out:** each view keeps its own spacing, with defaults
  computed for your phone and viewer, and *Configure spacing with volume buttons* puts two
  bullseyes in the headset so you can tune it by eye; double-tap the screen, or press the mount's
  trigger twice, to finish.
- **Gestures:** tap for the next preset; double-tap for the menu (playback, packs and browser,
  display, library, audio).
- **Audio:** device audio only. *Standard* needs one ordinary permission. *High quality* (opt-in)
  captures playback directly; some apps block it and Android asks again after locking.
- **First launch:** the one screen before the audio permission carries a photosensitivity
  warning: the visualizations include intense moving and flashing lights.

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

**Compatibility:** Android 10 (API 29) or
newer, arm64. Goggles mode works with any Cardboard-compatible mount; scan the mount's QR code if
it has one, otherwise the Cardboard v1 profile is used. Developed and checked on a Samsung Galaxy
S25 FE.

## My role

I owned the product: what Koggles is for, how the experience is organised, which trade-offs to
take, and what was acceptable to ship. I wrote the spec and the acceptance criteria for each
piece of work, chose the architecture (below), and tested the result on a physical phone and in a
headset. Claude Code wrote the implementation from written plans, test-first; OpenAI Codex
reviewed every plan and every change adversarially before I looked at it. I challenged claims
that the evidence did not support, decided which reviewer findings stood, and made the
release calls.

<!-- TODO(carter): confirm this paragraph is accurate to the letter; it is the one the feedback
asked for. Cut anything you did not actually do. -->

Koggles started over from a prototype I had spent several weeks on, a direct extension of
projectM's legacy reference Android app. The prototype showed what mattered (lens spacing, a
usable menu, audio that works without ceremony) and what hurt (carrying local modifications to
projectM). Three decisions followed:

1. **Use projectM unmodified**, as a pinned shared library, instead of modifying a fork as the
   prototype did. Every projectM behaviour Koggles needs is reached through its public API and
   build options, so there is no fork to maintain. A modified projectM can still be swapped in
   (see "Replacing libprojectM").
2. **Use Google's Cardboard SDK** for lens distortion and viewer profiles rather than hand-tuned
   geometry. Scanning the mount's QR code is a known gesture to anyone who has used Cardboard, and
   the SDK's profiles carry the lens parameters I would otherwise have to guess.
3. **Offer two audio capture paths and let the user pick.** *Standard* asks for one permission,
   once. *High quality* needs a system consent dialog, some apps refuse to be captured, and on
   Android 15+ locking the phone ends the session, so it asks again (one tap) on return. The
   fidelity gain is real, so it is there, opt-in, with the costs stated on the switch itself.

### Designing for a phone you cannot touch

Once the phone is in the mount, the touchscreen is behind a lens and the picture only fuses when
the two eye views sit over your own eyes. Lens spacing is therefore the one setting you need to
change while wearing the headset, and the one you cannot reach.

The adjustment flow is built around that. *Configure spacing with volume buttons* replaces the
visuals with two bullseyes, one per eye; the volume keys, which stay reachable on the outside of
most mounts, move them apart or together in 0.5 mm steps; a double tap on the screen, or the
mount's trigger pressed twice, returns to the visuals with the new value, and Back also works. The defaults are solved from the viewer profile and the phone's screen, with the intent
that the control is rarely needed; whether that holds for other people's mounts is not something I
have measured. Each view (normal, Wide, Wide + window) keeps its own spacing because the wide views
move the picture relative to the lenses.

The alternatives were narrower than they look. A spacing slider in the menu exists, per view, and
is the right tool before the phone goes in; it is useless once it is mounted. Touch while mounted
is out: the screen is behind the lenses. Head gestures need sensors and a tutorial for a one-off
adjustment. The volume keys are the only input on the phone that most mounts leave exposed, so
the design question reduced to what the screen should show while they are pressed: two targets,
one per eye, that fuse into one ring when the spacing is right. Ending the calibration had its
own constraint: a single tap was ruled out because the screen is easily touched while the phone
is being pushed into the mount, so it takes a double tap, which is also what a Cardboard trigger
pressed twice produces.
<!-- TODO(carter): confirm these were the alternatives you weighed, and add any I have left out
(the plan records your direction for the bullseyes, not the options you rejected). -->

<!-- TODO(carter): short clip or two-frame image of the calibration: images/calibration.gif
(phone screen while the bullseyes move with a volume press) or images/calibration-bullseyes.png.
Caption it with what the viewer should notice: the rings move, the menu does not appear. -->

![Calibration bullseyes in goggles mode](images/calibration-bullseyes.png)

What I observed testing it: the defaults fused on the first try in my mount with the Cardboard
v1 profile <!-- TODO(carter): confirm, and name the mount -->; the volume keys were usable
without taking the phone out <!-- TODO(carter): confirm -->. Wide view is newer and its spacing
behaves differently (in the wide views a step moves the rings the other way, because the field of
view clamp moves with the spacing); I have measured that on the phone but not yet done a hands-on
headset check of the wide views, so they are in the release as options, not the default.

## How it was built, and what the process caught

Every feature followed the same loop: a written plan, reviewed adversarially by Codex before any
code; then for each task a failing test, the implementation, and another Codex review of the
change, repeated until the review found nothing. Phases ended with device checks. Most checks
that needed a real phone were automated over USB (adb-driven installs, the instrumented suite,
log and pixel capture on the Galaxy S25 FE); I did the hands-on confirmation of real-world
behaviour: menu scrolling with a finger, goggles mode in a mount, audio from other apps.

The second reviewer is a mechanism, not the proof. The proof is the tests, the measurements and
the decisions recorded against them. Two examples, each with the risk, what caught it, and the
evidence that settled it:

**A "no fix needed" that the evidence did not support.** This one is laid out as the chain a
reader can inspect; what is quoted is the original record, the rest is my retrospective account.

1. *Acceptance criterion* (the preset-pack plan, written before the work): every bundled preset
   loads **with its own shaders** on the emulator and on the phone.
2. *Claimed evidence*: the agent's load sweep reported 299 of 300 candidate presets loaded, from
   projectM's load callback, and proposed that as proof the criterion was met.
3. *Reviewer finding* (Codex, 12 October 2026, quoted from the review record):
   > Loaded callbacks do not prove the preset shaders compiled. The evidence claim is contradicted
   > by projectM 4.1.7. `PerPixelMesh::CompileWarpShader` catches `ShaderException` and resets the
   > warp shader; `FinalComposite::CompileCompositeShader` catches it and compiles a default
   > composite shader. Neither original failure necessarily reaches ProjectM's failed-switch
   > callback. […] Add separate mutations that trigger swallowed warp and composite failures.
4. *Change*: a diagnostics build flag that turns on projectM's own preset-debug output and
   captures it, plus two deliberately broken presets (bad warp, bad composite) that the old check
   classified as "compiled" and the new one classifies as fallbacks
   ([`5be5573`](https://github.com/carterchadams/koggles/commit/5be55738c2a0dc44f61c53ad530b5d28b46b5a9a),
   `ShaderDiagnosticsTest`, `tools/ci/preset-sweep.sh --shaders`).
5. *Observed result*: the sweep re-run on the diagnostics build, on the emulator and on the phone:
   299 of 299 bundled presets compile their own shaders, 0 fallbacks. The claim held, but only
   now was it measured. The plan's rule that a preset which falls back is excluded rather than
   patched stayed in force.

**A "flaky" test that was a real bug.** The auto-advance timing test
([`a7cc94d`](https://github.com/carterchadams/koggles/commit/a7cc94d8bc3155b3b5554842c3ecbfd754f3a552))
failed about one run in three. Rather than widen its tolerance, I asked what user-visible problem
a 10 % timing miss could mean. projectM randomises each preset's duration around the configured
value by default, so the "30 s" setting was really 28–32 s. One API call turned that off
([`94b72c8`](https://github.com/carterchadams/koggles/commit/94b72c892dd9fbf32beb74d476569c79f7c6a588)),
and the test has been stable since.

<!-- TODO(carter): optional: link a longer engineering case study (a docs page or gist) with the
other incidents that used to be listed here: the uncommitted plan revision, the gate marked PASS
on emulator-only evidence, the phantom second scrolling cause, and the staging-rule violation the
agent reported on itself. They are true and worth keeping somewhere; they are not front-page. -->

The plans, review transcripts and verification ledgers are kept outside the repository; the
example above quotes the one finding and links the commits and tests that any reader can inspect.
<!-- TODO(carter): optionally publish the full preset-pack review record (the Codex review and
your response) as a gist or docs page and link it from step 3, so the quote has a source. -->

<!-- TODO(carter): follow-ups the feedback asks for that live outside this file:
  1. Repository About: set the description ("Immersive music visuals for Android, on your screen
     or in a phone headset"), the website (the demo or releases link) and topics (android,
     music-visualizer, projectm, milkdrop, cardboard, vr, kotlin, compose, opengl-es).
  2. Release notes: open each release with what changed for the user, who should update, and any
     limitation, before the checksum block. The tag message becomes the top of the notes
     (RELEASING.md), so write it that way from the next tag on; edit the v0.4.0 notes by hand.
  3. Pull requests: title the dev→main PR after the release, and describe the problem, the change
     and the validation performed (the snapshot below is a template).
  4. tools/ci/test-readme-images.sh fails while the image placeholders below point at files that
     do not exist; add the images before merging this README. -->

## Validation snapshot: v0.4.0

| | |
|---|---|
| Version | v0.4.0 (pre-release, as are all 0.x) |
| Phone | Samsung Galaxy S25 FE (SM-S731U1), Android 16 <!-- TODO(carter): confirm Android version --> |
| Emulator | `Medium_Phone_API_37.0`, SwiftShader (software GPU) |
| Automated, in CI | unit suite, native host tests (with TSAN), release-tooling tests, release-build static checks and emulator smoke test (`.github/workflows`) |
| Automated, local gate | the instrumented suite, run before the tag. v0.4.0 is a merge commit; the last code change in it is `8e1e0c5`. Emulator, at `8e1e0c5`: 93 tests, 6 skipped, 0 failed. Phone, at the commit before (`18996bf`): 92 tests, 6 skipped, 0 failed; then the 3 tests that `8e1e0c5` touches (`CalibrationKeysTest`), 3 of 3 on the phone. The phone did not get a full re-run of the final commit. |
| Hands-on, phone | a scripted drive of the new feature on the phone (enter calibration, volume up twice, Back) with its screenshots looked at; headset fit with the default spacing (normal view) and Bluetooth audio driving the visuals, both from earlier releases <!-- TODO(carter): say which release; and add anything you did by hand for 0.4.0 specifically --> |
| Not done | hands-on headset check of Wide and Wide + window; frame timing in goggles mode on the phone |

**On the release gate.** [RELEASING.md](RELEASING.md) requires "rendering performance on the
phone" before a tag. For 0.x that has meant a qualitative check, that the visuals run smoothly
to the eye in normal and goggles mode on the S25 FE <!-- TODO(carter): confirm you did this for
0.4.0; if not, say "0.3.0" or whichever release it was -->, and not a frame-timing measurement
against the 20 ms p95 target the plan sets. The emulator cannot measure that (software GPU), and
the phone measurement is still pending. I decided that was acceptable for a pre-release and that
the gap should stay listed under Known limits rather than be dropped.

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
| Bundled-preset compile sweep (at will, not part of the instrumented suite) | `tools/ci/preset-sweep.sh` loads every bundled preset; `tools/ci/preset-sweep.sh --shaders` adds the shader gate (`-PkogglesShaderDiagnostics`) |
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
  repository states no license. They are distributed under the projectM team's statement that
  MilkDrop presets are safe to treat as public domain, and that it removes presets on request
  (`LICENSE.md` in its `presets-cream-of-the-crop` repository). The app quotes it verbatim in
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
- **In the headset:** lens spacing is per view: Off, Wide and Wide + window each keep their own,
  because the wide views move the picture relative to the lenses. Frame timing in goggles mode on
  a physical phone has not been measured (see the validation snapshot above).
