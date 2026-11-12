# Automatic resume boundaries

- AgentModelRetryTest: bounded two retries, same request body/session/account, cancellation during wait, callback failure and hosted-tool start prevent replay; HTTP2 fallback changes only this request's transport.
- RelayStreamFailureTest: local fake HTTP SSE upstream failure, exactly bounded retries and unchanged request; no paid provider.
- ProviderSseReaderTest / OpenAiChatCompletionsProviderTest / OpenAiResponsesProviderTest: complete terminal, EOF, partial or malformed frames. Provider terminal contract remains authoritative.
- AgentToolBatchRecoveryTest: missing results become TOOL_INTERRUPTED with explicit unknown-execution warning. Known results retained, not executed again.
- PendingQueueRestartTest / VoiceSteeringRecoveryTest: actual Room/IPC fixture covers durable admission before ACK, missing ACK reconciliation, storage failure, main-chat recreation and input uniqueness.
- Retired plan flags no longer block steering or create a second execution state machine. Persistent user data is not removed.

Actual results: task runs/g2-pass. G3 child recovery is separately verified after removal of the custom delegation bus; no claim of entire-project completion at G2.
