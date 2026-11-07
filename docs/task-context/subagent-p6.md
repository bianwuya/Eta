# P6: critical subagent decisions

## Goal and execution sequence

Complete the previously approved collaboration upgrade without repeating P1–P5. P6 adds critical child-to-parent decision requests; P7 separately tests compaction/resumption and fixes only demonstrated defects. P8 real-device and real-provider acceptance follows manual installation by the user; it is not implied by CI.

Baseline: protected `local` f7511d0639d45ca76d618740e61ed13df300f84e. Prerequisites: [P1 #18](https://github.com/bianwuya/Eta/pull/18), [P2 #19](https://github.com/bianwuya/Eta/pull/19), [cancellation #20](https://github.com/bianwuya/Eta/pull/20), [P3 #21](https://github.com/bianwuya/Eta/pull/21), [P4 #22](https://github.com/bianwuya/Eta/pull/22), [P5 #23](https://github.com/bianwuya/Eta/pull/23). P5 feature 4db22913a46e913896d7ead07df7637c08b78a92 passed full check 42278162621 and merged as the baseline.

## P6 contract

- Requests only for a genuine critical blocker requiring the parent decision, not normal progress. Persist a unique request ID, runtime-owned child invocation and original tool-call ID plus bounded context references before waiting.
- A working parent receives batched requests only at a complete safe boundary; a waiting parent can wake past a sealed group barrier. No model polling, INFO-triggered calls, generic mutual wait, DAG or extra summary model.
- Parent explicitly answers, refuses, revises or cancels. The child receives a real result paired to its original tool call; decision data is never forged as a user/system instruction. Duplicate and foreign/late responses have explicit outcomes.
- Ended/unavailable parent, cancel, timeout, revision, process interruption and storage failure must end boundedly and honestly, without re-executing completed or unknown side effects.

## Preserved and excluded

Preserve handoff/prompt effectiveness, independent task result semantics, parent control, group sealing/report deduplication, passive same-group INFO mailbox, real receiver reads, original plan-mode read-only restriction, provider signatures, session/account identity and stable request prefixes. No new UI/settings/permissions, workflow/branch-protection changes, upstream merge, automatic installation, official release or P7 implementation in this PR. GUI-P0 #24 and QA infrastructure draft #2 are independent, not included or assumed deployed.

## Verification and package provenance

Use the existing exact-head full PR Lint/Test/Debug check, then normal protected local integration and its original-signature standard Debug/Release build. P7 has a separate PR; the final P8 package will explicitly identify its actual merged source, prerequisite/feature heads, check/build runs, both APK hashes and signing certificate. No phone Gradle or fabricated execution claims. CI/fake-provider proof does not measure actual device usability, cache hit rate, quality, latency or cost.
