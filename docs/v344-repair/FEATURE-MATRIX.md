# Incremental capability source and disposition

| Capability | Source | Disposition |
| --- | --- | --- |
| Official UI, new speech/title settings, compaction cache/archive/usage | v3.4.4 83a25e71 | Adopt official and reconcile extension seams |
| Account callbacks, reset operation, incoming queue, daemon/voice recovery | local WIP == T00 41b9bc58 after EOL normalization; G6 ancestor | Retain, do not reapply older files |
| Independent image connections/model catalog, child priority/failover | G6 78b258ed | Retain approved UI/runtime integration |
| HTTP400 history validation, screen fallback, compaction UI/readings | backup ce744f16 + ce0ce8ec | Apply reviewed individual hunks |
| Portable full/category backup and unavailable-model projection | backup 8 WIP files | Integrate; fix multi-profile/rollback compatibility |
| CPA reset credit read/consume protocol | management d0126752 | Separate optional GET credits, fixed account, persisted redemption ID |
| Custom automatic title generator | now superseded by official | Remove duplicate implementation/tests; retain manual action using official generator |
| Custom plan mode/delegation bus | already retired in G6 | Do not resurrect from backup branch conflicts |
| New reset account slot UI | shared existing quota/reset component | APPROVED v2 on 2026-11-14; wired to chat and fixed image account slots |

Preserved original checkout fingerprints: task protection-before.json and protection-check-integrated.json,
all HEAD/branch/status/index/staged/unstaged/refs/source hashes unchanged.

## Repair and verification seams

| User-visible contract | Authoritative implementation | Regression coverage |
| --- | --- | --- |
| Chat subscription authentication and selected account callbacks | ProviderConnectionFields / ModelProviderDetailScreen / ProviderLoginStore | ProviderComponentsTest / ProviderLoginStoreTest / subscription entry tests |
| Independent image profiles and fixed account | ImageGenSettingsScreen / ImageGenConfigStore / ImageGenClient | ImageGenProductionUiTest / ImageGenFakeServerTest / account entry tests |
| Account-specific usage and optional CPA credit details | CodexUsageRepository / ProviderConnectionProfile | CpaResetCreditsTest (red-to-green) / CodexUsageCacheIsolationTest |
| Confirmed reset, durable redemption UUID, fixed-account concurrency | CodexResetRepository / CodexUsagePanel | CodexResetRepositoryTest / CodexUsageAccountInteractionTest |
| Shared compaction model/effort/prompt, complete history in one request | SettingsDataStore / RuntimeConfigRepository / AgentContextSummarizer | IndependentCompactionTest / CompactionExcerptsTest / CompactionSettingsPersistenceTest |
| Start-only same-account model priority fallback | SubagentModelPolicy / SubagentExecutor | SubagentModelPolicyTest / routing regressions |
| Real child Token/cache/compaction stats and paged session totals | StepTracker / SessionStatsAccumulator / CacheUsageDiagnostics | SubagentActivityStatsTest / SessionStatsPagingTest / SessionStatsMergeTest |
| Portable six-category backup with rollback and local account preservation | EtaPortableBackup / EtaBackupRepository; UI goes through AgentAppState restore guard | EtaPortableBackupTest / LocalMergeRegressionTest |
| Completed child resume retains its original model/account after priority changes | SubagentExecutor / SubagentSessionStore | SubagentRoutingRegressionTest; binding missing fails before any request |
| Legacy Skills remain portable through the official installer | EtaPortableBackup portable copy metadata | EtaPortableBackupTest; original files, body, compatibility and model metadata preserved |
| Official title settings replace duplicate local switch | ConversationTitleGenerator / SettingsDataStore legacy migration | ConversationTitleMigrationTest / ManualConversationTitleTest |
| Tool history pairing, screen wake fallback, cached safe continuation | ChatToolCallHistory / AgentToolBatchRecovery / EtaAssistantScreenContext / existing runtime | ChatToolCallHistoryTest / AgentCancellationBoundaryTest / screen and runtime suites |

Final command outputs, source tree fingerprint, signed APK checksums and untested device
items are recorded in the task's DELIVERY.md and runs/, outside the source snapshot.
Native Robolectric UI captures are host verification; no device/OEM/IME/live redemption claim.

## Reviewed boundaries

- Settings/compaction persistence retains the existing keys and full JSON config. Corrupt JSON/IO is not silently rewritten to defaults; current valid-config restart coverage passes. A general corrupt-store recovery UX remains REVIEW, outside this symptom repair.
- Codex login switching follows official provider source identity: OpenAI or an existing Codex connection exposes subscription authentication. Custom relay connections retain API Key behavior; no new cross-source auth selector was introduced.
- Skill installer recovery journals and data commit callbacks remain authoritative. Partial rollback and post-commit cleanup errors are surfaced; retry does not invent a completed restore.
- UI shader support is a separate device check. Robolectric cannot compile COUI Mesh/child shader effects; subscription selection tests shadow only decorative CouiShadowRenderer construction/draw. Quota/reset screenshots use native API 36 without that shadow.
