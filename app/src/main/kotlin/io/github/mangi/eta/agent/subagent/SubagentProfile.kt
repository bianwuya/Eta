package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Subagent 角色定义与工具白名单（涵盖内置 Profile 与用户在 `agents/<name>.md` 定义的自定义 Profile）。
 *
 * 所有 Subagent 均剥夺 `subagent` / `subagent_profile` 自身（禁止递归派发与自修改）
 * 以及独占式物理屏幕 GUI 与交互式浏览器工具，确保多个 Subagent 并发运行时互不干扰。
 */
internal data class SubagentProfile(
    val name: String,
    val summary: String,
    val whenToUse: String,
    val toolHighlights: List<String>,
    val roleInstruction: String,
    private val allowWriteFilesAndTerminal: Boolean = false,
    val builtin: Boolean = true,
    val override: Boolean = false,
    val explicitTools: Set<String>? = null,
    val disallowedTools: Set<String> = emptySet(),
    val filePath: String? = null,
) {
    /** Engineering profiles (file/terminal write access) get a larger observed-token window than read-only ones. */
    val defaultTokenBudget: Long
        get() = if (allowWriteFilesAndTerminal) ENGINEERING_TOKEN_BUDGET else READ_ONLY_TOKEN_BUDGET

    fun buildSystemPrompt(): String =
        "$SUBAGENT_ROLE_PREFIX\n\n$roleInstruction"

    fun filterTools(allTools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            val toolName = item.optJSONObject("function")?.optString("name").orEmpty()
            if (isToolAllowed(toolName)) {
                filtered.put(JSONObject(item.toString()))
            }
        }
    }

    fun isToolAllowed(toolName: String): Boolean {
        if (toolName.isBlank() || toolName in FORBIDDEN_TOOLS || toolName in disallowedTools) {
            return false
        }
        if (toolName == AgentSubagentToolCatalog.REPORT_TOOL_NAME) return true
        val customAllowlist = explicitTools
        if (customAllowlist != null) {
            if ("*" in customAllowlist) return true
            return toolName in customAllowlist ||
                ("personal_data" in customAllowlist && (toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads))
        }
        if (toolName in READ_ONLY_TOOLS || toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads) {
            return true
        }
        if (!allowWriteFilesAndTerminal) return false
        return toolName in CODER_EXTRA_TOOLS || toolName.startsWith("mcp_")
    }

    companion object {
        const val TYPE_EXPLORE = "explore"
        const val TYPE_CODER = "coder"
        const val TYPE_PLAN = "plan"
        const val DEFAULT_TYPE = TYPE_EXPLORE

        /** Observed input+output window defaults; engineering work spans more rounds before a pause. */
        const val READ_ONLY_TOKEN_BUDGET = 300_000L
        const val ENGINEERING_TOKEN_BUDGET = 6_000_000L

        private const val SUBAGENT_ROLE_PREFIX =
            "你当前作为 Subagent（子智能体）运行。你收到的 user 消息均由主 Agent 派发，而非终端用户直接输入。" +
                "工具活动默认只显示给界面，不自动成为主 Agent 的事实结论。关键发现、范围扩大、准备越界修改、阻塞时使用 subagent_report，" +
                "写明已确认事实及证据、当前处理、下一步、需决定事项和验收范围；不设统一报告字数，不删必要约束。" +
                "普通过程更新用 subagent_report(kind=\"progress\")，它只更新详情；关键的协调、接口约定或最终交接用默认的 coordination / kind=\"final\"，会送达主 Agent。" +
                "需要主 Agent 决定时 needs_decision=true，在完整批次边界等待；不要直接向终端用户提问。" +
                "主 Agent 的定向引导会在安全边界传入，按修订和证据继续，不重复已完成工作。最终回复仍需完整交接。"

        val FORBIDDEN_TOOLS = setOf(
            AgentSubagentToolCatalog.TOOL_NAME,
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME,
            AgentSubagentToolCatalog.CONTROL_TOOL_NAME,
            // Cross-conversation messaging stays a parent-only capability; children must not message
            // other conversations or recursively wake each other.
            "conversation_list",
            "conversation_send",
            "observe_screen",
            "release_gui",
            "present_to_user",
            "tap",
            "tap_area",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "set_clipboard",
            "press_key",
            "open_system_panel",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "launch_app",
            "open_uri",
            "browser_use",
            "memory_write",
            "character_memory_write",
            "skills_install_from_github",
            "terminal_daemon",
            "terminal_job",
        )

        val READ_ONLY_TOOLS = setOf(
            "read_file",
            "stat_file",
            "list_directory",
            "glob_files",
            "grep_files",
            "read_image",
            "web_search",
            "fetch_url",
            "get_current_context",
            "search_apps",
            "inspect_app",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "get_setting",
            "get_logcat",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "get_health_summary",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "search_clipboard_history",
            "get_clipboard",
            "get_flashlight",
            "get_device_state",
            "get_display_state",
            "get_sound_state",
            "get_hotspot",
            "read_personal_item",
            "summarize_bills",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_system_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
            "memory_get",
            "character_memory_get",
            "skills_list",
            "skills_read",
            "skills_read_resource",
        )

        private val CODER_EXTRA_TOOLS =
            (AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) - FORBIDDEN_TOOLS

        val EXPLORE = SubagentProfile(
            name = TYPE_EXPLORE,
            summary = "只读探索与多源信息检索",
            whenToUse = "Fast read-only agent specialized for exploring files, personal device data (calendar, notes, messages, notifications, bills, media, system memories), logs, and web pages. Specify desired thoroughness in the prompt: \"quick\", \"medium\", or \"thorough\".",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "personal_data", "get_logcat"),
            roleInstruction = "你是只读探索 Subagent（explore）。你的职责是快速、准确地检索文件、设备个人数据、系统状态或网页资料，" +
                "严禁尝试修改任何文件或设备状态。在最终回复中直接给出结构化证据、关键路径/时间点与结论摘要。",
            allowWriteFilesAndTerminal = false,
            builtin = true,
        )

        val CODER = SubagentProfile(
            name = TYPE_CODER,
            summary = "工程执行、文件编辑与终端任务",
            whenToUse = "General engineering and terminal agent with file-editing and shell execution tools; use it for delegated tasks that run commands, analyze APKs, edit files, or execute scripts.",
            toolHighlights = listOf("terminal", "read_file", "write_file", "edit_file", "glob_files", "grep_files", "web_search"),
            roleInstruction = "你是工程与终端执行 Subagent（coder）。执行代码开发、Git、Python/Node 脚本、文本处理或 APK 分析时，终端与文件工具均应显式使用 environment=\"linux\"；操作 Android 系统命令或设备状态时使用 environment=\"android\"；工作区与共享目录在两端对应同一物理文件夹，按操作性质选择环境与对应路径，无需重复搬运文件。你的最后一条消息就是交给主 Agent 的完整交接报告：" +
                "请清楚说明你执行了哪些命令或修改了哪些文件、关键输出与验证结果，以及任何未完成或需后续处理的事项。",
            allowWriteFilesAndTerminal = true,
            builtin = true,
        )

        val PLAN = SubagentProfile(
            name = TYPE_PLAN,
            summary = "只读架构分析与分步方案设计",
            whenToUse = "Read-only planning agent. Use when you need a step-by-step implementation or execution plan, key file/data identification, and trade-off analysis before making changes.",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "skills_read"),
            roleInstruction = "你是只读规划 Subagent（plan）。你拥有只读检索能力，不得修改文件或执行破坏性操作。" +
                "请在最终回复中清晰列出：1. 已确认的上下文事实；2. 仍待核实的关键问题；3. 分步执行方案与风险权衡。",
            allowWriteFilesAndTerminal = false,
            builtin = true,
        )

        val BUILTIN_PROFILES: List<SubagentProfile> = listOf(EXPLORE, CODER, PLAN)
        val BUILTIN_NAMES: Set<String> = BUILTIN_PROFILES.mapTo(linkedSetOf()) { it.name }

        fun resolve(
            name: String?,
            catalog: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
            customRole: String? = null,
            customTools: List<String>? = null,
        ): SubagentProfile {
            val normalized = name?.trim()?.lowercase()
                ?.replace(Regex("[^a-z0-9_-]+"), "-")
                ?.trim('-')
                .orEmpty()
            val matched = if (normalized.isEmpty()) {
                catalog.firstOrNull { it.name == DEFAULT_TYPE } ?: EXPLORE
            } else {
                catalog.firstOrNull { it.name == normalized }
            }
            val cleanRole = customRole?.trim().orEmpty()
            val cleanTools = customTools
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it !in FORBIDDEN_TOOLS }
                ?.distinct()
                .orEmpty()

            if (matched != null) {
                if (cleanRole.isEmpty() && cleanTools.isEmpty()) return matched
                return matched.copy(
                    roleInstruction = if (cleanRole.isNotEmpty()) {
                        "${matched.roleInstruction}\n\n补充角色要求：$cleanRole"
                    } else {
                        matched.roleInstruction
                    },
                    explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else matched.explicitTools,
                    toolHighlights = if (cleanTools.isNotEmpty()) cleanTools.take(8) else matched.toolHighlights,
                )
            }

            // 未命中预设时，当场创建仅用于本次会话的临时专职 Subagent，不落盘保存。
            val ephemeralName = normalized.ifEmpty { "general" }
            val instruction = cleanRole.ifEmpty {
                "你是专职处理当前子任务的 Subagent（$ephemeralName）。请高效利用可用工具收集证据或完成操作，" +
                    "并在最后一条消息中向主 Agent 返回结构化、完整的结论摘要。"
            }
            return SubagentProfile(
                name = ephemeralName,
                summary = cleanRole.ifEmpty { "临时专职 Subagent" },
                whenToUse = cleanRole,
                toolHighlights = if (cleanTools.isNotEmpty()) {
                    cleanTools.take(8)
                } else {
                    listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
                },
                roleInstruction = instruction,
                allowWriteFilesAndTerminal = true,
                builtin = false,
                explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else setOf("*"),
            )
        }
    }
}
