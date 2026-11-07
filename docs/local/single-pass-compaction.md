# Single-pass context compaction

## User requirement (2026-11-07)
“分段合并这个去掉，我不需要这个” — remove segmented summarization and the second summary-merge pass.

## Behavior
- Serialize the complete eligible compaction history into one summarization request. Do not group messages into chunks, split oversized source data, or summarize summaries in a merge request.
- Keep the independently selected compaction model, reasoning setting and custom prompt; do not restore an output character limit.
- Retain existing source selection, complete tool-exchange boundaries, protected latest user/revisions, and atomic commit. Failed or invalid summaries do not replace the original context.
- If the existing conservative input budget cannot hold the complete request, report `COMPACTION_MODEL_CAPACITY` before sending anything. Do not truncate history, switch models, or silently fall back to chunks.
- Retain the existing one-time HTTP/2 transport recovery using the identical full request and account. This is transport recovery, not a second summarization/merge pass.

## Scope and review context
Base: `1b36b55f0b1f42c7af39a0d5cd692c82e8d0af10` on protected `local` (includes merged GUI-P0 PR #24 and the collaboration final integration PR #27; see commit ancestry for the preserved feature history).
This PR changes only the shared summarizer and corresponding regression tests plus this document. It does not change UI, automatic trigger thresholds, provider prompt/session identity, signing, CI execution, or install settings. Parallel GUI-P1–P3 PR work is not included by this baseline and is not modified.

## Verification and delivery
Regression coverage checks exact complete multi-message/Unicode/tool-exchange input in one request, capacity rejection without any provider request or context commit, transport failure with original history intact, long valid summaries, and identical-body/account transport fallback. Full Lint, unit tests and standard Debug/Release build run in the existing PR Check. QA signing reuses that same successful check's artifacts with the original certificate; the PR/feature head/base/actual QA source/check/sign/APK hashes are linked by the existing QA receipt. Test coverage and a signed package are not user functional acceptance. No automatic merge, install or formal release is authorized by this change.
