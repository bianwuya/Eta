# G0–G5 执行验证

本轮已到 **WAITING_UI_APPROVAL**。G0–G4 的隔离逻辑和 G5 预览已提交候选；正式可见 UI、G6、APK 和手机验收未开始。分支 `codex/eta-official-g0-g5-20261112`，工作树 `F:/eta/projects/eta-official-g0-g5-20261112/worktree`。

## 实际验证

| 范围 | 新快照 / 运行目录 | 实际结果 |
| --- | --- | --- |
| G0 RC2 5 文件 | `runs/g0-green`、`runs/g0-smoke` | 38 + 50 tests，0 failed |
| G1 官方对照 | `g1-static-validation.json`、`OFFICIAL-HUNKS.json` | v3.4.2 `db0004ca`；后续 main `4101bc70` 单列；221 文件 / 1037 hunk 归因，证据不足保留 REVIEW |
| G2 Runtime / 计划退出 | `runs/g2-pass` | 18 suites / 107 tests，0 failed |
| G3 官方执行主干 | `runs/g3-fresh-final` | 434 suites / 2718 tests，0 failed / 1 skipped |
| G4 假 HTTP | `runs/g4-fake-native` | 8 suites / 22 tests，0 failed；无真实收费请求 |
| G3 补强 + G4 + G5 最终树 | `runs/g3-g5-clean-fixture` | **437 suites / 2729 tests，0 failed / 0 errors / 1 skipped，exit 0** |
| Lint Debug | 同一最终树与运行，`lint/lint-results-debug.{xml,html,txt,sarif}` | **0 errors / 438 warnings，exit 0**；未关闭检查、无新增 baseline |
| 源码校验 | 同次 `source-byte-check.json`、`host-user.txt` | 1359 文件 SHA256 逐字节一致；uid 1000 `eta-test` |
| Git 提交检查 | `git diff --check`、原 `eta-check.py` / pre-commit hook | 实际 exit 0；未绕过原 hook |

最终实际源树：`a5131bb6b92cfa65621fdd1d6b04e16b83611811`。归档 SHA256 `c2f52ea431257bdff4c4c840faa93a76840ffa4e3e3aafa038cc14ec61b4c66c`。此树包含本次全部应用及测试变更；后续提交只加入报告与审核清单。精确 commit/source 映射和恢复命令见任务根 `CHECKPOINTS.json`；`EVIDENCE-AUDIT.json` 确认早期 G0/G2/G3/G4 提交与对应已测源码只差测试后报告。

执行命令使用已建立的 WSL2 `Eta-Test-20261112`，普通用户 `eta-test`，JDK 25.0.2、Gradle 9.7.1、SDK 37、原离线依赖。源码 archive 与 SHA256 校验后同步到本任务 `/opt/eta/trees/official-g0-g5`，不复测旧固定 integration 冒称新结果。

```powershell
python F:/eta/projects/eta-official-g0-g5-20261112/export.py <新快照名>
wsl -d Eta-Test-20261112 -u eta-test -- bash /mnt/f/eta/projects/eta-official-g0-g5-20261112/run.sh <新快照名> <唯一日志名> :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:lintDebug
```

先准确暂存拟测源码，或向 export.py 显式传入已提交 SHA。运行目录必须使用新名字；入口有任务锁并拒绝 root。单测已清理旧结果再完整执行；部分 Lint 任务复用 Gradle 正常输入哈希缓存。

## 失败记录与修正

- `g3-extra-red` 的两个断言复现压缩模型被聊天候选覆盖、回调失败被误判为模型认证拒绝。`g3-extra-green` 和最终全测均通过；修复位于现有策略/provider 边界。
- `g3-bound-review-verified` 新工具恢复 fixture 首轮未启用 `terminalTools`，真实工具目录因此拒绝该调用。只修正假配置权限；最终测试确认原工具执行一次、进程内存清空后换账号不再请求模型或重放工具。
- `g3-g5-final-full` 误以 WSL 默认 root 执行，3 个只读权限断言失败，不能计为通过。显式 `-u eta-test` 后同应用代码完整 2727 项通过。仅修复本任务产生的 root-owned 输出，未改 Windows ACL 或原工具链。
- `g3-g5-acceptance` 原 Messenger fixture 在创建前可能继承共享绑定，失败发生在 `bindingStarted`。测试前清理共享连接，并将旧 waiter 的退出排在新 fixture 建连前；修改只在测试目录，保留原断言与超时。最终同树全测通过。
- `g3-g5-final-verified` Gradle 日志为 test + Lint 成功，但运行中的 shell 脚本被编辑，导致收尾解析失败，缺少完整回执。本运行不计为通过依据；`g3-g5-clean-fixture` 使用固定脚本重新执行并保存完整 XML、exit、Lint 和用户/字节回执。
- 早期并发运行的 EOF/缺 binary 结果已作废。后续始终串行，任务 `flock` 防止同构建树重复使用。
- G4 native PNG fixture 和 G5 shader 限制见各阶段报告；没有放宽生产解码或修改应用 shader。唯一测试 skip 是原 `EtaWaveShaderTest.effectCompilesWithAndroidShaderCompiler`：Robolectric native runtime 不支持 child shader。

完整旧 integration 2934 项与当前 2729 项不是同一源码。删除的用例随用户撤销的 plan / Yieldable / Mailbox / DecisionBroker / TaskGroup 协议退出；保留混合测试中的普通失败、取消、权限、历史和统计断言。逐项 added/removed、删除文件清单见任务 `TEST-INVENTORY-DELTA.json`，不能拿旧数量当当前测试承诺。

## 保护与实际边界

原 local HEAD `a5b845fb244af37785d862fe1164f13b9b9d3460`、103 条 WIP、index/diff/文件哈希；原 integration HEAD `dda9d6f506e38f366b27548d69c2d2761331bfa0`、RC2 五文件和 index/diff 均由任务 `protect.py` 与初始指纹比较。最终回执为 `protection-check-final.json`。除任务独立 refs 外，原有 refs 未更改。

没有读取或变更真实凭据，没有真实付费请求、APK 构建/安装、push 或合并 local/main。官方 main 后续滚动提交只做对照，未整批合入。缓存测试证明客户端身份与前缀稳定，未测服务端真实命中率或费用。Android Keystore、网络账号、手机触摸/IME/系统返回、shader 动效和性能仍需 G6 获批后验证。

预览与批准范围见 `UI-REVIEW.md` / `UI-APPROVAL.md`。当前唯一继续实施可见 UI 的前置条件是用户明确审核；本记录不会代替批准或自动执行 G6。
