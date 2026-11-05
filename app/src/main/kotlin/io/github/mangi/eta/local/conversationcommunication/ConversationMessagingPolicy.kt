package io.github.mangi.eta.local.conversationcommunication

import org.json.JSONArray

/**
 * 由跨会话消息启动的 run（origin=conversation_message）的策略：
 *
 * - 不再获得 conversation_list/conversation_send，避免会话之间形成自动互唤链；
 * - 不据此执行提权、安装或安全设置类工具：对方消息只是数据/请求，不是用户授权。
 *
 * 终端等已在用户设置中明确授予的工具保持原权限，由用户既有授权约束，不在这里扩大或削弱。
 */
internal object ConversationMessagingPolicy {
    /**
     * 只保留“专用提权/安装/安全入口”这一类工具的禁止项；名单要小且明确，
     * 不把普通的文件、终端或设备操作工具一并封掉。
     */
    val originBlockedTools: Set<String> = setOf(
        "skills_install_from_github",
        "set_setting",
        "set_device_state",
        "app_state_control",
        "wifi_credentials",
        "read_sms_code",
    )

    fun isOriginBlockedTool(name: String): Boolean = name in originBlockedTools

    /** 复制后过滤，绝不修改按轮复用的原始工具目录。 */
    fun filterOriginRunTools(tools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (isOriginBlockedTool(name)) continue
            filtered.put(tool)
        }
    }
}
