# Start-only model failover

| Trigger | Action | Evidence |
|---|---|---|
| User changes ordered model IDs | Same-provider/account/protocol enabled tool models only; incompatible or too-short contexts filtered | SubagentModelPolicyTest.filtersDisabledIncompatibleShortContextAndOtherAccountWhilePreservingOrder |
| Explicit 401/403/404 or permanent quota429 before any output/tool | Try next eligible model once; retain child session/history and publish actual model/cold-cache reason | explicitStartRejectionFailsOverOnceAndSubsequentTurnsStayOnActualModel |
| Temporary429 / 502 / 503 | Same model via AgentModelRetry, maximum two retries; no model failover | transientFailureHostedToolUnknownTransportAndEarlierSuccessNeverFailOver; existing retry/provider tests |
| Hosted tool or streamed output already observed; unknown transport outcome | Propagate failure. No cross-model replay | Same policy safety test |
| Existing interrupted child resume | Bound model/account fingerprint must match; repair missing tool results as unknown, no automatic new task | OfficialSubagentRecoveryTest; AgentToolBatchRecovery |
| Completed child receives explicit new prompt | Reuse child identity and history; selected model change is an intentional cold-cache boundary and actual model is reported | SubagentActivityStatsTest.realExecutorResumeCarriesHistoricalReportUntilNewUsageArrives |
| Different completion times | Official awaitAll joins all calls once; completed results checkpoint immediately, canonical order before next model turn | SubagentTest.agentLoopExecutesMultipleSubagentCallsInParallelAndPreservesOrder |
| Missing usage fields | Null remains unknown; original statistics store/projection shared by instance and settings page | Existing local/subagentstats tests retained |

No account rotation, quota polling, synthetic budget, new scheduling framework or production UI added. Profile/model ordered settings are additive and default empty; old settings retain original single-model behavior.
