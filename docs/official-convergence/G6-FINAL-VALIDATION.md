# G6 最终本地验证与候选包

结果：**获批 v2 正式实现与本地候选交付通过；真实设备体验未验收**。源码提交 `2765b907b85c68ccf7bedf856b3fd7f76208a758`，源码树 `598182d1b041697387603ab2701d2767abe05d5c`。后续报告提交只增加文档，不改变已测试、已打包源码。原 local、main 和 RC2 未修改。

## 实际检查

| 检查 | 实际结果 | 任务目录证据 |
| --- | --- | --- |
| 当前源码完整 Debug 单测 | 443 suites / **2752 tests / 0 failed / 0 errors / 1 skipped**, exit 0 | `runs/g6-full-01/summary.json`、`xml/`、`gradle.log` |
| Debug Lint | **0 errors / 437 warnings / 2 hints**, exit 0 | 同运行 `lint/lint-results-debug.xml` / html / txt / sarif |
| 正式 UI + Runtime/引导定向 | 7 suites / 33 tests / 0 failed / 0 skipped | `runs/g6-acceptance-03` |
| 原工作流 Python 检查 | 18 tests / OK | `runs/g6-workflow-tests.log` |
| Debug / Release 标准双包 | `assembleDebug` / `assembleRelease` **exit 0**, 91 actionable tasks，77 executed | `runs/g6-signed-build-01/build.json` / `command.txt` / `gradle.log` |
| 包签名与身份 | 两包 apksigner + aapt2 exit 0；v2 signature true；原证书；仅 arm64-v8a | 同运行 `signature-{debug,release}.txt` / `badging-{debug,release}.txt`；`APK-RECEIPTS.json` |
| 源码校验 | 单测 1378 文件、双包 1379 文件，均 0 字节差异；uid 1000 eta-test | 两运行 `source-byte-check.json` / `host-user.txt` |
| 提交检查 | 原 eta-check / pre-commit hook、Git CRLF-aware whitespace check exit 0 | 隔离提交及任务回执 |
| 原现场保护 | HEAD、branch、status、index、diff、文件 SHA256、原 refs 全一致 | `protection-check-g6-implementation.json`；交付后再复核 |

宿主沿用现成 WSL2 `Eta-Test-20261112` / 普通用户 `eta-test`、JDK 25.0.2、Gradle 9.7.1、SDK 37，不重建环境、不改工具链或依赖。新受控 archive 校验后才测试。`G6-EVIDENCE-AUDIT.json` 证明测试快照 `78324fd8` 与源码提交的应用/测试完全一致，唯一新增为实施说明。与 v2 的 2734 项相比新增 18 项，无删除；原 integration 2934 项到当前数量的差异延续 G2/G3 已批准退出的自制 plan/bus 测试，逐项见 `TEST-INVENTORY-DELTA.json` 与新增 G6 清单。

唯一 skip 仍为 `EtaWaveShaderTest.effectCompilesWithAndroidShaderCompiler`：Robolectric native runtime 不支持 child shader。未新增跳过、关闭 Lint 或更改 baseline。双包运行复制的旧 XML 只用于归档，不代表该构建又运行了测试；完整测试成功只引用 `g6-full-01`。

## 包文件

目录：`F:/eta/projects/eta-official-g0-g5-20261112/candidates/2765b907b85c68ccf7bedf856b3fd7f76208a758`。

| 变体 | 文件 / 字节 | SHA256 |
| --- | --- | --- |
| Debug | `Eta-v3.4.1-debug+2765b907b85c68ccf7bedf856b3fd7f76208a758.apk` / 33898636 | `676a20b1b67f8511d12c9daeaac82969a023b059499bd0d6cba6d72ec0e8f6ef` |
| Release | `Eta-v3.4.1+2765b907b85c68ccf7bedf856b3fd7f76208a758.apk` / 7782676 | `74e54485c94773305eaf0a10dec6de2614287d0cbb5000843223e706b1592549` |

