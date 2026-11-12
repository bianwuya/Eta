# G2 Runtime and plan retirement

Official Runtime/Loop/Provider/Wire/checkpoint remain the execution spine. Removed PlanRunGate, PlanModeStore, panel, slash IME interception, plan prompt/tools and runtime callbacks. Kept independent durable question cards and full-turn question boundary. Old plan flags in IPC, queued input and continuation read as normal; stored history/plan files are not deleted. The historical plan_propose sensitive-tool entry remains exclusively to redact old transcript data.

Confirmed cache defect: the old route key did not partition a changed model, endpoint or API-key identity. OfficialContinuityTest.intentionalModelAccountAndEndpointChangesIsolateCacheRouting failed before the change and passed after adding those boundaries. A one-way credential digest only participates in the hashed key, never in output. Five independent unchanged runs preserve system/history prefix, tools and cache route. Explicit user cache keys remain authoritative. No server hit-rate guarantee is claimed.

AgentModelRetry now allows two retries; same body/account/session are held, callback persistence failure, hosted-tool start and permanent auth/quota/certificate errors do not replay. Official provider terminal logic and narrow relay EOF/HTTP2/TLS compatibility remain. No independent recovery loop added.

Validation is tied to fresh archive g2-pass (tree 389b6f1e3bfec2994cc613e75ec9658ebc5ad458), not the old 2934-test snapshot. See task runs/g2-pass/{summary.json,gradle.log,xml}; test filter includes provider/SSE/retry, five-turn continuity, multimodal steering, queue persistence/restart, voice steering recovery, question drafts, tool recovery and checkpoint tests. Earlier attempts preserve compiler/obsolete plan expectations and the cache red test. Updated assertions only where explicit feature removal or retry-cap contract changed; ordinary failure/cancel/storage assertions retained.

G3 will remove the redundant child bus. No new image/subagent visible UI was implemented. Checkpoint refs and commit IDs are in the task CHECKPOINTS.json. To inspect/recover any checkpoint create a separate worktree at its SHA, never reset original local/integration.
