# Codex usage/reset candidate v2

Date: 2026-11-14. Status: APPROVED by the explicit user reply recorded in UI-APPROVAL.md.

Candidate uses the existing CodexUsageContent in official ImageGenPage + ImageGenGroup,
MiuixScaffoldPage/CouiLazyScaffoldPage, TextButton/CouiButton, WindowDialog/CouiConfirmDialog
and MiuixDialogActions. Both chat and image account slots must use the same component
with their own fixed provider/account binding. No global navigation/theme changes.

Assets: F:/eta/01-核心开发源码/.analysis/20261114-v344-repair/ui-review-v2.
Eight light/dark Miuix/COUI screenshots cover usage expanded and reset confirmation.
Native Robolectric Compose Canvas, 360dp fixture, fontScale 1.15. Contour/squircle shaders
disabled for host support. No claim of device/OEM/IME/gesture validation.

Motion: existing theme dialog enter/exit and quota detail expansion. Reset opens a
target-account confirmation; cancellation makes no request. While redemption runs,
reset is disabled. Unknown outcome retains the same persisted request ID.
User was shown COUI light usage and Miuix dark confirmation and asked once via async input.

Approved G6 v2 layout remains governed by docs/official-convergence/UI-APPROVAL.md.
