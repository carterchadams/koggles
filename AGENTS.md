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
| `docs/verification/` | Gate and manual-test records, created while executing the blueprint. Also the execution ledgers (`*-ledger.md`, verbatim copies) and the RED/GREEN log excerpts (`*-tdd-evidence.md`) |
| `docs/diagnosis/` | Field-report diagnosis plans and diagnoses, with their probe sources, allowlisted device-log excerpts and screenshots |
| `docs/process/` | How the agentic workflow ran on this project, with links to the records |

Older records cite local scratch paths under `.superpowers/`, which is git-ignored.
The published copies:

| Cited path | Published copy |
|---|---|
| `.superpowers/sdd/BLUEPRINT/progress.md` | `docs/verification/BLUEPRINT-ledger.md` (same line numbers) |
| `.superpowers/sdd/FIELD-REPORT-1-FIXES/progress.md` | `docs/verification/FIELD-REPORT-1-FIXES-ledger.md` (same line numbers) |
| `.superpowers/sdd/BLUEPRINT/codex-task*.md`, `t19-codex*.md`, `t20-codex*.md` | `docs/codex-review/2026-10-11-koggles-blueprint-task-gates.md` |
| `.superpowers/sdd/BLUEPRINT/codex-final-*.md` | `docs/codex-review/2026-10-11-koggles-phase1-merge-reviews.md` |
| `.superpowers/diagnosis/probes/` | `docs/diagnosis/2026-10-11-field-report-1-probes/` |
| `.superpowers/diagnosis/` (logs, screenshots) | `docs/diagnosis/2026-10-11-field-report-1-evidence.md` (excerpts only; raw captures stay local) |

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