包名 `io.github.mangi.eta`，versionCode `2026110901`，基础 versionName `3.4.1`，仅 buildMetadata 追加真实源码 SHA。官方 3.4.2 用于实现对照，不冒称整个候选已升级到 3.4.2。两包证书 SHA256 均为 `a7a92cca03f7d7390ab74001c97efbfe4ff4289b0dc4ffa21d6ebc3cee1c81a8`，与原签名一致。签名参数仅由原 `eta-signing.ps1` 在进程内加载，经 WSL 环境传递，未写日志、参数或中间密钥文件，未修改认证或签名资产。

## 功能与安全回归

- 官方主干与删除依据：`OFFICIAL-DELTA-MATRIX.md`、`OFFICIAL-HUNKS.json`、G2/G3 报告。Runtime/子代理沿官方执行器，仅保留中转 SSE、稳定 session/cache、ACK/checkpoint、观测和安全降级的已证实增量。G6 不再次改 Runtime。
- 主代理 ≥5 轮、双子代理 ≥5 轮、重建内存、意图换模型/账号/endpoint 的隔离，见完整运行 `OfficialContinuityTest` / `OfficialSubagentRecoveryTest` 及 `CACHE-CONTINUITY-MATRIX.md`。
- 已发生副作用或终态未知不重放；同账号启动前拒绝才降级，主请求/压缩模型分别保留。原策略 6 项、SSE/恢复/取消套件均纳入本次全量。
- 引导真实 Messenger → Runtime → 原 SubagentExecutor 用例通过；长请求未结束时父代理响应；子实例继续、两个结束时刻的结果各一次。闩锁代替原不成立的固定汇总次数断言，未放宽唯一性或取消边界。
- 生图目录 UI 与底层 fake server 已覆盖，不发生真实目录/付费生图请求；缺缓存字段显示未知，实例统计不混入另一个子代理。
- 正式 UI 截图/动画/状态对照见 `UI-VALIDATION.md`，47 个产物哈希在 `ui-validation/acceptance-03/ASSETS.json`。真实 Compose 不是 HTML；原图片查看器未改，现有 10 项对话框与 5 项缩放数学测试通过。

## 失败记录及限制

- 首轮 COUI 调用缺命名参数，修正后 compileDebugKotlin exit 0。初始 UI 测试中 Android Keystore 不在 Robolectric 实现，改测试夹具从原默认加密存储注入假凭据；生产加密机制未弱化。
- 测试宿主仅 measure/layout 会留下 NavDisplay draw-time 状态未刷新，补逐帧真实绘制后目录端到端通过。手动模型输入曾误选底层搜索框，改当前窗口输入定位并断言 EditableText。
- 统计 fixture 原在初次读取后才写入，3 秒刷新前即断言；改为先写 fixture，不改变生产节奏。并发测试由单 release 改逐子代理闩锁，明确验证先后完成。
- 首次独立验签入口缺 Java 环境；随后发现 SDK 37 apksigner 输出 `V2 Signer:`，与旧 `Signer #1` 不同。验证直接匹配官方实际 SHA256 字段，最后两包真实验签通过。失败日志保留，不冒称首轮成功。
- 自动审批拒绝递归清理测试生成的两处 `__pycache__`（blocked by policy）。随后采用可逆保留：将两个明确的自生成 `.pyc` 文件逐个移动到任务 `verification-cache/g6-workflow`；没有再次删除、改权限、提交或打包它们。
- **未测**：实体手机/OEM 圆角、IME、TalkBack、物理返回手势、长列表边缘自动滚动和动画性能。Robolectric 导航用 32dp fixture，软件 shader 关闭；编译、截图及指针绿不等于手机体验验收。没有真实服务端缓存率/费用测试。

## 回退与边界

`codex/eta-official-g6-implementation-checkpoint` 固定源码与 APK SHA；交付报告 ref 为 `codex/eta-official-g6-checkpoint`，精确值在任务 `CHECKPOINTS.json`。回看 G5 v2：另建受控 checkout 到 `1c4d61e163b311dc8e3d1b07523c923259ea497c`。禁止对原 local/integration reset、clean、切分支或提交。原 local 103 WIP、RC2 五文件保留。

本轮完成候选构建与本地验证，**未安装 APK、未 push、未合并 main/local、未改密钥、未发真实收费请求**。用户设备体验仍待独立验收；本报告不代表手机已经更新。
