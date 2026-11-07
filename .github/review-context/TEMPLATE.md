# 审查上下文模板

复制本模板或在 PR 正文提供等价信息；只写任务必需内容，链接仓库内版本化资料或 GitHub PR，不依赖本机文件。云审查应沿链接读全必要需求，不只读 diff。不要公开私人原文、凭据或无关数据。

- **目标与背景**：真实需求及约束。
- **范围**：保留的行为、明确非目标；此构建包含与不包含的功能。
- **关联与前置**：PR 链接、具体 SHA、合入状态及依赖关系；变动时更新。
- **源码身份**：feature head、base SHA 与实际 checkout 的 merge candidate source SHA 各自记录，不混同。
- **风险与验证**：必要测试、实际检查结果、尚未验证/本人验收的内容。
- **制品追溯**：精确 check run、sign run、Debug/Release 各自摘要与证书；链接实际 `qa-receipt.json` 和 `REVIEW-CONTEXT.md`，未产出标待完成，不臆造字段或结果。

AI 已读或 CodeRabbit skipped 不等于审查通过；AI 报告不授权合入、部署或付费调用。
