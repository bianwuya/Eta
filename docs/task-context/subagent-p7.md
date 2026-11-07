# P7: effective context and honest recovery

## Goal / prerequisites

The user requested completing collaboration P6 and P7, then personally installing the final package for P8. P6 [PR #25](https://github.com/bianwuya/Eta/pull/25), feature dd40ba7040e6c78352eccaa2daccda3b963dd43a, passed the exact full PR check [42331997532](https://github.com/bianwuya/Eta/actions/runs/42331997532) (Lint, full unit tests, standard Debug+Release) and normally merged to protected local as502619d359f08989f8329ad0c0a9cc56e654f89c, this P7 baseline. P6 final19 added test methods passed as part of that full check; its prior failed candidate is not reused as proof. The automatic same-merge signed stable build remains separately verified before further integration.

P1–P5 [#18](https://github.com/bianwuya/Eta/pull/18), [#19](https://github.com/bianwuya/Eta/pull/19), cancellation [#20](https://github.com/bianwuya/Eta/pull/20), [#21](https://github.com/bianwuya/Eta/pull/21), [#22](https://github.com/bianwuya/Eta/pull/22), [#23](https://github.com/bianwuya/Eta/pull/23) are preserved completed prerequisites, not reimplemented here. QA infrastructure #2 independently entered stable local before P6; P7 does not modify/deploy/revert its workflows. GUI-P0 #24 is excluded.

## P7 tests first

Verify the actual child compactor, handoff/effective goal and constraints, latest parent revision and resume together, including failed compaction. Compare only identity and prefix portions the protocol actually retains; changed compacted history is expected. A deterministic fake summarizer establishes structure/relay, not real-model understanding or cache hits.

Verify unread INFO across same-invocation compaction plus explicit read and fresh resume: real call/result pairing, no INFO-driven wake/injection, no old inbox authority inherited by a new task. Verify pending critical requests across checkpoint preview/reopen/finalization: genuinely live same-owner state or explicit interruption, no response transferred to a new invocation or infinite wait. Completed/unknown side effects must never auto-replay, repeated recovery reports deduplicate by stable ID, absent process must not be falsely claimed to resume in place.

Only a demonstrated regression justifies a minimal production repair. Reuse the existing domain/checkpoint/Executor/provider tests; no generic recovery framework, new UI/settings/permissions, extra summary model, polling monitor, version/signing changes or skipped tests.

## Verification / final package

Independent feature PR, exact-head full cloud Lint/unit/standard Debug+Release, normal protected local merge. No phone Gradle or automatic installation. Requests, provider roles/signatures, cancellation arbitration, session/account identity and parent control remain intact. Test callback failures must reach the JUnit thread, not disappear inside runtime error handling.

This P7 PR is not the final P8 package. The user explicitly chose retaining installed71a09c7 HTML external browser, child history/statistics and TLS-disconnect repairs; after P7 they will be integrated in a separate exact-source PR/full check. Final package proof binds feature/base/actual merge source/check/build, both original-signature standard variants and their SHA256. No new GUI-P0 or unrelated feature is inferred from separate PRs. Actual Android lifecycle, provider cost/latency/cache and usability remain user P8, not inferred from JVM/Room reopen/cloud builds.
