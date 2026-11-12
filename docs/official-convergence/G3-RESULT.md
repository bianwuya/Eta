# G3: official subagent execution with retained extensions

Restored official AgentLoop.executeToolBatch/executeParallelSubagents and upstream schema semantics. Removed YieldableSubagents, SubagentDecisionBroker, SubagentMailbox, SubagentTaskGroup, their injected reports/acceptance protocol and unused controller decision APIs. Retained the existing live child cancel/revise wrapper because it preserves explicit cancellation and permission boundaries without a second execution loop. Old runtime task chunks are read-only compatibility data: an unknown result is never represented as completion or replayed.

Retained original actual-model/context/history and nullable usage statistics/store/settings projection, independent compaction model/prompt, profile prompt and tool allowlist. Start-only SubagentModelPolicy filters manual candidate order to same account/protocol/capability/context; explicit rejection can fall through, streamed output/tool/unknown outcome cannot. Model change updates actual display and records a cold-cache reason. Profile order is stable and additive. No visible settings UI implemented before G5 approval.

Extended official SubagentSessionStore with atomic, sanitized durable history and a model/account identity digest (no raw credentials). Fresh process memory loads original child IDs/history; interrupted tool calls are completed with explicit unknown results. Explicitly cancelled instances cannot resume. The official sequential sub_N identity is kept and avoids on-disk collisions after memory recreation. Same child/cache identity persists for five rounds; two children stay separate.

Fresh validation: runs/g3-fresh-final in the task root, source tree b255c8cb7d35c1ad7e9099d28d71cd1a6481b86f. All actual statistics in summary.json/XML/gradle.log. Earlier 166-test targeted snapshot g3-confirm passed; it does not substitute for the final full run. Full test count reduction is attributable to removed plan/bus-only suites and replaced plan assertions. Ordinary cleanup, cancellation, profiles, history, statistics and compaction coverage remain; mixed SubagentStartupCleanupTest was retained with only its retired-owner scenario removed.

An earlier overlapping test launch corrupted Gradle result collection (EOF/missing in-progress binary). Those results are invalidated and retained as failed logs. A task-local flock now prevents overlapping exports/tests; final run started only after ps confirmed no remaining JVM. The old snapshots and original WIP were untouched. Original local/integration protection was rechecked with all fields true.

Rollback: create a new checkout at codex/eta-official-g2-checkpoint for pre-G3 behavior, or at codex/eta-official-g3-checkpoint for this stage. Never reset original local/integration. See [CHECKPOINTS.json](../../../CHECKPOINTS.json) for full SHAs. No APK, key changes, paid calls, push or stable-branch merge.

## G3 supplement

The ordered-chat provider previously replaced an independently selected compaction model with its chat candidate. It also treated an event callback throwing an auth-shaped persistence failure as a model rejection. Both were reproduced by `SubagentModelPolicyTest` in `runs/g3-extra-red` (2 failures) and fixed at the provider policy: non-chat requests retain their own config/provider; callback failures propagate without selecting another candidate.

OAuth child requests pin a `ProviderCredentialBinding` before running; persisted identity includes the credential slot, so changing the selected account cannot silently resume an interrupted task under another account. Regression fixtures cover a changed binding across failover/later turns, restart after a committed tool, independent compaction and restoration of the last committed summary. The actual provider/key stores were not initialized with real credentials.

The final full-run receipts and the separate test-fixture correction are documented in `FINAL-VALIDATION.md`. This supplement adds no production UI or new execution bus.
