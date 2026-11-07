# GUI-P0: recover the physical capsule and preserve task controls

## User goal and observed failure

On the installed GUI-P0 QA build `569d59ab900446817b7d1535abe29ebf37559e83` (same source tree as feature `5ee7213309d301629b3452b2c98a5d2c64e5328d`), the original Eta-owned operation capsule flashes and disappears after power-button invocation; task execution continues without its persistent control surface. This concerns the application's capsule, not the OEM notification island. Existing expand/collapse and pause/resume/takeover controls must remain usable; this is a repair of existing behavior, not authorization for new UI design.

## Evidence and causal boundary

A bounded on-device investigation reproduced two removals of Eta from the enabled-accessibility-service list by the ColorOS security-center accessibility process. The system subsequently restored the service through Eta's existing protection backend. While the GUI owner was still active, system window inspection no longer contained the capsule or glow, although Runtime retained its presentation state. The later local removal produced WMS `Failed looking up window` warnings. The security center's internal risk decision is not established; do not claim it is fixed or bypass its protections.

The precise missing application contract is accessibility connection lifetime -> overlay context/token lifetime -> invalidation of old View references and draw confirmation. A prior `gate_visible` event is not physical-display evidence after the connection changes. The existing 500 ms release policy also removed the task-control surface while a nonterminal task continued; task controls and GUI ownership must not share one lifetime.

## Required repair

- Observe real accessibility connection generations, including same-instance reconnects. A late disconnect from an obsolete instance cannot clear a newer connection.
- Bind overlay context, window manager/type, and visible-frame confirmation to a consistent connection snapshot. On loss/reconnect, invalidate obsolete presentation state rather than trusting a locally attached old View.
- Recover the current eligible task/owner's existing capsule on a valid connection; require a fresh owner/window/connection-specific frame before GUI authorization. Never replay an uncertain action or retain a stale owner's authority.
- Restore the original task-lifetime expandable control surface, while non-GUI work is represented neutrally as thinking/waiting rather than falsely claiming ongoing phone manipulation. Preserve foreground suppression, terminal cleanup, pause/resume and the original targeting of controls.
- No resident polling, new monitoring framework, or repeated background repair loops.

## Preserved behavior and non-goals

Preserve existing Miuix components, theme, geometry and animations, virtual-display routing/preview, materialization, capsule clearance and touch-through, user takeover, entry dismissal/screenshot exclusion, cancellation and concurrency. Do not change provider prompts/history prefixes, sessions/accounts, signatures/version policy, CI execution paths, OEM security settings or the accessibility protection backend. No official upstream sync, business merge, automatic installation or release is authorized by this repair.

## Dependencies and source identity

- Feature remains PR [#24](https://github.com/bianwuya/Eta/pull/24), `feat/gui-p0-visible-handoff`, targeting `local`; P0 is not merged.
- Starting P0 feature: `5ee7213309d301629b3452b2c98a5d2c64e5328d`; prior installed QA source: `569d59ab900446817b7d1535abe29ebf37559e83`.
- Latest stable dependency at repair start: `9b6edc498545a5f905ee169959b5a66f60ad5ca6` (merged [#27](https://github.com/bianwuya/Eta/pull/27)), preserving P6/P7 and the accepted HTML, subagent history/statistics and TLS fixes. It was merged normally into the P0 feature before this repair. The new QA package must not regress to the earlier P5-only base.
- The QA CI/signing infrastructure from [#2](https://github.com/bianwuya/Eta/pull/2) is already in `local`; do not rebuild it or weaken its checks.

## Verification and delivery contract

Tests must cover accessibility loss with obsolete Views still locally attached, same/new-instance reconnects, late old callbacks, connection changes during window creation, stale draw rejection, current-owner recovery, owner handoff, release into non-GUI work with neutral interactive controls, pause/cancel/terminal cleanup and timeouts. Keep existing clearance/materialization/entry-guard tests. Phone-side work is editing and lightweight static checks only; full Lint/unit suite and standard Debug/Release build run in the existing GitHub PR check.

New feature head, current base, actual PR merge-candidate source, exact check/sign runs and both APK hashes/certificate must be recorded in the trusted QA receipt and PR package comment. Reuse checked APKs for signing; no second compilation. This document is not a receipt and does not claim those stages have completed. Physical capsule visibility and user interaction remain manual device acceptance after the user installs the new package. A passing Compose/Robolectric test is not proof of OEM display acceptance.
