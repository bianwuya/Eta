# 并行迭代：快速 QA 双包与云审查关联

## 目标与范围

让独立功能改动及时在 GitHub 可见，并行长任务各自快速获得可手动安装的原签名标准 Debug/Release 包，同时让云审查能读懂真实需求、依赖、实际源码和制品来源。提速靠复用同次完整检查的构建产物，不缩减检查或重复编译签名；不依赖把业务 PR 合入稳定分支。

保留受保护 `local` 稳定线、现有签名身份、标准构建和官方更新仅手动决定的策略；不修改 `main`、保护规则或版本号，不自动合入业务、自动安装、进入 GUI-P1，也不自动启动付费 AI 审查。AI 报告不是合入、安装或付费授权。

## 已知关系与缺口（截至本任务基线）

- 流程 PR [#2](https://github.com/bianwuya/Eta/pull/2) 原 head `d639adb54a6c61d88b3d346010bbf2c839be31ec` 未上线；旧方案以手动构建单个 Release 为主，缺少标准双包和复用完整检查编译输出，无法满足本次效率与并行需求。此次在其历史上正常合并最新 `local`，不强推抹去历史。
- 稳定 `local` 基线 `f7511d0639d45ca76d618740e61ed13df300f84e` 来自已合入 [P5 PR #23](https://github.com/bianwuya/Eta/pull/23)；本流程工作分支原始合并提交 `8978a302fb0a7e397fc1b64d77b2b226dc7679e0` 的两个父提交分别是上述旧 PR2 head 与 local 基线。此 SHA 不是本次后续最终 feature head，实际以 PR 记录为准。
- 关联业务 [GUI-P0 PR #24](https://github.com/bianwuya/Eta/pull/24)：已知 head `9e31325ef65b60094a230714b4f189f46761a0b1`，基线 `0d9ec92f44c2205a6325400071f1de21f20ebac1`，截至记录时未合入。它处理 GUI 操作可见性交接；本流程只解决出包和追溯，不修改 P0 业务，不把 P0 视作已合入或已实机验收。原 [检查 run 42287550862](https://github.com/bianwuya/Eta/actions/runs/42287550862) 通过当时的 Lint/全量单测/Debug，不证明新流程的 Release 或签名成功。PR head 和状态可能变化，使用前实时核验。

## 两条线与制品边界

QA 线拟在 PR 完整检查一次构建标准 Debug/Release 后，由可信默认分支 `local` 的签名工作流基于 `workflow_run` 对合格制品签名，不重跑编译或合入业务 PR；受控补签以检查 run ID 对应。无签名 Secrets 的 PR 构建和不检出/执行功能分支代码的可信签名阶段隔离，校验来源和制品完整性。多个 PR 独立，旧 head 不冒充新 head。实际包可能是 feature 与 base 的 merge candidate；不假定包含其他并行 PR 或未合入的 P0。

稳定线仍是明确决定后正常经受保护 PR 合入 `local`，由 `Eta Build` 产出标准原签名双包。本人手动安装与实测，不将检查或制品等同实际验收。

## 必须核验与上线状态

PR 正文或版本化上下文记录真实需求、保留/非目标、相关 PR 与具体 SHA 和合入状态、包包含/不包含、风险与必要测试；云审查沿链接读完整必要需求而不只看 diff。本任务不公开导入私人任务书全文。

逐次记录 feature head、base SHA、实际 checkout 的 merge source SHA，以及精确 check run、sign run、Debug/Release 摘要和证书；链接实际 `qa-receipt.json`、`REVIEW-CONTEXT.md`，未产生的回执标待完成，不预设具体字段 schema。模型已读或 CodeRabbit skipped 不代表审查通过。

新 `workflow_run` 需要流程 PR 合入默认分支后才生效；截至该记录流程尚未上线、未实证自动签名双包或下载。上线需要主代理取得本次明确合入决定；不能从本文或任务实现推断该授权。需要完整 PR 云检查、可信签名边界审查和上线后真实运行证据，不能将文档或静态测试当作签名实证。上线前不合 PR 即不启用；上线后按实际合入提交正常 revert，不改写稳定历史。
