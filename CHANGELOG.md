# Changelog

Every release is a signed APK on the [Releases](https://github.com/carterchadams/koggles/releases) page; the notes there carry the same text plus each file's SHA-256. All 0.x releases are pre-releases and install over the previous one.

## Unreleased: anonymous opt-in usage statistics

**Privacy**
- A new switch, "Share anonymous usage statistics", on the first-launch screen and in the menu's new Privacy section. Off on every install, including existing ones. With it on, Koggles sends six kinds of event count to Aptabase with the phone model, Android and app versions, language and a per-session random ID; the README's Privacy section lists every field. The app gains the INTERNET permission for this and nothing else.
- Builds without an Aptabase key (plain CI, forks, local builds) hide the switch and never send anything.

## v0.5.0: calibration double tap, photosensitivity warning, documentation

Released 2026-10-14. Installs over 0.4.0. No settings change meaning.

**In the headset**
- A double tap ends the spacing calibration from inside the mount (a Cardboard trigger is a tap). While the bullseyes are up, a single tap does nothing, because the screen is easily touched while the phone is pushed into the mount. Back and switching goggles off still end it.

**First launch**
- A photosensitivity warning about intense moving and flashing lights now opens the first-launch explainer, above the audio-permission text. The screen scrolls when the text does not fit, so Continue is always reachable in landscape or with large fonts.

**Documentation**
- README rewritten around install, use, goggles and lens spacing, audio and permissions, building and tests, with screenshots. Its testing status records the hands-on headset check of Wide, Wide + window and the calibration, and the frame rate measured on a Galaxy S25 FE (Android 16): 120 fps in normal and goggles mode, no dropped frames, present-to-present p95 8.69 ms.
- CASE_STUDY.md on the design and the AI-assisted development process, and this CHANGELOG with the history of every release.
- AGENTS.md is the one instructions file for coding agents.

**Tooling**
- The README image check matches the banner by its path and download link, not its alt text, so the alt text can change.

## v0.4.0: wide view and eye spacing calibration

Released 2026-10-13. Installs over 0.3.0. Existing installs keep the normal view until they choose a wide one; new installs start in Wide + window.

**Wide view**
- Under Goggles mode, three views: Off (the normal view), Wide, and Wide + window. Wide extends the picture toward the screen edges instead of stopping at the viewer's nominal field of view, with the same distortion polynomial. Wide + window also gives each eye its own 85 % share of the frame.

**Lens spacing per view**
- Each view keeps its own lens spacing, and the slider shows the current view's. The range is now -10 to +40 mm, because wide view on a wide phone needs about +28.
- The wide views get computed defaults, solved from the Cardboard SDK's own lens model so each view fuses where the normal view does. They are recomputed when the display, the normal spacing or the viewer profile changes, and a spacing you set by hand is never overwritten.

**Spacing calibration in the headset**
- "Configure spacing with volume buttons" (under Goggles mode) closes the menu and shows one bullseye per eye. Volume up and down move them apart or together in 0.5 mm steps without changing the volume. Back ends it, including Android 16's predictive back; so does a double tap or switching goggles off.

**Known at release**
- The wide views were measured on the emulator but had not yet had a hands-on headset check, so they shipped as options, not the default for existing installs.
- Frame timing in goggles mode on a physical phone was not measured. Measured after release (2026-10-14, Galaxy S25 FE, Android 16): 120 fps in both normal and goggles mode with no dropped frames, present-to-present p95 8.69 ms. The author has since checked the wide views and the calibration in the headset.

## v0.3.0: menu revisions, portrait menu, transition interstitials removed

Released 2026-10-13. Installs over 0.2.0. The two interstitial settings from earlier installs are ignored.

**Menu**
- Goggles mode is the first item; About & licenses is its own last item below Audio instead of a button inside Library.
- The settings menu is always portrait, in both modes. Goggles mode stays landscape once the menu closes, and returning from the QR scanner cannot rotate an open menu.

**Playback**
- Transition interstitials are gone: the four-second "transition to black" presets shown between presets, with their switch and slider. The author saw seconds of black between presets and asked for the feature to go. Every advance now goes straight to the next preset; transition-named presets stay out of the rotation.
- The four Cream of the Crop transition presets are dropped from the bundle. The Koggles Starter Pack is 299 presets in one flat folder, and the release verifier rejects any nested file.

**Downloads and tooling**
- The README banner's Download button links to koggles-current.apk.
- The bundled-preset compile sweep (every preset compiled with its own shaders) runs at will through tools/ci/preset-sweep.sh instead of inside the instrumented suite.

## v0.2.0: branding and the rolling download link

Released 2026-10-13. Installs over 0.1.0. No settings change meaning.

**Look**
- Neon-on-black theme for the settings panel and every screen: black surfaces with the Koggles swirl's cyan, magenta and gold, and a cyan outline on the settings panel.
- Adaptive launcher icon drawn from the Koggles swirl, on a black background.
- The Koggles text logo fades in and out over black at launch (about 1.7 s, once per launch). It never consumes touches and does not replay when the activity is recreated.
- The README carries the brand banner.

**Downloads**
- A rolling "current" release holds a fixed-name koggles-current.apk, a copy of the newest release, so the README can link one URL. The versioned release keeps the four versioned files and the notes.

**Build and test tooling**
- The release verifier requires the launcher icon in the APK, and CI checks the icon resources and every README image.
- tools/ci/instrumented.sh runs the device suite on one named serial, and installs under a suffixed application id on a phone so it never replaces the real app.

## v0.1.0: first public release

Released 2026-10-12. The whole app as it first shipped. Everything below is new.

**Visuals**
- A music visualizer built on an unmodified libprojectM 4, driven by whatever the phone is playing. Full-screen mode, and goggles mode: side-by-side with lens distortion correction from Google's Cardboard SDK, for phone VR mounts.
- Goggles settings: scan a viewer's QR code to load its profile (the Cardboard v1 profile without one), a lens spacing slider, and a distortion correction switch.
- Bundled content, the Koggles Starter Pack: 299 presets selected from projectM's classic preset pack, four transition presets from Cream of the Crop, and the MilkDrop texture pack. Every file is pinned by hash and checked at build and release time.

**Playback and library**
- Tap for the next preset, double-tap for the menu.
- Playback settings: auto-advance (Off / 15 / 30 / 60 / 120 s), Soft blend with a blend time or Hard cut, hard cuts on beat, transition interstitials with their own duration, and a lock to stay on the current preset.
- Your own packs: choose a folder once (for example Documents/Koggles/<pack>) and its presets join the rotation. A library browser with packs and folders, favorites, skip, and a play-from scope (all enabled, this pack, this folder, favorites).

**Audio**
- Device audio only, never the microphone. Standard uses Android's Visualizer and needs one ordinary permission. High quality (opt-in) uses playback capture: higher fidelity, but some apps block it and Android 15+ asks again after the phone is locked.
- A first-launch explainer before the permission request, and status text in the menu (listening, waiting for permission or consent, no audio detected, another app holds the Visualizer).

**Release pipeline**
- Signed APKs built by GitHub Actions on every version tag, with a static verifier (signature, version, native libraries, bundled presets by hash, licences, R8 keeps, size), an emulator smoke test, the R8 mapping and a complete corresponding source archive including projectM.

**Known limits at release**
- No in-headset menu: changing settings or presets meant taking the phone out of the mount.
- Frame timing in goggles mode on a physical phone was not measured.
