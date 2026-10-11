# AGENTS.md

Koggles is an Android music visualizer built on libprojectM. It has a
normal full-screen mode and a lens-corrected side-by-side "goggles" mode for
phone VR mounts, and it is driven by device audio.

## The blueprint is the source of truth for building

**[`plans/BLUEPRINT.md`](plans/BLUEPRINT.md) is the actionable engineering
plan.** Build from it.

- **Work order:** execute its tasks in order (Phase 1 gates, then Phase 2
  features, then Phase 3 release). Tick each `- [ ]` checkbox as you
  complete it.
- **What each task fixes:** the task's **Files**, **Interfaces**, test
  names, commands, and commit message are decisions already made. Follow
  them exactly. If one is wrong or impossible, stop and raise it with the
  human. Do not improvise around it.
- **Global Constraints** at the top of the blueprint apply to every task:
  pinned versions, constants, exact UI copy, the GL-thread rule, and the
  commit rules.
- **🧑 HUMAN steps** need the developer's phone or headset. Stop at each
  one, ask the human, and record their answer verbatim in the named
  `docs/verification/` file.
- **Gates G1–G4:** if any gate fails, stop. Do not continue to Phase 2
  until the spec has been revised.

## Document hierarchy

| Path | Role |
|---|---|
| `plans/BLUEPRINT.md` | **How to build it.** The tasks, interfaces, and tests |
| `docs/superpowers/specs/2026-10-11-koggles-design.md` | **What to build and why.** Read it alongside the blueprint. If the two disagree, stop and ask; don't pick one |
| `docs/codex-review/` | Adversarial reviews by Codex. A historical record, kept verbatim |
| `docs/claude-response/` | Claude's dispositions and acceptance matrices for those reviews. A historical record |
| `docs/verification/` | Gate and manual-test records, created while executing the blueprint |

The review folders explain why the blueprint looks the way it does. They are
not instructions. Codex output goes in `docs/codex-review/` and Claude output
goes in `docs/claude-response/`; never mix them.

## Changing the blueprint

The blueprint is the product of several review rounds. Do not edit it while
implementing, apart from ticking checkboxes and recording gate results where
it says to. Changes go through the human. A proposed change and any review
response get written up in the review folders above, and the blueprint is
revised only after the human approves.

## Repo conventions

- Package `dev.koggles`. Native code lives in `app/src/main/cpp/`, and the
  pure C++ under `core/` is host-tested from `native-tests/`.
- Each commit stages only the files its task lists. Never use
  `git add -A`.
- Never commit `keystore.properties` or `*.jks`.
