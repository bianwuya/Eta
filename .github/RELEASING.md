# Eta 个人版维护与发布（local 稳定分支）

## 分支与两条交付线

- `main` 是原作者 `Mangi-11/Eta` 的同步镜像，不放个人功能或签名改动；`local` 是个人稳定分支，也是当前 GitHub 默认分支。功能从独立分支发起 base=`local` 的 PR。
- **QA 线（拟上线）**：PR 完整检查运行 Lint、全量单测并一次构建标准 Debug/Release；成功后由可信 `local` 上的签名工作流接收 `workflow_run`，只对合格的同仓库、仍开放未合并且精确 HEAD 对应的 PR 构建产物签名，不重编译、不合业务 PR。也可按检查 run ID 补签；以实际工作流支持为准。双包和回执供本人手动安装验证，非稳定发布。多个 PR 可独立并行，同一 PR 更新后旧检查不可当作新 HEAD。
- **稳定线**：完成检查与必要审查后，经明确合入决定正常通过受保护 PR 合入 `local`，由 `Eta Build` 生成原签名标准 Debug/Release。两条线不修改 `main`、分支保护或签名身份，也不自动安装。

**上线状态**：上述 QA 签名链依赖工作流变更合入默认分支；在流程 PR 合入前，新的 `workflow_run` 不会因此文档生效。代码、检查、真实签名双包和本人验收必须分别提供证据，不能把计划或文档当作上线/签名实证。

官方同步与集成仅手动且由用户决定；不定时、不随普通 PR 自动合入。参见 [官方更新流程](UPSTREAM_AUTOMATION.md)。按需运行 `Sync upstream main (manual)`，需要审阅时另运行 `Prepare upstream PR (manual)`；它们不自动 merge/close。确需把官方更新合入个人版时核对 base=`local`、compare=`main`，按受保护流程审阅并使用 merge commit，勿把 local 反向合入 main。上游 main 自带构建触发器可能仍运行，其产物不是个人稳定版。

## PR 检查与审查上下文

`PR Check` 不读取发布签名 Secrets，保留既有必需检查与完整 Lint、单测、标准 Debug；新流程还构建标准 Release 并上传 QA 输入。实际检查 run、精确源码和结果以 Actions 为准，失败不可冒充通过。

自动签名要求检查执行定义与可信 base 一致，包括 PR Check、setup-build/actions、wrapper、Gradle 脚本/配置和构建插件入口。改这些基础设施的 PR 仍可构建和审查，但不能凭自己改过的检查给自己自动签包；应先单独审查、正常整合相应基础设施。此约束不是对任意 PR 测试代码的沙箱或独立代码可信证明，签名与 CI 都不代替代码审查。

PR 正文或仓库内版本化上下文必须包含真实目标、保留行为和非目标、前置/关联 PR 与具体 SHA 和合入状态、包包含/不包含、风险和必要测试；见 [PR 模板](PULL_REQUEST_TEMPLATE.md) 与 [上下文模板](review-context/TEMPLATE.md)。云审查应沿链接读取完整必要需求，不只看 diff，也不依赖本机 scratch。检查 PR head 与实际 checkout 的 merge candidate source SHA，不能混为一谈。

关联记录应追溯到精确 check run、签名 run、两包摘要及证书；交付时核验 `qa-receipt.json` 与 `REVIEW-CONTEXT.md` 中的来源和范围，字段以实际制品为准。不把模型已读取或 CodeRabbit skipped 当审查通过；不自动开启付费审查，也不将 AI 报告当作合入、安装或其他操作授权。

## QA 与稳定包使用

QA 流程上线且真实运行成功后，从对应 run 获取两个原证书签名的标准 APK 和回执；核对 PR、feature head、base SHA、实际 checkout source SHA、check/sign run、两包 SHA256、包名、版本及证书，再交本人手动安装。QA 包只包含该次实际构建来源，可能是与 base 的合并候选，不假设包含其他并行 PR。开发签名的 PR 原始构建不是交付包；等待本人实际测试，不把下载或安装等同验收。

稳定线的 `Eta Build` 由推送 `local`、手动选择 `local` 或符合 local 历史校验的 `v*` 标签触发，需核对精确运行结果、包名、版本、两包摘要和原证书。标准 Debug 会用个人证书重签，Release 使用同一身份；只取对应 local 的个人版制品。

QA 与稳定包均沿用已有 `applicationId`、签名身份及 `versionCode`，通过构建元数据区分。版本名变化不提高 versionCode；设备已装更高版本、证书不匹配或 ROM 拒绝等版本覆盖时可能失败，不为绕过失败而盲目卸载造成数据丢失。重要数据按需备份，QA 包不当作正式 Release 发布。

签名阶段必须在可信默认分支环境中处理已校验的外部构建产物，不检出或执行功能分支代码、不运行 Gradle；PR 构建阶段不注入签名 Secrets。安全和真实运行结果以实现及独立审查、Actions 证据为准。

## 签名 Secrets

必须在 `Settings > Secrets and variables > Actions` 配置：

- `ETA_RELEASE_KEYSTORE_BASE64`：现用发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`
- `ETA_RELEASE_KEY_ALIAS`
- `ETA_RELEASE_KEY_PASSWORD`

不得更换已安装版本所使用的签名密钥，不得把 KeyStore、密码或 Base64 证书正文提交到 Git。
仅可信签名/发布 Job 使用证书；PR Check 不持有 Secrets。

macOS 可以用以下命令复制证书的 Base64：

```bash
base64 < /path/to/Eta-release.jks | tr -d '\n' | pbcopy
```

GitHub CLI 也能配置 Secrets：

```bash
base64 < /path/to/Eta-release.jks | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## 发布带版本号的 APK

1. 在 `local` 修改 `versionName` 和递增 `versionCode`（日期+序号，必须大于之前已安装版）。
2. 先完成 PR 审查、合并和 CI 检查；确认 local 是待发布的准确提交。
3. **从 local 创建 `v<versionName>` 标签**，不要从 main 创建标签。
4. 推送该标签，工作流会检查标签提交属于 local 历史、标签名与 APK versionName 相符。
5. Lint 和测试以及签名构建成功后，自动创建仅含 Release APK 的 GitHub Release **草稿**。
6. 检查包名、版本号、签名证书指纹与附件后，再手动点击 Publish Release。

例如，在已检出并拉取最新版 local 的电脑上：

```bash
git checkout local
git pull origin local
git tag v3.4.1
git push origin v3.4.1
```

这只是例子，真正的标签必须与 build.gradle.kts 中 versionName 一致、且不能覆盖旧标签。

## 不同电脑、手机统一构建

不需要把源码和 JDK/SDK 分别放在每台设备上：所有人从 GitHub 访问同一仓库的
`local` 分支，让 GitHub Actions 的 Ubuntu Runner 使用相同的 JDK、SDK、Gradle 配置编译。
只要选同一提交、同一签名证书与构建参数，构建来源一致。所有设备均从同一 Actions 页面下载 APK。

## 重要边界

- `main` 保持官方镜像；个人稳定功能经受保护 PR 合入 `local`，QA 出包不要求合业务 PR。
- PR Check 不使用签名密钥；可信签名链仅处理符合来源、完整性和当前状态约束的产物；稳定 Tag 需通过 local 历史校验。
- Release 自动创建的是**草稿**，不自动发布或升级手机里的 App。官方 main 的上游工作流不应为禁用而破坏镜像。
