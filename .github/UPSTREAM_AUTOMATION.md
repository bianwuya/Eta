# Eta 官方更新：仅手动

个人版迭代优先。官方更新不再定时同步，也不随普通 PR 检查触发合入；用户决定何时同步、审阅和合并。

## 分支职责

- `Mangi-11/Eta:main`：官方上游。
- `bianwuya/Eta:main`：按需同步的官方镜像，不加入个人功能或工作流。
- `bianwuya/Eta:local`：个人稳定版，功能通过受保护 PR 合入；原签名、分享及已有定制能力保留。
- `automation/upstream-<upstreamSHA>-on-<localSHA>`：用户手动请求后生成的审阅候选，不是发布分支。

不强推 main/local，不换签名，不用 `git merge -X ours` 掩盖冲突。

## 日常快速迭代

`功能分支 → PR Check（Lint / 全量单测 / Debug）→ 合入 local → Eta Build（原签名标准 Debug + Release）`

这条链路不等待官方主线，也不触发上游集成。已有 Gradle 缓存与同分支并发去重保留；不靠跳过测试、绕过分支保护提速。

## 用户决定更新官方时

1. 在 Actions 手动运行 **Sync upstream main (manual)**，分支选 `local`。它只快进镜像 `main`，不改个人版，也不触发后续集成。
2. 按需手动运行 **Prepare upstream PR (manual)**，分支选 `local`。它只为当前镜像快照准备候选分支、PR 和一次检查，**从不自动 merge 或 close PR**。
3. 已有上游 PR 时仅提示链接/编号，不重复启动检查、不自动关闭旧修复。
4. 冲突在审阅分支按实际语义修复并重新验证。冲突失败不会触发定时重试，个人版 `local` 不受影响。
5. 检查和审阅完成后，由用户决定合入。保留 merge commit 祖先关系，不把个人版反向合入官方镜像。

Actions 若不允许用 `GITHUB_TOKEN` 创建 PR，工作流会保留候选分支并给出手动创建链接；不因此放开权限或绕过保护。

## 验证边界

- 上游手动工作流不读取发布签名 Secrets，也不执行 PR 中的代码。
- PR Check 不持有签名；原签名双包只由受控的 local/版本标签构建生成。
- 编译、测试、APK、安装、手机功能验收分别记录。无关的官方冲突不作为个人功能迭代的前置条件。
