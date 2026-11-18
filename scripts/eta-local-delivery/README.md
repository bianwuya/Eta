# Eta GitHub 自动同步：本地验证优先（2026-11-18）

## 当前已启用
- 已授权公开仓库：`https://github.com/bianwuya/Eta`。
- **仅限**受控隔离树 `F:/eta/projects/eta-night-20261118/worktree`，分支 `night/v344-to-main-20261118`；原生 Git `post-commit` 钩子位于独立 `repository.git/hooks/post-commit`。
- 初次已确认手动上传 `bd557bbb9ccb51d52b75b297a0e921d3f8afe0f2`，`git ls-remote` 读回一致。GitHub 此分支没有 Actions run。
- 之后每次 **git commit** 完成，钩子调用 `eta-auto-push.ps1`，只在 HEAD、分支、工作树、远端白名单和敏感文件路径检查通过后启动。
- WSL2 `Eta-Test-20261112` ext4：`:app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug --offline`，新源码快照；Windows 用既有原签名 `:app:assembleDebug :app:assembleRelease --offline`，然后 `apksigner verify`。
- 全部通过、执行期间 HEAD/工作树未发生变化才非强制 `git push origin HEAD:refs/heads/night/v344-to-main-20261118` 并读回 SHA。失败保留本地提交和构建证据，绝不强推、自动合并、安装或创建 PR。
- 为防止半成品和隐私数据上传，**不自动 git add/commit**，也不监听文件保存事件；有 WIP 时主动阻断。
- 日志：`F:/eta/07-构建与运行日志/eta-auto-delivery/`；`-CheckOnly` 为安全模拟测试，不构建也不推送。
- 停用：把独立 `F:/eta/projects/eta-night-20261118/repository.git/hooks/post-commit` 重命名为 `post-commit.disabled`。不会触及其他仓或提交。

## 云端限制
- 当前该开发分支未创建指向 `local` 的 PR，所以单独推送**不会**触发 GitHub Android 构建：`android-release.yml` 仅监听 `local`/版本标签；`pr-check.yml` 监听针对 `local` 的 PR；`device-test-apk.yml` 监听 PR Check 完成。**如果以后为此分支创建了 PR，则后续推送仍可能触发云端 PR Check / Device Test。**
- GitHub 上的 `local` 仍有旧自动云构建触发器。本次尝试直接改为手动触发，被仓库规则拒绝：`Changes must be made through a pull request`，且强制要求 `Lint / Test / Debug Build`。未绕过保护，也未修改 `main`/`local`。
- 如果要求 GitHub **所有**分支/PR 都不自动云构建，需要仓库管理员在 GitHub Actions 分别 Disable `Eta Build`、`PR Check`、`Device Test APK`，并调整 `local` 分支当前必需的云检查规则；否则新 PR 可能无法合入。保留 Upstream 同步的手动流程不变。

## 适用边界
- 当前自动钩子只安装在昨晚的独立仓库；正式 `local` 含大量 WIP，其他已有开发树存在未投放内容，均未自动上传。对未来工作树需逐个做来源/当前使用状态检查并安装自己的安全钩子，不能把此独立脚本硬套到别的 repo。
- 通过模拟钩子和 GitHub 首次上传/读回，不等于已经验证过钩子从新提交自动执行完整 10 分钟级测试打包；后续首次新代码提交仍须观察日志。

## AI 工具统一入口（2026-11-18）
- 总规则：`F:/eta/AGENTS.md` 第 8 节；专项按目标仓 `AGENTS.md` 与工程知识库规则读取。
- 工作区根新增 `CLAUDE.md`、`GEMINI.md`；主源码仓 `01-核心开发源码` 与本夜间隔离树均配置对应 `CLAUDE.md`、`GEMINI.md` 和 `opencode.json`（OpenCode 绝对路径引用总规则）。均为**本地指令入口，不复制规范全文**。
- 两个 Git 仓的 `.git/info/exclude` 仅精确忽略这三个**由本次新建**的入口文件，使旧 WIP 数量不变化、夜间隔离树维持干净，从而不误阻断已安装 Hook。需要把入口正式入库时必须另做显式审查和选择，不由 `git add -A` 隐式上传。
- 已核实 Windows 有 Claude Code 和 OpenCode 可执行入口；本轮未启动模型会话以免影响账号或消耗额度。Gemini CLI 命令未检测到，故仅提前放置配置文件，**未验证其运行时加载**。
- 指令文件不具有强制安全能力；Git Hook 当前只在本夜间仓库安装且可被绕过。要保证所有 Git push 都经本地验收，需要另行针对各仓及远端保护规则实施强制门禁，不能从文字约束推断已经全局生效。

## GitHub 版本化审查说明
本目录仅是本机现行脚本和 Hook 的**审查快照**，并非已启用的 Git Hook。脚本当前明确限定运行于 `F:/eta/projects/eta-night-20261118/worktree`，需本机专用工具链与受保护签名条件，不能直接在其他电脑/仓库执行。用户自己的 Git Hook 位于独立裸仓 `repository.git/hooks/post-commit`，不会自动随 Git 推送；本次没有修改其触发条件或安装新 Hook。克隆此仓库或仅查看该分支不会自动构建/推送。
