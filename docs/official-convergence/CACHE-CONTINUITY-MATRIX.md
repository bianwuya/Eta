# Cache continuity

| Boundary | Evidence | Result |
|---|---|---|
| Five unchanged logical-chat runs, appended history | OfficialContinuityTest.fiveIndependentRunsPreserveRequestPrefixToolsAndCacheIdentity | Exact original message prefix, one tools hash, one cache key |
| Model / account / endpoint intentionally changed | OfficialContinuityTest.intentionalModelAccountAndEndpointChangesIsolateCacheRouting | Distinct cache key; old implementation red |
| Different provider/session/purpose, explicit key, unrelated models/OAuth | OpenAiPromptCacheTest | Partitioned hints; explicit values retained |
| Image steering with complete tool batch | MultimodalSteeringCacheTest | No in-flight cancel; prefix/key/session unchanged; durable image privacy retained |
| New run after completed tool / handoff / continuation | AgentContinuationBuilderTest; PendingQueueRestartTest; VoiceSteeringRecoveryTest | Same effective logical session; ACK/query prevents duplicate input |
| Compaction / manual prompt changes | Existing compaction implementation | Intentional prefix change, not a promised cache hit; G3 retains compaction settings |
| Two child instances, five turns and memory recreation | OfficialSubagentRecoveryTest.twoChildrenKeepDistinctCacheIdentityAcrossFiveTurnsAndMemoryRecreation | Distinct keys across children, stable per-child prefix/key across turns |
| Interrupted child after account change | OfficialSubagentRecoveryTest.interruptedInstanceCannotResumeWithChangedAccountOrReplayItsTool | Rejected before another provider/tool call; original history retained |
| Chat failover versus independent compaction | SubagentModelPolicyTest; durable compaction recovery fixture | Account binding pinned for chat; compaction model/config retained; committed summary restored |

Fake providers establish client request stability only. No production request, paid token or server cache percentage was measured.
