# Power-task conversation entry and notification focus

## Approved Goal

Build on local GUI update `7f9a14e836274f49afe09daa1b1c21555ff90fad`. The user rejected the extra task-details and stop-confirmation dialog after considering it: tapping the native ongoing notification/pill should directly open the actual power-started conversation, including while the run is active. Use the existing chat controls; its bottom-right stop remains cancellation of the current run, not a newly invented resumable pause.

The most recently submitted or explicitly continued active power task is the notification's primary task, ahead of existing long-running work. Merely invoking the input window is not submission. Multiple power tasks use explicit interaction recency, not inferred task duration. Other work continues unchanged. Displayed title, phase, navigation and any action must all refer to the same primary run. Completion falls back to remaining work, but an already issued click for the finished task still opens that task's conversation rather than a newer target.

## Runtime And Conversation Invariants

- Keep the single Runtime registry, foreground service, notification channel, lease lifetime and exact run ownership. Display priority does not change execution priority or GUI ownership.
- Reuse existing stable source/conversation-key identity, real input and safe replay/live events. Opening a conversation attaches to the original run; it must not submit another START or model request.
- Live projection and final archive import update the same conversation without duplicate messages. Save durably before ACK, preserve fallback/outbox recovery, and handle preparing, rapid completion, reconnection and UI recreation.
- Remove the intermediate task/stop dialogs from notification and active-list entry. Failed navigation preserves the current screen with a retryable state. Passive status updates do not change the user's selected conversation.
- Scope the existing chat stop to that conversation's active run. Closing entry/UI does not cancel accepted work. Retain single-writer/queue, predecessor cleanup and model/account/session semantics.
- Expose only necessary same-UID presentation data, not model config, credentials, system prompts, full model history or opaque adapter payloads. Large display text uses existing FD transport and cleanup.
- Ordinary reasoning, browser, file and terminal work does not keep an extra neutral overlay capsule. Preserve reliable visible gating for actual GUI actions, ownership/connection-generation isolation, screenshot exclusion, clearance and useful virtual-screen preview/pause/takeover. Do not change auto/virtual/main routing or hide an active operation for visual convenience.

## Scope And Verification

This is a local implementation/review/build task. No push, cloud PR progression, merge, workflow/dependency/version/signature changes, system-security changes or automatic installation. Other unmerged communication and single-pass compaction branches are excluded. The previously generated task-dialog image is historical, not the implementation target.

Required evidence includes A/background plus B/new power notification selection and exact intent, B/C interaction ordering, completed old intent recovery, live conversation replay without START, per-run stop, preparing/terminal/recreation races, durable idempotent import/ACK, and foreground/virtual GUI presentation regressions. Run actual Kotlin compilation, lint, the complete unit suite and standard original-signature Debug/Release builds on the final frozen commit using the already configured ARM64 host toolchain. Code, tests, packages, installation and OEM acceptance are distinct. The user installs manually and tests native-pill behavior, live navigation, controls, overlay/virtual-screen presentation and performance.
