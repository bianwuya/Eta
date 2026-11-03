# Eta 上游自动维护说明

## 分支职责（不可改变）

- `Mangi-11/Eta:main`：唯一官方上游。
- `bianwuya/Eta:main`：只镜像官方，不加入任何本地功能、提交或自动化配置。
- `bianwuya/Eta:local`：用户稳定版，使用受保护的 PR 合并入口；图片放大、分享接入、签名及已有功能必须保留。
- `automation/upstream-<upstreamSHA>-on-<localSHA>`：短期集成候选分支，可删除，不是发布分支。

不要引入 Eta666 的源码，不要强推 main/local，不要修改签名密钥，不要用 `git merge -X ours` 掩盖上游冲突。

## 自动化流程

1. `Sync upstream main`（`.github/workflows/sync-upstream.yml`）每小时检查官方 main，经 fast-forward 同步至 fork main；失败则不强推。
2. `Auto integrate upstream into local`（`.github/workflows/auto-integrate-upstream.yml`）响应同步完成/PR Check 完成，并每两小时检查一次。
3. 脚本确认 fork main SHA 与官方一致、local 尚未包含该官方提交，然后在独立分支执行正常 Git merge。冲突即停止。
4. 脚本创建面向 local 的 PR，主动使用 `workflow_dispatch` 启动 `PR Check`，规避 `GITHUB_TOKEN` 创建 PR 时可能不能正常触发普通 PR CI 的限制。
5. 只有本地产生的未经额外修改的双父 merge commit、最新 local 基线、必需 `Lint / Test / Debug Build` 成功、无高风险文件改动且 GitHub 检查允许时，才自动合并。
6. CI 失败、合并冲突、自动修复后的额外提交、权限/构建配置变化一律禁止无条件自动合并，由 ChatGPT 的“Eta 主线同步守护”每小时检查、尝试定点修复和报告。

## 设置与可能的限制

GitHub 仓库 `Settings → Actions → General → Workflow permissions` 中的 **Allow GitHub Actions to create and approve pull requests** 可能默认关闭。关闭时自动集成脚本会保留候选分支，但无法自行创建 PR；每小时的 ChatGPT GitHub 监控可以用 GitHub 连接器创建缺失的 PR。此设置是否开启，应以实际工作流日志为准，不要在日志中打印令牌。

- `PR Check` 使用无签名 Secrets 的 Lint、单测与 Debug 构建。
- 同证书的设备测试 APK 和 Release 构建由单独受控工作流处理；本自动化绝不读取 `ETA_RELEASE_*`。
- CI 通过不等于 Android 手机场景全部通过；敏感权限、Gradle、签名/工作流和实机功能回归必须特别审查。

## 故障处理

- **main 分叉或同步失败**：读取 `Sync upstream main` 日志；不能覆盖 main，核实官方 SHA、GitHub API 响应和是否存在自有提交。
- **无法创建 PR**：检查 `automation/upstream-*` 远端分支，使用 GitHub 连接器补建以 local 为 base 的 PR；不能直接推送 local。
- **PR Check 失败**：先读失败任务日志并确定具体原因。对临时网络失败有限重试；代码失败仅在独立分支做最小更改，经 CI 重新验证。不要关闭/跳过必需检查。
- **local 更新导致 PR 过期**：对未经额外修复的自动候选 PR 可关闭，让下一轮以最新 local 重建；有人工或 AI 修复提交时保留 PR 审查。
- **冲突或高风险文件改动**：停止自动合并并报告；必要时将明确修复放在另一个 PR，不要污染 main。
- **无人操作时的持续监控**：ChatGPT 中已设置每小时条件监控。自动工作流与 ChatGPT 监控互为兜底，但并非实时、永不失败的守护进程。
