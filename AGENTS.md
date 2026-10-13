# AGENTS.md

Koggles is an Android music visualizer built on libprojectM. It has a normal full-screen mode
and a lens-corrected side-by-side "goggles" mode for phone VR mounts, and it is driven by device
audio. Package `dev.koggles`. Kotlin/Compose app under `app/src/main/java`, C++ renderer under
`app/src/main/cpp` (the pure C++ in `core/` is host-tested from `native-tests/`), release tooling
under `tools/ci`.

## How work happens here

The author directs; agents execute. Planning documents, review transcripts and verification
records are kept outside the repo (locally, under the git-ignored `.tmp/`), so the repo holds
code, tests, and the rationale in code comments. README's "How this was built" is the public
summary.

For any change beyond a trivial fix:

1. **Plan first, in writing.** A short plan with the files to touch, the tests that will prove it,
   and measurable success criteria. The author approves the plan before code.
2. **Adversarial review of the plan**, by an independent reviewer (OpenAI Codex through its
   Claude Code plugin). Revise until it approves. Record each round's findings and your response
   in `.tmp/` so the author can read them.
3. **Tests first.** For each task, write the failing test, watch it fail for the expected reason,
   then make it pass. A test that passes before the change exists is not testing the change.
   For guard tests that already pass, prove they can fail by temporarily breaking what they guard.
4. **Measure on real targets.** Logic and timing on the emulator; anything touching the GPU
   driver, audio stack or touch input also on the physical phone. Device checks are automated
   over USB (`adb`, instrumented tests, logcat and pixel capture); install test builds beside the
   author's app with `-PkogglesAppIdSuffix`, never over it. The author does quick hands-on checks
   of real-world behaviour and reports them verbatim; record those words.
5. **Adversarial review of the finished branch**, revised until approved, then merge into `dev`.
   `main` moves only when the author says so. Never push, tag, or publish without the author.

Stop and ask the author for: a product or copy decision; anything needing their phone, headset,
accounts, keys or secrets; and a reviewer finding you have rebutted twice with evidence. Do not
stop to ask "should I continue?".

## Rules

- Stage explicit paths only. Never `git add -A`.
- Never commit `keystore.properties`, `*.jks`, `.tmp/` or `.superpowers/`.
- Every native renderer call happens on the GL thread that owns the renderer
  (`RenderCommands`); playback and audio state live on the main thread.
- User-visible copy is exact and needs the author's approval.
- Bundled content is pinned by commit and by per-file hash (`app/bundled-presets.txt`,
  `app/bundled-transitions.txt`); changing the bundle means changing those lists.
- Don't modify `third_party/projectm` or `third_party/cardboard`; use build options and the
  public API.
- When a test fails or flakes, first ask what real problem it could indicate. Several real bugs in
  this project surfaced as "flaky" tests.
- Report outcomes faithfully: say what ran, what passed, what is pending, and what you did not do.

## Reviewer pin

The adversarial reviewer is the OpenAI Codex companion, pinned per phase in its ledger. Model pin:

ttp-codex-model: gpt-6.1-sol
