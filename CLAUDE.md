# CLAUDE.md

Koggles: an Android music visualizer on libprojectM with a lens-corrected "goggles" mode for phone
VR mounts. Package `dev.koggles`. Kotlin/Compose under `app/src/main/java`, C++ renderer under
`app/src/main/cpp` (its pure `core/` is host-tested from `native-tests/`), unit tests under
`app/src/test`, instrumented tests under `app/src/androidTest` (run with `tools/ci/instrumented.sh`,
`ANDROID_SERIAL` set). Plans and records live outside the repo in the git-ignored `.tmp/records/`.

## The process

Every fix or feature follows this. If a step is skipped, shortened or reordered for any reason,
say so to the human at that moment and explain why; never deviate silently.

1. **Idea.** The human states the fix or feature.
2. **Plan.** `/superpowers:writing-plans` into `.tmp/records/plans/<NAME>.md`. A plan is a
   blueprint: the files to touch, the interfaces, the tests that will prove each task (unit or
   integration, plus instrumented), what will be measured on the emulator and on the physical
   phone, and the success criteria.
3. **Review the plan.** `/codex:adversarial-review` on the plan. Update the plan in response, and
   re-review until it is approved. Keep the review and the response in `.tmp/records/`.
4. **The loop**, one task at a time:
   - `/superpowers:test-driven-development` before any production code. Write the failing test,
     watch it fail for the expected reason, then make it pass. Tests are both automated (unit /
     integration) and instrumented on the emulator and on the physical device.
   - `/superpowers:executing-plans` to implement the task.
   - `/codex:adversarial-review` on the implementation. Fix findings test-first.
   - Repeat until the review finds no problem.
5. **Whole-work review.** `/codex:review` on the complete work. A holistic finding resumes the
   loop at step 4.
6. **README.** Update it if the user-facing behaviour or the feature list changed.

Then merge the branch into `dev`. `main` moves only when the human says so. Never push, tag or
publish without the human.

## Rules

- Branch off `dev` for each piece of work. Stage explicit paths; never `git add -A`. Never commit
  `keystore.properties`, `*.jks`, `.tmp/` or `.superpowers/`.
- Install test builds beside the human's app with `-PkogglesAppIdSuffix`, never over it.
- Every native renderer call runs on the GL thread that owns the renderer (`RenderCommands`);
  playback and audio state live on the main thread.
- Don't modify `third_party/projectm` or `third_party/cardboard`; use build options and the
  public API. Bundled content is pinned by commit and per-file hash in `app/bundled-*.txt`.
- User-visible copy is exact and needs the human's approval.
- A failing or flaky test is a real problem until shown otherwise; diagnose before changing it.
- Report faithfully: what ran, what passed, what is pending, what you did not do.

Stop and ask the human for a product or copy decision, for anything needing their phone, headset,
accounts, keys or secrets, and for a reviewer finding you have rebutted twice with evidence. Do not
stop to ask "should I continue?".

## Reviewer pin

Codex model: `gpt-6.1-sol`.
