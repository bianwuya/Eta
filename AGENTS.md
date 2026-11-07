# Eta 本地工程规则

此文件只约束本仓库，不改变系统权限或当前用户指示。开工主动读本文件，无需用户重复提醒。

## 开始与知识入口

- 先读 `../eta-local/knowledge/INDEX.md`，按任务选一两篇；硬边界与精简设计原则见 `RULES.md`，当前状态见 `STATE.md`。索引不可用时可调用 Skill `eta-project-maintainer`，不要依据旧 PLAN 猜当前状态。
- `git status --short --branch`，确认任务相关差异，保留现有修改。`main` 保持纯上游；自定义在 `local` 或功能分支。上游 Mangi-11/Eta，自有 fork bianwuya/Eta。

## 默认开发边界

1. 小而集中，优先 `app/src/main/kotlin/io/github/mangi/eta/local/` 与现有扩展点；一个问题一个逻辑修复。触及>=5个已有上游文件先评估，不当作禁止合理重构的硬门槛。
2. 不为本地功能重写 versionCode/versionName，用 eta.buildMetadata 区分构建；不降低已有 Lint/测试，不把失败吞成成功。
3. 不提交秘密、私有备份、签名私钥、APK/构建输出；不输出可疑秘密正文。沿用现有release keystore，不随意换签名。
4. 普通修改不触发安装/卸载/整机备份。文档做链接检查，脚本做隔离测试；代码做对应编译/测试，交付APK用现有CI。不擅自push/发布或清理用户文件。
5. 不过度防御：复用现有模型，不造通用框架、常驻监控、无限重试或多层兜底；外部不可信输入做必要验证，内部类型化数据不重复防御。
6. 接收分享/拖放只追加真实输入框草稿，保留原文字，不自动发送；临时URI先稳定复制再释放权限。主聊天以外窗口不是已实现范围。

## PR 与云审查关联

PR 正文或仓库版本化上下文须写真实目标、保留/非目标、前置和关联 PR 的具体 SHA 与合入状态、包包含/不包含、风险及必要测试；引用 `.github/review-context/TEMPLATE.md`，不要只给本机路径。云审查沿链接读完整必要需求而非只看 diff；区分 feature head 与实际 checkout 的 merge source，并关联精确 check/sign run、双包摘要及回执。模型已读或 CodeRabbit skipped 不等于审查通过；AI 报告不是操作授权，不自动开启付费审查。

## 工具与完成标准

Linux执行代码/Git/脚本；Android执行设备操作。/workspace 与宿主 /data/local/tmp/eta 是同一物理目录，不来回复制环境文件。Root变化先实际检测，不绕过授权。

提交前运行 `python3 ../eta-local/scripts/eta-check.py --repo .`（默认staged）。本机hook实际启用情况查 `git config --get core.hooksPath`；换机器需重新接入，不把文档当强制沙箱。

代码完成、CI通过、APK、已安装、OEM实测分别报告，只有实际证据支持才标已验收。新坑更新知识库对应条目，不把整段日志存进规则。
