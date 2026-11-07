package io.github.mangi.eta.local.guipresentation

import io.github.mangi.eta.agent.tool.ToolExecutionDecision

/** The entry may send BACK, so it needs the same visible hint as the action that follows it. */
internal object GuiActionPreparation {
    fun prepare(
        isCurrent: () -> Boolean,
        ensureVisible: () -> ToolExecutionDecision,
        dismissEntry: () -> Boolean,
    ): ToolExecutionDecision {
        if (!isCurrent()) return stale()
        val visible = ensureVisible()
        if (visible is ToolExecutionDecision.Reject) return visible
        if (!isCurrent()) return stale()
        if (!dismissEntry()) return ToolExecutionDecision.Reject(
            "ENTRY_SURFACE_NOT_READY",
            "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
        )
        if (!isCurrent()) return stale()
        return ensureVisible()
    }

    private fun stale() = ToolExecutionDecision.Reject(
        "GUI_PRESENTATION_STALE", "手机操作权已变化，本次未执行；请重新观察",
    )
}
