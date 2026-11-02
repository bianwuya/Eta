# Eta 个人版维护与发布（local 稳定分支）

## 分支职责

- `main`：原作者 `Mangi-11/Eta` 的同步镜像。不要在 main 提交自己的功能、工作流或签名改动。
- `local`：自己的稳定版，保留系统分享等定制能力，是**唯一的个人版构建与发布来源**。
- `feat/<功能名>`：从 local 创建的功能分支。每个功能单独 PR，审查后合并到 local。
- 同步顺序：原作者更新 → GitHub 的 `Sync fork` 更新 main → 创建 `main -> local` 的 PR → 自动测试 → 人工确认合并 → local 构建。

> 注意：GitHub 的默认分支与上述工作流中的 main/local 是两回事。若要新建 PR 默认指向 local，
> 还需在 GitHub `Settings > General > Default branch` 将默认分支切换到 local。
> GitHub App 当前不能在本工作流中修改这个仓库设置；改默认分支不会删除或合并代码。
> 由于 main 保持原作者原样，main 上原有的上游构建触发器可能仍会运行，这是镜像策略的限制。

## 自动审查：PR Check

`local` 目标分支的 Pull Request 自动运行 `PR Check`：

1. Android Lint
2. Kotlin/Android 单元测试
3. Debug APK 编译

不会读取发布签名 Secrets。失败会上传报告。建议在 GitHub 的 `Settings > Rules > Rulesets`
为 local 设置必需检查 `Lint / Test / Debug Build`、禁止强制推送、要求 PR 审查。
仅 CI 通过不代表人工审查通过；PR 应保持 Draft 直到复核完毕。

只把原作者的更新合入 main，不把个人功能反向合入 main。同步 main 时注意保留 local
自己的工作流；如果官方同时改了工作流，在 main -> local 的 PR 内处理差异。

## 自动构建：Eta Build

下列来源可触发个人版构建：

- 推送到 `local`：自动执行 Lint、测试，生成 Debug 和使用自己证书签名的 Release APK。
- `Actions > Eta Build > Run workflow`：**Branch 选择 local**。不要选 main。
- 推送 `v*` 标签：仅当标签提交在 local 的历史里才允许执行后续任务。

注意：由于 main 严格镜像上游，其已有的 `Eta Build` 工作流可能也在 main 更新时构建。
这些 **main 构建产物不是个人稳定版**；只能取 `local` 工作流的签名 Release APK。

构建成功后打开具体的 Actions 运行记录，在 Artifacts 下载 `Eta-v...apk`。
普通分支构建会追加 `+<短 SHA>`；Debug 带 `-debug` 后缀。
Debug 签名与 Release 签名不同，不能作为已有个人签名版的直接覆盖升级包。

## 签名 Secrets

必须在 `Settings > Secrets and variables > Actions` 配置：

- `ETA_RELEASE_KEYSTORE_BASE64`：现用发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`
- `ETA_RELEASE_KEY_ALIAS`
- `ETA_RELEASE_KEY_PASSWORD`

不得更换已安装版本所使用的签名密钥，不得把 KeyStore、密码或 Base64 证书正文提交到 Git。
只有发布构建 Job 恢复证书；PR Check 不持有 Secrets。

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

- main 只跟进官方，不执行个人定制合并。
- local 是个人稳定版，功能从 feat 分支通过 PR 合入。
- PR Check 不使用证书；仅 local/tags 允许个人版 Build；Tag 需要 local 历史校验。
- Release 自动创建的是**草稿**，绝不自动发布或升级手机里的 App。
- 官方 main 的默认工作流存在于上游代码中，不应为禁用它而破坏 main 的原样镜像。
