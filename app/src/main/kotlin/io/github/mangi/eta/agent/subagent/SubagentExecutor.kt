package io.github.mangi.eta.agent.subagent

import android.os.SystemClock
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行单次 `subagent` 工具调用：管理独立子上下文、过滤工具目录、实时镜像内部步骤，并向父 Agent 返回结构化摘要。
 */
internal class SubagentExecutor(
    private val conversationKey: String,
    private val parentController: AgentRunController,
    private val configProvider: () -> AgentModelClient.ModelConfig,
    private val capabilitiesProvider: () -> AgentToolCapabilities,
    private val baseToolExecutor: AgentModelClient.ToolExecutor,
    private val skillContext: SkillContext,
    private val memoryContext: AgentMemoryContext,
    private val mcpTools: JSONArray,
    private val executionContext: TerminalExecutionContext?,
    private val onProgress: (AgentEvent.SubagentProgress) -> Unit,
    private val onRequestStat: (io.github.mangi.eta.local.subagentstats.SubagentRequestStat) -> Unit = {},
    private val parentRunId: String = "",
    private val requestStatsEpoch: () -> String? = { null },
    private val compactionTarget: ((AgentModelClient.ModelConfig) -> io.github.mangi.eta.agent.model.AgentCompactionTarget)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
    private val childToolFactory: (() -> AgentModelClient.ToolExecutor)? = null,
    private val detailStoreFactory: (() -> SubagentDetailStore)? = null,
) : AutoCloseable {
    private val tasks = SubagentTaskManager(parentController)
    val coordination get() = tasks.coordination
    override fun close() = tasks.close()
    fun control(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        tasks.control(JSONObject(call.argumentsJson))
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun latestSnapshotJson(toolCallId: String): String? = latestSnapshots[toolCallId]

    fun execute(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
    ): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent 参数必须是合法的 JSON 对象")

        val prompt = args.optString("prompt").trim()
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
        if (prompt.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "prompt 不能为空")
        }
        if (description.isEmpty()) {
            return errorResult("INVALID_ARGUMENTS", "description 不能为空")
        }

        val resumeId = args.optString("resume").trim()
        val requestedType = args.optString("subagent_type").trim().lowercase()
        val isResume = resumeId.isNotEmpty()

        if (isResume && requestedType.isNotEmpty()) {
            return errorResult(
                "RESUME_WITH_TYPE_UNAVAILABLE",
                "resume 续接已有 Subagent 时不得同时指定 subagent_type；请仅传递 resume 与 prompt。",
            )
        }

        val entry: SubagentSessionStore.Entry
        val priorHistory: List<AgentModelClient.ConversationMessage>
        if (isResume) {
            val existing = SubagentSessionStore.get(conversationKey, resumeId)
                ?: return errorResult(
                    "SUBAGENT_NOT_FOUND",
                    "未找到可续接的 Subagent：$resumeId（可能已过期或不属于当前会话）",
                )
            if (!existing.running.compareAndSet(false, true)) {
                return errorResult(
                    "SUBAGENT_ALREADY_RUNNING",
                    "Subagent $resumeId 当前正在运行中，无法并发续接",
                )
            }
            entry = existing
            priorHistory = existing.history
        } else {
            val catalog = SubagentProfileCatalog.listAll()
            val customRole = args.optString("role").trim()
            val customTools = args.optJSONArray("tools").toStringList()
            val profile = SubagentProfile.resolve(
                name = requestedType,
                catalog = catalog,
                customRole = customRole,
                customTools = customTools,
            )
            entry = SubagentSessionStore.create(conversationKey, profile)
            priorHistory = emptyList()
        }

        return try {
            val task = tasks.start(entry.subagentId, toolCall.id, description, SubagentTaskManager.Budget.from(args),
                onFinish = { SubagentSessionStore.releaseRunning(conversationKey, entry.subagentId) },
                onState = { task, state, reason ->
                    val snapshot = SubagentActivitySnapshot(subagentId = entry.subagentId,
                        subagentType = entry.profile.name, description = description, prompt = SubagentDisplayText.redact(prompt).take(512),
                        status = state, currentAction = reason.ifBlank { state }, previewTruncated = prompt.length > 512,
                        updatedAt = System.currentTimeMillis())
                    latestSnapshots[toolCall.id] = snapshot.encode()
                    onProgress(AgentEvent.SubagentProgress(parentRound, toolCall.id, entry.subagentId, entry.profile.name, snapshot.encode()))
                },
            ) { task ->
                runChild(parentRound, toolCall, entry, priorHistory, prompt, description, isResume, task)
            }
            AgentModelClient.ToolResult(task.snapshot().put("ok", true)
                .put("started", true).put("completion_is_not_implied", true)
                .put("hint", "可继续独立工作；使用 subagent_control 查询、等待、引导或停止。启动不是最终摘要。")
                .toString(), command = latestSnapshots[toolCall.id]
                    ?: SubagentActivitySnapshot.initialFromArguments(toolCall.argumentsJson)?.copy(subagentId = entry.subagentId)?.encode())
        } catch (failure: Exception) {
            SubagentSessionStore.releaseRunning(conversationKey, entry.subagentId)
            errorResult("SUBAGENT_START_FAILED", failure.message ?: "启动失败")
        }
    }

    private fun runChild(parentRound: Int, toolCall: AgentModelClient.ToolCall,
        entry: SubagentSessionStore.Entry, priorHistory: List<AgentModelClient.ConversationMessage>,
        prompt: String, description: String, isResume: Boolean, task: SubagentTaskManager.Task,
    ): AgentModelClient.ToolResult {
        val profile = entry.profile
        val subagentId = entry.subagentId
        val childController = task.controller

        val resolvedConfig = runCatching { configProvider() }.getOrElse { throwable ->
            SubagentSessionStore.releaseRunning(conversationKey, subagentId)
            return errorResult("SUBAGENT_CONFIG_ERROR", throwable.message ?: "无法加载 Subagent 模型配置")
        }
        val modelLabel = resolvedConfig.modelDisplayName.ifBlank { resolvedConfig.model }
        val tracker = StepTracker(
            subagentId = subagentId,
            subagentType = profile.name,
            description = description,
            prompt = prompt,
            model = modelLabel,
            resumed = isResume,
        )

        val detailStore = detailStoreFactory?.invoke()
        val detailId = task.id
        var lastEmitNanos = 0L
        var snapshotSequence = 0L
        val publicationLock = Any()
        fun publishSnapshot(force: Boolean): Unit = synchronized(publicationLock) {
            val now = nanoTime()
            if (!force && now - lastEmitNanos < PROGRESS_THROTTLE_NANOS) return
            lastEmitNanos = now
            val source = tracker.snapshot()
            snapshotSequence = maxOf(snapshotSequence + 1, System.currentTimeMillis())
            val full = source.copy(detailId = detailId, updatedAt = snapshotSequence,
                prompt = SubagentDisplayText.redact(source.prompt), output = SubagentDisplayText.redact(source.output),
                error = source.error?.let(SubagentDisplayText::redact))
            if (force) runCatching { detailStore?.save(detailId, full) }
                .onFailure { io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled("subagent_detail_write_failed") {
                    "Subagent detail storage failed: type=${it.javaClass.simpleName}"
                } }
            val encoded = full.copy(prompt = full.prompt.take(512), output = full.output.takeLast(512),
                steps = full.steps.takeLast(12).map { it.copy(detail = it.detail.takeLast(200),
                    detailTruncated = it.detailTruncated || it.detail.length > 200) },
                previewTruncated = true).encodeBounded(12_000)
            latestSnapshots[toolCall.id] = encoded
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = encoded,
                ),
            )
        }

        task.onState = { state, reason ->
            tracker.controlState(state, reason)
            publishSnapshot(force = true)
        }
        tracker.controlState(task.snapshot().optString("status"), "")
        publishSnapshot(force = true)

        val ownedTools = childToolFactory?.invoke()
        val tools = ownedTools ?: baseToolExecutor
        val toolBinding = (ownedTools as? AutoCloseable)?.let { childController.register { it.close() } }

        val childToolExecutor = AgentModelClient.ToolExecutor { subCall ->
            childController.throwIfCancelled()
            if (subCall.name == AgentSubagentToolCatalog.REPORT_TOOL_NAME) {
                tasks.report(task, JSONObject(subCall.argumentsJson))
            } else if (!profile.isToolAllowed(subCall.name)) {
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "SUBAGENT_TOOL_FORBIDDEN")
                        .put(
                            "message",
                            "当前 Subagent (${profile.name}) 不允许调用工具 ${subCall.name}",
                        )
                        .toString(),
                )
            } else {
                val stepId = "tool-${subCall.round}-${subCall.id}"
                val safeArguments = if (io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy.isSensitive(subCall.name)) {
                    "[敏感工具参数不保存]"
                } else SubagentDisplayText.redact(subCall.argumentsJson)
                runCatching { detailStore?.capture(detailId, stepId, "${subCall.name}\n$safeArguments\n[执行中]") }
                tools.execute(subCall).also { result ->
                    val safeResult = if (result.sensitive || io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy.isSensitive(subCall.name)) {
                        "[敏感工具结果不保存]"
                    } else SubagentDisplayText.redact(result.content)
                    runCatching { detailStore?.capture(detailId, stepId, "${subCall.name}\n$safeArguments\n$safeResult") }
                }
            }
        }

        val subagentConfig = resolvedConfig.copy(
            systemPrompt = profile.buildSystemPrompt(),
            subagentTools = false,
            supportsSearchSuggestions = false,
        )

        return try {
            val response = AgentModelClient.complete(
                config = subagentConfig,
                provider = io.github.mangi.eta.local.subagentstats.SubagentRequestStats.provider(
                    delegate = io.github.mangi.eta.agent.model.ProviderClientFactory.getClient(subagentConfig),
                    template = io.github.mangi.eta.local.subagentstats.SubagentRequestStat(
                        conversationId = conversationKey, subagentId = subagentId,
                        invocationId = toolCall.id, parentRunId = parentRunId,
                        profile = profile.name,
                        providerId = subagentConfig.providerId, model = subagentConfig.model,
                        purpose = "CHAT", ordinal = 0, resumed = isResume,
                    ),
                    baseline = entry.requestStatsBaseline,
                    collectionEpoch = requestStatsEpoch,
                    save = onRequestStat,
                ),
                compactionTarget = compactionTarget?.let { resolve -> {
                    val target = resolve(subagentConfig)
                    target.copy(provider = io.github.mangi.eta.local.subagentstats.SubagentRequestStats.provider(
                        target.provider, io.github.mangi.eta.local.subagentstats.SubagentRequestStat(
                            conversationId = conversationKey, subagentId = subagentId, invocationId = toolCall.id,
                            parentRunId = parentRunId, profile = profile.name, providerId = target.config.providerId,
                            model = target.config.model, purpose = "COMPACTION", ordinal = 0, resumed = isResume,
                        ), entry.requestStatsBaseline, collectionEpoch = requestStatsEpoch, save = onRequestStat,
                    ))
                } },
                prompt = prompt,
                toolExecutor = childToolExecutor,
                history = priorHistory,
                runController = childController,
                coordination = task.coordination,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = profile.filterTools(JSONArray(mcpTools.toString()).put(AgentSubagentToolCatalog.reportSchema())),
                executionContext = executionContext,
                capabilitiesProvider = capabilitiesProvider,
                sessionId = "$conversationKey-$subagentId",
                operationId = "${toolCall.id}-$subagentId",
                toolFilter = profile::filterTools,
                onEvent = { childEvent ->
                    val force = tracker.onChildEvent(childEvent)
                    publishSnapshot(force = force)
                },
            )
            val updatedHistory = SubagentContinuation.history(priorHistory, prompt, response.transcript, response.contextSnapshot)
            SubagentSessionStore.complete(conversationKey, subagentId, updatedHistory)
            tracker.markCompleted(response.content)
            publishSnapshot(force = true)

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put("status", SubagentActivitySnapshot.STATUS_COMPLETED)
                    .put("tool_calls", tracker.toolCount())
                    .put("summary", response.content)
                    .put(
                        "resume_hint",
                        "如需在此 Subagent 已有上下文基础上继续追问或深挖，可调用 subagent(resume=\"$subagentId\", prompt=\"...\", description=\"...\")。",
                    )
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } catch (throwable: Throwable) {
            val partial = (throwable as? AgentModelExecutionException)?.let { ex ->
                val partialHistory = SubagentContinuation.history(priorHistory, prompt, ex.transcript, ex.contextSnapshot)
                SubagentSessionStore.complete(conversationKey, subagentId, partialHistory)
                tracker.partialOutput()
            } ?: run {
                SubagentSessionStore.releaseRunning(conversationKey, subagentId)
                tracker.partialOutput()
            }

            val cancelled = parentController.isCancelled ||
                childController.isCancelled ||
                throwable is AgentRunCancelledException
            val errorMessage = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                tracker.markCancelled(partial)
            } else {
                tracker.markFailed(errorMessage, partial)
            }
            publishSnapshot(force = true)

            if (parentController.isCancelled) {
                throw AgentRunCancelledException()
            }

            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", if (cancelled) "SUBAGENT_CANCELLED" else "SUBAGENT_FAILED")
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put(
                        "status",
                        if (cancelled) {
                            SubagentActivitySnapshot.STATUS_CANCELLED
                        } else {
                            SubagentActivitySnapshot.STATUS_FAILED
                        },
                    )
                    .put("message", errorMessage)
                    .apply {
                        if (partial.isNotBlank()) put("partial_output", partial)
                        if (!cancelled) {
                            put(
                                "resume_hint",
                                "可调用 subagent(resume=\"$subagentId\", prompt=\"继续完成未完部分\", description=\"$description\") 恢复执行，或由主 Agent 直接接手。",
                            )
                        }
                    }
                    .toString(),
                command = latestSnapshots[toolCall.id],
            )
        } finally {
            toolBinding?.close()
            (ownedTools as? AutoCloseable)?.let { runCatching { it.close() } }
            runCatching { kotlinx.coroutines.runBlocking { detailStore?.prune() } }
        }
    }

    fun executeProfileManagement(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return errorResult("INVALID_ARGUMENTS", "subagent_profile 参数必须是合法的 JSON 对象")
        return when (val action = args.optString("action").trim().lowercase()) {
            "list" -> {
                val profiles = SubagentProfileCatalog.listAll()
                val items = JSONArray().also { array ->
                    profiles.forEach { profile ->
                        array.put(
                            JSONObject()
                                .put("name", profile.name)
                                .put("builtin", profile.builtin)
                                .put("description", profile.summary)
                                .put("when_to_use", profile.whenToUse)
                                .put("tools", JSONArray(profile.toolHighlights)),
                        )
                    }
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "list")
                        .put("agents_dir", SubagentProfileCatalog.defaultAgentsDir().absolutePath)
                        .put("profiles", items)
                        .toString(),
                )
            }

            "get" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "get 需要提供 name")
                val profile = SubagentProfileCatalog.get(name)
                    ?: return errorResult("SUBAGENT_NOT_FOUND", "未找到 Subagent Profile：$name")
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "get")
                        .put("name", profile.name)
                        .put("builtin", profile.builtin)
                        .put("description", profile.summary)
                        .put("when_to_use", profile.whenToUse)
                        .put("tools", JSONArray(profile.toolHighlights))
                        .put("disallowed_tools", JSONArray(profile.disallowedTools.toList()))
                        .put("prompt", profile.roleInstruction)
                        .apply { profile.filePath?.let { put("file_path", it) } }
                        .toString(),
                )
            }

            "save" -> {
                val name = args.optString("name").trim().lowercase()
                val description = args.optString("description").trim()
                val whenToUse = args.optString("when_to_use").trim()
                val prompt = args.optString("prompt").trim()
                val tools = args.optJSONArray("tools").toStringList()
                val disallowedTools = args.optJSONArray("disallowed_tools").toStringList()
                runCatching {
                    SubagentProfileCatalog.saveCustomProfile(
                        name = name,
                        description = description,
                        whenToUse = whenToUse,
                        tools = tools,
                        disallowedTools = disallowedTools,
                        prompt = prompt,
                    )
                }.fold(
                    onSuccess = { saved ->
                        AgentModelClient.ToolResult(
                            content = JSONObject()
                                .put("ok", true)
                                .put("action", "save")
                                .put("name", saved.name)
                                .put("description", saved.summary)
                                .put("tools", JSONArray(saved.toolHighlights))
                                .put("file_path", saved.filePath.orEmpty())
                                .put("message", "已保存自定义 Subagent \"${saved.name}\"，现在可直接通过 subagent(subagent_type=\"${saved.name}\", ...) 调用。")
                                .toString(),
                        )
                    },
                    onFailure = { error ->
                        errorResult("SUBAGENT_SAVE_FAILED", error.message ?: "保存自定义 Subagent 失败")
                    },
                )
            }

            "delete" -> {
                val name = args.optString("name").trim().lowercase()
                if (name.isEmpty()) return errorResult("INVALID_ARGUMENTS", "delete 需要提供 name")
                if (name in SubagentProfile.BUILTIN_NAMES) {
                    return errorResult("BUILTIN_SUBAGENT_IMMUTABLE", "内置 Subagent ($name) 不可删除")
                }
                val deleted = SubagentProfileCatalog.deleteCustomProfile(name)
                if (!deleted) {
                    return errorResult("SUBAGENT_NOT_FOUND", "未找到可删除的自定义 Subagent：$name")
                }
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("action", "delete")
                        .put("name", name)
                        .toString(),
                )
            }

            else -> errorResult("INVALID_ARGUMENTS", "未知的 action：$action（可选：list, get, save, delete）")
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optString(it)?.trim()?.takeIf(String::isNotEmpty) }
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    internal class StepTracker(
        private val subagentId: String,
        private val subagentType: String,
        private val description: String,
        private val prompt: String,
        private val model: String,
        private val resumed: Boolean,
    ) {
        private val lock = Any()
        private val steps = mutableListOf<SubagentStepSnapshot>()
        private var totalStepCount = 0
        private var totalTools = 0
        private var status = SubagentActivitySnapshot.STATUS_RUNNING
        private var currentAction = "等待模型响应"
        private val roundTexts = linkedMapOf<Int, StringBuilder>()
        private var finalOutput = ""
        private var retainedTruncated = false
        private var errorText: String? = null

        fun onChildEvent(event: AgentEvent): Boolean = synchronized(lock) {
            when (event) {
                is AgentEvent.RoundStarted -> {
                    currentAction = "等待模型响应（第 ${event.round} 轮）"
                    true
                }

                is AgentEvent.ModelRetryScheduled -> {
                    // 失败尝试只留作步骤记录，不拼进后续的部分汇报。
                    roundTexts.remove(event.round)
                    finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
                    currentAction = event.displayMessage
                    true
                }

                is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (current.detail + event.delta).takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        } else {
                            totalStepCount++
                            if (steps.size >= MAX_STEPS) steps.removeAt(0)
                            steps += SubagentStepSnapshot(
                                id = stepId,
                                kind = SubagentStepSnapshot.KIND_THINKING,
                                title = "思考",
                                detail = event.delta.takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        }
                        currentAction = "正在思考…"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        val text = roundTexts.getOrPut(event.round) { StringBuilder() }
                        text.append(event.delta)
                        if (text.length > MAX_OUTPUT_CHARS) {
                            retainedTruncated = true
                            text.delete(0, text.length - MAX_OUTPUT_CHARS)
                        }
                        while (roundTexts.size > 4) roundTexts.remove(roundTexts.keys.first())
                        currentAction = "正在接收模型正文；尚未作为阶段结论汇报"
                        false
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.AssistantBlockEnd -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.THINKING -> {
                        val stepId = "thinking-${event.round}-${event.index}"
                        val existingIndex = steps.indexOfFirst { it.id == stepId }
                        if (existingIndex >= 0) {
                            val current = steps[existingIndex]
                            steps[existingIndex] = current.copy(
                                detail = (event.replacementContent ?: current.detail)
                                    .trim()
                                    .takeLast(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_SUCCESS,
                            )
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        if (event.replacementContent != null) {
                            roundTexts[event.round] = StringBuilder(event.replacementContent)
                        }
                        true
                    }

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> false
                }

                is AgentEvent.ToolStarted -> {
                    finishRunningThinkingSteps()
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val title = event.argsPreview.ifBlank { event.name }
                    val detail = event.command?.trim().orEmpty().take(MAX_STEP_DETAIL_CHARS)
                    if (steps.none { it.id == stepId }) {
                        totalTools++
                        totalStepCount++
                        if (steps.size >= MAX_STEPS) steps.removeAt(0)
                        steps += SubagentStepSnapshot(
                            id = stepId,
                            kind = SubagentStepSnapshot.KIND_TOOL,
                            title = title,
                            detail = detail,
                            status = SubagentStepSnapshot.STATUS_RUNNING,
                            toolName = event.name,
                        )
                    }
                    currentAction = title
                    true
                }

                is AgentEvent.ToolFinished -> {
                    val stepId = "tool-${event.round}-${event.toolCallId}"
                    val existingIndex = steps.indexOfLast { it.id == stepId }
                    val stepStatus = if (event.success == false) {
                        SubagentStepSnapshot.STATUS_FAILED
                    } else {
                        SubagentStepSnapshot.STATUS_SUCCESS
                    }
                    if (existingIndex >= 0) {
                        val current = steps[existingIndex]
                        val combinedDetail = listOfNotNull(
                            current.detail.takeIf { it.isNotBlank() },
                            event.resultSummary.trim().takeIf { it.isNotBlank() },
                        ).joinToString("\n").take(MAX_STEP_DETAIL_CHARS)
                        steps[existingIndex] = current.copy(
                            status = stepStatus,
                            detail = combinedDetail,
                        )
                    }
                    currentAction = "正在分析结果…"
                    true
                }

                else -> false
            }
        }

        fun controlState(value: String, reason: String) = synchronized(lock) {
            status = value
            if (value !in setOf("running", "completed", "failed", "cancelled")) currentAction = when (value) {
                "paused" -> "已在安全边界暂停：$reason"
                "waiting_decision" -> "等待主代理决定"
                "pausing" -> "等待当前请求/完整工具批次结束后暂停"
                "cancelling" -> "正在取消；已执行操作不会撤销"
                "interrupted" -> "任务已中断"
                else -> value
            }
        }

        fun markCompleted(content: String) = synchronized(lock) {
            finishRunningThinkingSteps()
            status = SubagentActivitySnapshot.STATUS_COMPLETED
            retainedTruncated = retainedTruncated || content.trim().length > MAX_OUTPUT_CHARS
            finalOutput = content.trim().take(MAX_OUTPUT_CHARS)
            val tools = toolCount()
            currentAction = if (tools > 0) "完成 · $tools 次工具调用" else "完成"
        }

        fun markFailed(error: String, partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_FAILED
            errorText = error.take(1_000)
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = error.lineSequence().firstOrNull()?.take(80) ?: "执行失败"
        }

        fun markCancelled(partial: String) = synchronized(lock) {
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_CANCELLED
            errorText = "已停止"
            if (partial.isNotBlank()) finalOutput = partial.take(MAX_OUTPUT_CHARS)
            currentAction = "已停止"
        }

        fun partialOutput(): String = synchronized(lock) {
            if (finalOutput.isNotBlank()) return finalOutput
            roundTexts.values
                .map { it.toString().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
                .takeLast(MAX_OUTPUT_CHARS)
        }

        fun toolCount(): Int = synchronized(lock) { totalTools }

        fun snapshot(): SubagentActivitySnapshot = synchronized(lock) {
            val liveOutput = if (finalOutput.isNotBlank()) {
                finalOutput
            } else {
                roundTexts.values.lastOrNull()?.toString()?.trim().orEmpty().takeLast(MAX_OUTPUT_CHARS)
            }
            SubagentActivitySnapshot(
                subagentId = subagentId,
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                model = model,
                resumed = resumed,
                status = status,
                currentAction = currentAction,
                steps = steps.takeLast(MAX_STEPS),
                totalSteps = totalStepCount,
                previewTruncated = retainedTruncated || totalStepCount > MAX_STEPS,
                output = liveOutput,
                error = errorText,
            )
        }

        private fun finishRunningThinkingSteps() {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.kind == SubagentStepSnapshot.KIND_THINKING &&
                    step.status == SubagentStepSnapshot.STATUS_RUNNING
                ) {
                    steps[index] = step.copy(status = SubagentStepSnapshot.STATUS_SUCCESS)
                }
            }
        }

        private fun finishAllRunningSteps(terminalStatus: String) {
            for (index in steps.indices) {
                val step = steps[index]
                if (step.status == SubagentStepSnapshot.STATUS_RUNNING) {
                    steps[index] = step.copy(status = terminalStatus)
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_THROTTLE_NANOS = 500_000_000L
        private const val MAX_STEP_DETAIL_CHARS = 2_000
        private const val MAX_OUTPUT_CHARS = 128_000
        private const val MAX_STEPS = 256
    }
}
