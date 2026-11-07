# Local Development Checks

Local feedback uses the existing phone Linux toolchain. Stable `local` and upstream
`main` are not modified by validation. Do not create a fresh worktree per correction
or erase build output to force a clean-looking result.

The reusable entry belongs to the separate maintenance-assets repository:
`scripts/eta-local-dev.py`. The implementation branch is `feat/local-fast-development`.
Locate that repository explicitly; a source worktree's relative `../eta-local` is not
reliable. No product code, version policy, signing identity or test mode changes are
part of this workflow change.

## Feedback And Delivery

- L0: documentation/format/repository guard, no APK build.
- L1: local Kotlin logic, compilation and explicit affected unit tests.
- L2: UI/Compose, compilation, affected tests and a separate visual acceptance flag.
- L3: shared/core/storage/build/unknown changes, compilation and full unit regression.
- L4: delivery or integration, complete Lint, all unit tests, standard Debug/Release,
  original certificate, exact source/version/variant/metadata and SHA-256 audit.

Unknown scope promotes rather than skips. A manually requested level can only
increase the level. Delivery cannot consume L0-L3 evidence. Targeted feedback is not
a substitute for the final L4 or the user's physical/OEM acceptance.

## Reuse And Recovery

Keep one owned detached validation tree per task. The verifier serializes access,
checks immutable HEAD and source hygiene before/after a run, keeps the existing
Gradle user home/JDK/SDK, enables the build cache explicitly and does not `clean`.
Configuration caching is a strict opt-in compatibility probe, not a warning-only
fallback. Cache directories are not evidence of successful checks.

Every check stores actual commands, timings, task outcomes and log/report/input
digests. Delivery rechecks those digests, source HEAD, original signatures and APK
metadata without recompiling. This permits retrying a failed copy/audit without
repeating passed compilation. Changed source or evidence invalidates reuse.

Tracked changes and unknown untracked files are source changes. Only exact generated
`__pycache__/*.pyc` files are tolerated; new source inside that directory still fails.
Cleanup is explicit, limited to a recorded owned tree, uses `git worktree remove`
without force, and refuses every untracked/unsaved file. Existing unrelated trees
and their caches are never deleted automatically.

Independent agent handoffs declare goals, file ownership, entry points, acceptance
and forbidden actions. Overlap requires serial integration/reassignment, not last
writer wins. Reports include changed files, key diff, real check commands/results,
risks and next step. Environment/service retries are bounded to two recoveries;
code/signing/permission failures require evidence-driven repair, not blind retries.
