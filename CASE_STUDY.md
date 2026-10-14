# Koggles: From a quick prototype to a usable app

Early in my ketamine treatment, I began using projectM for visual stimulation during sessions. I enjoyed the experience and wondered what it would be like inside an inexpensive phone-mount headset. Using projectM's source code and its legacy reference Android app, I built a prototype in a few hours.

The requirements took longer. Over several weeks of use, in and out of treatment, I found that getting the visuals onto the screen was only the beginning. The image needed lens correction. Eye spacing needed an adjustment I could make while wearing the headset. Permission prompts interrupted the experience, and preset collections needed a simpler way to manage them.

Those observations shaped a weekend rebuild into version 0.1 and the refinements that followed. I owned the product direction and architectural decisions, using coding agents for implementation and adversarial review. The goal was an app I could return to without repeatedly working around its limitations.

## Designing for the headset

Plain rendering of the visualizers was disorienting in my headset. Google's Cardboard SDK supplied the lens correction that addressed the distortion I experienced, but its field-of-view limits made the image narrower than I wanted.

Widening the view was not an isolated change. It altered the relationship between the image and the lenses, so I also had to recalculate the spacing between the eye views. Lens correction, field of view, and calibration had to work together.

Calibration posed an interaction problem as well. I needed to judge the spacing through the lenses, with the phone already mounted. I added an interpupillary-distance (IPD) adjustment mode that uses the phone's volume buttons to move the eye views apart or together in goggles mode. The controls and the visual feedback now belong to the same interaction, rather than requiring an adjustment in the menu followed by another look through the headset.

The wider viewing modes had their spacing behavior measured on the phone and then, with the calibration, a hands-on check in the headset. Correcting the original distortion does not establish that every subsequent rendering option works well through the lenses.

## Making repeat use easier

Permission prompts were one of the prototype's recurring annoyances. During development, an alternative audio path emerged that required less frequent consent. Koggles offers it as Standard mode, with direct playback capture available through an opt-in High quality mode. The latter carries additional permission requirements and can be blocked by the app playing the audio. Users can choose between the two rather than encounter the more demanding flow by default.

For presets, I chose a structure that could be managed entirely through the filesystem. Each subfolder of a primary folder becomes a package. Users can select packages and mark individual presets as favorites. Collections remain ordinary folders, so organizing the visuals does not require a separate packaging tool.

Getting new versions onto the phone also needed to be straightforward. Before an app-store release, that meant making sideloading practical. The release workflow publishes signed APKs, and a consistent signing key lets each release update an existing installation.

## Keeping the renderer unmodified

In the prototype, I had added features by modifying projectM directly. That became cumbersome. For the rebuild, I wanted application-specific behavior to stay outside the renderer.

Both projectM and Cardboard could be integrated as unmodified dependencies. projectM's public API and build options supplied what the application needed; Cardboard supplied lens correction and viewer profiles. That left me responsible for how those capabilities fit together, without a renderer fork to maintain alongside the app.

I also chose Android 10 as the minimum version with certain older, high-pixel-density phones in mind. That keeps those devices within the intended compatibility range; testing on them is still outstanding.

## Using agents—and checking their evidence

I built version 0.1 using my usual agentic workflow: iterative planning, red–green test-driven development, and adversarial review. Claude Code handled implementation; Codex reviewed plans and changes. I supplied the product requirements and architectural constraints and directed the work.

One verification problem illustrates why a second agent's review was useful, and why review alone was not enough.

The bundled-preset acceptance criterion required each preset to compile its own shaders on both the emulator and the test phone. The initial check relied on projectM reporting that a preset had loaded. Codex identified a gap: a preset could load after a shader failure because the renderer silently substituted a default shader. A successful load did not establish that the intended visual effect was being rendered.

The revised check captured projectM's shader diagnostics. Two deliberately broken presets tested the check itself: the old version treated them as successful; the new one identified the fallbacks. A subsequent sweep found that all 299 bundled presets compiled their own shaders on both test platforms, with no fallbacks.

The conclusion held, but the original evidence had not justified it. Adversarial review exposed the gap; a test that could detect the failure resolved it.

[Shader diagnostics implementation and tests](https://github.com/carterchadams/koggles/commit/5be55738c2a0dc44f61c53ad530b5d28b46b5a9a)

## What the project has established

I enjoy using Koggles both in and out of treatment, and limited feedback from friends has been positive. That is enough reason to keep developing it. It is not yet broad validation across users, phones, or headsets. The treatment context explains the project's origin, not a claim of therapeutic benefit.

I plan to release the app through an app store after more robust testing and the remaining distribution work. The older phones that influenced the compatibility decision and the wider headset modes are among the outstanding checks.

A few hours were enough to make the idea testable. Weeks of use showed me what needed to change. The next stage is to establish how well those changes work beyond my own setup.
