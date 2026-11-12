# G0: frozen RC2 baseline

2026-11-12. Native execution chat: 01a1ce87-0eb0-70e0-a96e-bf56c2a34e84.

- Parent: dda9d6f506e38f366b27548d69c2d2761331bfa0.
- Independent branch: codex/eta-official-g0-g5-20261112, checkout under F:/eta/projects/eta-official-g0-g5-20261112/worktree.
- Applied exactly the five RC2 paths from patch SHA256 489c7d7cb0a2306e4bb69a3bb919d0b95d0e7a463abde54924afe578fe080642; original integration diff matched byte for byte before applying. No cache files staged.
- Fresh before snapshot tree a1b6c08dfc724a22cb55dc57a8625a0605d39c0a: 13 tests, 1 failure (realExecutorResumeCarriesHistoricalReportUntilNewUsageArrives). The historical draft timing failure did not reproduce in this run; no claim that every run reproduces it.
- Fresh after snapshot tree 226ae3e0097c83828418c8072802aa32f409d964: see g0-green/summary.json and XML. Original four affected suites plus available runtime/credential smoke tests executed with no failed assertions.
- Existing WSL2 Eta-Test-20261112, JDK25.0.2, Gradle9.7.1, SDK37 and verified offline cache reused. Snapshot archive hashes checked before execution. New isolated Linux source tree used; original fixed environment snapshots unchanged.
- Protection records: ../protection-before.json and protection-check-g0.json (relative to task directory). Original local HEAD, 103 status records, staged/unstaged diff, index and 116 source hashes unchanged. Original integration HEAD/index/diff/five files unchanged. Only this task's new refs may be added to its shared isolated bare repository.
- Return to the baseline safely by creating another worktree at this commit; do not reset the original local or integration trees. Stage commits and checkpoint refs are recorded in ../CHECKPOINTS.json.

The task runner initially lacked rsync. Replaced that optional copy step with Python byte-preserving source synchronization inside the new disposable Linux tree; no application changes or test exclusions were used to repair the host entry point.
