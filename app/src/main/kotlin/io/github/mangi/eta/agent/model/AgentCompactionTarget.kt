package io.github.mangi.eta.agent.model

internal data class AgentCompactionTarget(
    val config: AgentModelClient.ModelConfig,
    val provider: AgentProviderClient,
    val prompt: String = DEFAULT_PROMPT,
) {
    companion object {
        val DEFAULT_PROMPT = """
            为 Eta 整理继续任务所需的上下文。按相关性去除无关、重复和失效信息，不以缩短长度为目的删减必要语义。
            完整保留当前目标、有效约束与用户纠偏、关键决策及依据、实际操作和真实结果、关键路径与标识、必要证据与冲突、尚未确认和未解决的问题、下一步。
            有效旧摘要融入新摘要，保留更正及依据；不要把尝试当成功，不编造缺失事实。详细重复日志可改为明确的证据位置，但影响决定的失败、风险和冲突必须写明。
            如有角色剧情，保留角色关系、场景、剧情进展和未解决线索；虚构剧情、人设与真实设备操作和现实用户事实分开。
        """.trimIndent()
    }
}
