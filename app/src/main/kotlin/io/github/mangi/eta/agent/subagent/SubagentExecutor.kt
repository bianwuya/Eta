package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentCompactionTarget
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.local.subagentdisplay.SubagentReadableText
import io.github.mangi.eta.local.subagentcontext.SubagentTaskHandoff
import io.github.mangi.eta.local.subagentcontext.AgentRunRole
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import io.github.mangi.eta.local.subagentcontext.SubagentMailbox
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行单次 `subagent` 工具调用：管理独立子上下文、过滤工具目录、实时镜像内部步骤，并向父 Agent 返回结构化摘要。
 *
 * 子任务本体仍同步执行；调用方在启动回调后持有工作线程，并在安全边界接收最终结果。
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
    private val compactionTarget: ((AgentModelClient.ModelConfig) -> AgentCompactionTarget)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
    private val childToolFactory: ((AgentRunController) -> AgentModelClient.ToolExecutor)? = null,
    private val detailStoreFactory: (() -> SubagentDetailStore)? = null,
    private val controlRegistry: SubagentControlRegistry? = null,
    private val mailboxStorage: SubagentMailbox.Storage? = null,
    private val decisionBroker: SubagentDecisionBroker? = null,
    private val mailboxToolGate: ((AgentModelClient.ToolCall, () -> AgentModelClient.ToolResult) -> AgentModelClient.ToolResult)? = null,
    private val providerFactory: (AgentModelClient.ModelConfig) -> io.github.mangi.eta.agent.model.AgentProviderClient =
        io.github.mangi.eta.agent.model.ProviderClientFactory::getClient,
) {
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun latestSnapshotJson(toolCallId: String): String? = latestSnapshots[toolCallId]

    fun execute(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
        mailboxInvocation: SubagentMailbox.Invocation? = null,
        decisionTaskId: String? = null,
        onStarted: (String, String) -> Unit = { _, _ -> },
    ): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return failureResult(toolCall, "INVALID_ARGUMENTS", "subagent 参数必须是合法的 JSON 对象")
        if (args.has("group_key")) return failureResult(toolCall, "SUBAGENT_GROUP_UNAVAILABLE",
            "当前调用没有持久化父运行 owner；未启动分组任务。请去除 group_key 使用原同步路径。")
        if (mailboxInvocation != null && (mailboxStorage == null || parentRunId.isBlank() ||
                mailboxInvocation.scope.parentRunId != parentRunId))
            return failureResult(toolCall, "MAILBOX_UNAUTHORIZED", "Mailbox requires a trusted durable parent owner")

        val prompt = args.optString("prompt").trim()
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .take(120)
        if (prompt.isEmpty()) {
            return failureResult(toolCall, "INVALID_ARGUMENTS", "prompt 不能为空")
        }
        if (description.isEmpty()) {
            return failureResult(toolCall, "INVALID_ARGUMENTS", "description 不能为空")
        }

        // Validate the complete task before creating an entry or claiming a resumed one.
        // A rejected handoff must not launch a partial task or leave a session running.
        val taskPrompt = try {
            SubagentTaskHandoff.taskPrompt(prompt, args.opt("handoff"))
        } catch (failure: IllegalArgumentException) {
            return failureResult(toolCall, "INVALID_HANDOFF", failure.message ?: "handoff 参数无效")
        }

        val resumeId = args.optString("resume").trim()
        val requestedType = args.optString("subagent_type").trim().lowercase()
        val isResume = resumeId.isNotEmpty()

        if (isResume && requestedType.isNotEmpty()) {
            return failureResult(
                toolCall,
                "RESUME_WITH_TYPE_UNAVAILABLE",
                "resume 续接已有 Subagent 时不得同时指定 subagent_type；请仅传递 resume 与 prompt。",
            )
        }

        val entry: SubagentSessionStore.Entry
        val priorHistory: List<AgentModelClient.ConversationMessage>
        if (isResume) {
            val existing = SubagentSessionStore.get(conversationKey, resumeId)
                ?: return failureResult(
                    toolCall,
                    "SUBAGENT_NOT_FOUND",
                    "未找到可续接的 Subagent：$resumeId（可能已过期或不属于当前会话）",
                )
            if (existing.explicitlyCancelled.get()) {
                return failureResult(
                    toolCall,
                    "SUBAGENT_CANCELLED_FINAL",
                    "Subagent $resumeId 已被主代理明确取消，不能继续旧任务；请根据最新要求重新创建子代理。",
                )
            }
            if (!existing.running.compareAndSet(false, true)) {
                return failureResult(
                    toolCall,
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
            runChild(
                parentRound = parentRound,
                toolCall = toolCall,
                entry = entry,
                priorHistory = priorHistory,
                prompt = prompt,
                taskPrompt = taskPrompt,
                description = description,
                isResume = isResume,
                mailboxInvocation = mailboxInvocation,
                decisionTaskId = decisionTaskId ?: mailboxInvocation?.taskId,
                onStarted = onStarted,
            )
        } catch (failure: Exception) {
            SubagentSessionStore.releaseRunning(conversationKey, entry.subagentId)
            if (failure is AgentRunCancelledException || failure is java.util.concurrent.CancellationException) throw failure
            if (parentController.isCancelled) throw AgentRunCancelledException()
            failureResult(toolCall, "SUBAGENT_START_FAILED", failure.message ?: "启动失败")
        }
    }

    private fun runChild(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
        entry: SubagentSessionStore.Entry,
        priorHistory: List<AgentModelClient.ConversationMessage>,
        prompt: String,
        taskPrompt: String,
        description: String,
        isResume: Boolean,
        mailboxInvocation: SubagentMailbox.Invocation?,
        decisionTaskId: String?,
        onStarted: (String, String) -> Unit,
    ): AgentModelClient.ToolResult {
        val profile = entry.profile
        val subagentId = entry.subagentId
        val childController = AgentRunController()
        val controllerBinding = parentController.registerChild(childController)
        val controlBinding = controlRegistry?.register(
            subagentId, description, toolCall.id, childController,
            onCancel = { entry.explicitlyCancelled.set(true) },
        )
        var ownedTools: AgentModelClient.ToolExecutor? = null
        var toolBinding: AgentRunController.ResourceBinding? = null
        var mailboxBinding: AgentRunController.ResourceBinding? = null
        var mailboxIdentity: SubagentMailbox.Identity? = null
        val mailboxActive = java.util.concurrent.atomic.AtomicBoolean(true)
        var mailboxCloseAttempted = false
        var mailboxCloseFailure: Exception? = null
        fun closeMailbox() {
            mailboxActive.set(false)
            if (mailboxCloseAttempted) return
            mailboxCloseAttempted = true
            mailboxBinding?.close()
            try {
                mailboxIdentity?.let { checkNotNull(mailboxStorage).close(it) }
            } catch (failure: Exception) {
                mailboxCloseFailure = failure
            }
        }
        fun withMailboxCleanup(result: AgentModelClient.ToolResult): AgentModelClient.ToolResult {
            closeMailbox()
            val failure = mailboxCloseFailure ?: return result
            return result.copy(content = JSONObject(result.content).put("cleanup_failure", JSONObject()
                .put("code", "MAILBOX_CLOSE_FAILED").put("exception_type", failure.javaClass.simpleName)
                .put("message", "Mailbox close persistence failed; original execution outcome is preserved. Do not replay completed work.")
                .put("outcome_preserved", true)).toString())
        }
        var detailStoreForCleanup: SubagentDetailStore? = null
        try {
        val mailbox = mailboxInvocation?.let { invocation ->
            val identity = SubagentMailbox.Identity(invocation, subagentId)
            val storage = checkNotNull(mailboxStorage)
            storage.bind(identity, profile.isToolAllowed(SubagentMailbox.TOOL_NAME)) {
                mailboxActive.get() && !entry.explicitlyCancelled.get() && !childController.isCancelled && !parentController.isCancelled
            }
            mailboxIdentity = identity
            mailboxBinding = childController.register { storage.close(identity) }
            // The ordinary executor already observes pause. Do not block on pause while holding a DB transaction.
            SubagentMailbox(identity, storage) {
                if (childController.isCancelled || parentController.isCancelled) throw AgentRunCancelledException()
            }
        }
        val resolvedConfig = runCatching { configProvider() }.getOrElse { throwable ->
            mailboxActive.set(false)
            if (throwable is AgentRunCancelledException || throwable is java.util.concurrent.CancellationException) throw throwable
            if (parentController.isCancelled || childController.isCancelled) throw AgentRunCancelledException()
            val previousCompactions = entry.compactionCount.get()
            SubagentSessionStore.releaseRunning(conversationKey, subagentId)
            val message = throwable.message ?: "无法加载 Subagent 模型配置"
            // 启动失败必须是真实失败状态，不能停留在合成卡片初始的“正在启动”。
            val failed = SubagentActivitySnapshot.failureFromArguments(toolCall.argumentsJson, message)
                .copy(subagentId = subagentId, compactionCount = previousCompactions)
            latestSnapshots[toolCall.id] = failed.encode()
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = failed.encode(),
                ),
            )
            return withMailboxCleanup(AgentModelClient.ToolResult(
                content = SubagentTaskResult.runtimeFailure(message).attach(JSONObject()
                    .put("ok", false)
                    .put("code", "SUBAGENT_CONFIG_ERROR")
                    .put("status", SubagentActivitySnapshot.STATUS_FAILED)
                    .put("message", message)
                    ).toString(),
                command = latestSnapshots[toolCall.id],
            ))
        }
        val modelLabel = resolvedConfig.modelDisplayName.ifBlank { resolvedConfig.model }
        val tracker = StepTracker(
            subagentId = subagentId,
            subagentType = profile.name,
            description = description,
            prompt = prompt,
            model = modelLabel,
            resumed = isResume,
            contextWindow = resolvedConfig.knownContextWindow,
            successfulCompactions = entry.compactionCount,
        )

        val detailStore = detailStoreFactory?.invoke().also { detailStoreForCleanup = it }
        val detailId = toolCall.id
        var lastEmitNanos = 0L
        var snapshotSequence = 0L
        val publicationLock = Any()
        fun publishSnapshot(force: Boolean): Unit = synchronized(publicationLock) {
            val now = nanoTime()
            if (!force && now - lastEmitNanos < PROGRESS_THROTTLE_NANOS) return
            lastEmitNanos = now
            val source = tracker.snapshot()
            snapshotSequence = maxOf(snapshotSequence + 1, System.currentTimeMillis())
            val full = source.copy(
                detailId = detailId,
                updatedAt = snapshotSequence,
                prompt = SubagentDisplayText.redact(source.prompt),
                output = SubagentDisplayText.redact(source.output),
                error = source.error?.let(SubagentDisplayText::redact),
            )
            if (force) {
                runCatching { detailStore?.save(detailId, full) }
                    .onFailure {
                        io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled("subagent_detail_write_failed") {
                            "Subagent detail storage failed: type=${it.javaClass.simpleName}"
                        }
                    }
            }
            val encoded = full.copy(
                prompt = full.prompt.take(512),
                output = full.output.takeLast(512),
                steps = full.steps.takeLast(12).map { step ->
                    step.copy(
                        detail = step.detail.takeLast(200),
                        detailTruncated = step.detailTruncated || step.detail.length > 200,
                    )
                },
                previewTruncated = true,
            ).encodeBounded(12_000)
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

        publishSnapshot(force = true)

        ownedTools = childToolFactory?.invoke(childController)
        val tools = ownedTools ?: baseToolExecutor
        toolBinding = (ownedTools as? AutoCloseable)?.let { childController.register { it.close() } }

        val cancelledByDecision = java.util.concurrent.atomic.AtomicBoolean(false)
        val childToolExecutor = AgentModelClient.ToolExecutor { subCall ->
            childController.throwIfCancelled()
            if (cancelledByDecision.get()) {
                AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "ASK_PARENT_CANCELLED")
                    .put("message", "Parent cancelled this work; all later tool calls are blocked. Report actual outcomes.").toString())
            } else if (childController.hasPendingParentDirective) {
                // The parent has corrected the direction while the child was thinking.
                // Reject stale tools already emitted by the model; inject the correction at
                // the next complete turn instead of applying stale side effects.
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "SUBAGENT_REPLAN_REQUIRED")
                        .put("message", "主代理有新的任务修正等待处理，旧工具调用未执行；请先读取新指令再重新规划。")
                        .toString(),
                )
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
                } else {
                    SubagentDisplayText.redact(subCall.argumentsJson)
                }
                runCatching { detailStore?.capture(detailId, stepId, "${subCall.name}\n$safeArguments\n[执行中]") }
                val result = if (subCall.name == SubagentMailbox.TOOL_NAME) {
                    val readOrStore = {
                        mailbox?.execute(subCall) ?: AgentModelClient.ToolResult(JSONObject().put("ok", false)
                            .put("code", "MAILBOX_UNAVAILABLE").put("message", "No explicit runtime group; mailbox is unavailable").toString())
                    }
                    mailboxToolGate?.invoke(subCall, readOrStore) ?: readOrStore()
                } else if (subCall.name == SubagentDecisionBroker.ASK) {
                    val invoke = {
                        if (decisionBroker == null || decisionTaskId == null) AgentModelClient.ToolResult(JSONObject()
                            .put("ok", false).put("code", "ASK_PARENT_UNAVAILABLE").toString())
                        else decisionBroker.ask(decisionTaskId, subagentId, subCall, childController)
                    }
                    (mailboxToolGate?.invoke(subCall, invoke) ?: invoke()).also { result ->
                        if (runCatching { JSONObject(result.content).optString("status") }.getOrNull() == "cancel") {
                            cancelledByDecision.set(true)
                            childController.requestStopAfterToolBatch()
                        }
                    }
                } else tools.execute(subCall)
                result.also { result ->
                    val safeResult = if (result.sensitive || io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy.isSensitive(subCall.name)) {
                        "[敏感工具结果不保存]"
                    } else {
                        SubagentDisplayText.redact(result.content)
                    }
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
            childController.throwIfCancelled()
            AgentModelClient.validateConfig(subagentConfig)
            onStarted(subagentId, latestSnapshots[toolCall.id].orEmpty())
            var finalStopReason = io.github.mangi.eta.agent.model.AssistantStopReason.UNKNOWN
            val delegate = providerFactory(subagentConfig)
            val resultProvider = object : io.github.mangi.eta.agent.model.AgentProviderClient by delegate {
                override fun complete(
                    request: io.github.mangi.eta.agent.model.ProviderRequest,
                    runController: AgentRunController,
                    onEvent: (io.github.mangi.eta.agent.model.ProviderEvent) -> Unit,
                ): io.github.mangi.eta.agent.model.ProviderResponse =
                    delegate.complete(request, runController, onEvent).also {
                        if (request.purpose == io.github.mangi.eta.agent.model.ProviderRequestPurpose.CHAT) finalStopReason = it.stopReason
                    }
            }
            val response = AgentModelClient.complete(
                config = subagentConfig,
                runRole = AgentRunRole.SUBAGENT,
                provider = io.github.mangi.eta.local.subagentstats.SubagentRequestStats.provider(
                    delegate = resultProvider,
                    template = io.github.mangi.eta.local.subagentstats.SubagentRequestStat(
                        conversationId = conversationKey,
                        subagentId = subagentId,
                        invocationId = toolCall.id,
                        parentRunId = parentRunId,
                        profile = profile.name,
                        providerId = subagentConfig.providerId,
                        model = subagentConfig.model,
                        purpose = "CHAT",
                        ordinal = 0,
                        resumed = isResume,
                    ),
                    baseline = entry.requestStatsBaseline,
                    collectionEpoch = requestStatsEpoch,
                    save = onRequestStat,
                ),
                compactionTarget = compactionTarget?.let { resolve ->
                    { ->
                        val target = resolve(subagentConfig)
                        target.copy(
                            provider = io.github.mangi.eta.local.subagentstats.SubagentRequestStats.provider(
                                delegate = target.provider,
                                template = io.github.mangi.eta.local.subagentstats.SubagentRequestStat(
                                    conversationId = conversationKey,
                                    subagentId = subagentId,
                                    invocationId = toolCall.id,
                                    parentRunId = parentRunId,
                                    profile = profile.name,
                                    providerId = target.config.providerId,
                                    model = target.config.model,
                                    purpose = "COMPACTION",
                                    ordinal = 0,
                                    resumed = isResume,
                                ),
                                baseline = entry.requestStatsBaseline,
                                collectionEpoch = requestStatsEpoch,
                                save = onRequestStat,
                            ),
                        )
                    }
                },
                prompt = taskPrompt,
                toolExecutor = childToolExecutor,
                history = priorHistory,
                runController = childController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = profile.filterTools(JSONArray(mcpTools.toString()).also { extra ->
                    if (mailbox != null) extra.put(SubagentMailbox.schema())
                    if (decisionBroker != null && decisionTaskId != null) extra.put(SubagentDecisionBroker.askSchema())
                }),
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
            mailboxActive.set(false)
            // A child stopped while the model returned must not publish a successful completion.
            childController.throwIfCancelled()
            val updatedHistory = SubagentContinuation.history(
                priorHistory,
                taskPrompt,
                response.transcript,
                response.contextSnapshot,
            )
            SubagentSessionStore.complete(conversationKey, subagentId, updatedHistory)
            if (cancelledByDecision.get()) tracker.markCancelled(response.content)
            else tracker.markCompleted(response.content)
            publishSnapshot(force = true)
            childController.throwIfCancelled()
            val taskResult = if (cancelledByDecision.get()) SubagentTaskResult.interrupted(
                "主代理取消了该任务；请核实已完成工作和不可回滚副作用。\n" + response.content)
            else SubagentTaskResult.fromFinal(response.content,
                finalStopReason == io.github.mangi.eta.agent.model.AssistantStopReason.END_TURN)

            val result = AgentModelClient.ToolResult(
                content = taskResult.attach(JSONObject()
                    .put("ok", !cancelledByDecision.get())
                    .put("subagent_id", subagentId)
                    .put("subagent_type", profile.name)
                    .put("description", description)
                    .put("model", modelLabel)
                    .put("status", if (cancelledByDecision.get()) SubagentActivitySnapshot.STATUS_CANCELLED else SubagentActivitySnapshot.STATUS_COMPLETED)
                    .put("tool_calls", tracker.toolCount())
                    .put("summary", response.content)
                    .put(
                        "resume_hint",
                        "如需在此 Subagent 已有上下文基础上继续追问或深挖，可调用 subagent(resume=\"$subagentId\", prompt=\"...\", description=\"...\")。",
                    )
                    ).toString(),
                command = latestSnapshots[toolCall.id],
            )
            val finalized = withMailboxCleanup(result)
            childController.throwIfCancelled()
            controlRegistry?.finish(subagentId, toolCall.id, finalized) ?: finalized
        } catch (throwable: Throwable) {
            mailboxActive.set(false)
            val partial = (throwable as? AgentModelExecutionException)?.let { ex ->
                val partialHistory = SubagentContinuation.history(
                    priorHistory,
                    taskPrompt,
                    ex.transcript,
                    ex.contextSnapshot,
                )
                SubagentSessionStore.complete(conversationKey, subagentId, partialHistory)
                tracker.partialOutput()
            } ?: run {
                SubagentSessionStore.releaseRunning(conversationKey, subagentId)
                tracker.partialOutput()
            }

            val cancellationCause = (throwable as? AgentModelExecutionException)?.cause
            val cancelled = parentController.isCancelled ||
                childController.isCancelled ||
                throwable is AgentRunCancelledException || throwable is java.util.concurrent.CancellationException ||
                cancellationCause is AgentRunCancelledException || cancellationCause is java.util.concurrent.CancellationException
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
            if (cancelled) {
                // Display failures must not turn cancellation into a startup error.
                runCatching { publishSnapshot(force = true) }
                throw AgentRunCancelledException()
            }
            publishSnapshot(force = true)

            val result = AgentModelClient.ToolResult(
                content = SubagentTaskResult.runtimeFailure(errorMessage).attach(JSONObject()
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
                    ).toString(),
                command = latestSnapshots[toolCall.id],
            )
            try {
                val finalized = withMailboxCleanup(result)
                childController.throwIfCancelled()
                controlRegistry?.finish(subagentId, toolCall.id, finalized) ?: finalized
            } catch (cancelledAtPublication: AgentRunCancelledException) {
                tracker.markCancelled(partial)
                runCatching { publishSnapshot(force = true) }
                throw cancelledAtPublication
            }
        }
        } finally {
            try {
                // Fallback for startup/cancellation exits. Never replace an already selected outcome.
                closeMailbox()
            } finally {
                runCatching { toolBinding?.close() }
                (ownedTools as? AutoCloseable)?.let { runCatching { it.close() } }
                runCatching { kotlinx.coroutines.runBlocking { detailStoreForCleanup?.prune() } }
                runCatching { controlBinding?.close() }
                runCatching { controllerBinding.close() }
            }
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

    /**
     * 启动/续接失败携带真实 failed 快照，时间线卡片与详情不会停留在合成的“正在启动”预览。
     */
    private fun failureResult(
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        content = SubagentTaskResult.runtimeFailure(message).attach(JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("status", SubagentActivitySnapshot.STATUS_FAILED)
            .put("message", message)
            ).toString(),
        command = SubagentActivitySnapshot.failureFromArguments(toolCall.argumentsJson, message).encode()
            .also { latestSnapshots[toolCall.id] = it },
    )

    internal class StepTracker(
        private val subagentId: String,
        private val subagentType: String,
        private val description: String,
        private val prompt: String,
        private val model: String,
        private val resumed: Boolean,
        private val contextWindow: Int? = null,
        private val successfulCompactions: AtomicInteger = AtomicInteger(0),
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
        // Usage belongs to this invocation: resume may select a different model or append a prompt.
        private var contextTokens: Int? = null
        private var compactionCount = successfulCompactions.get()
        private var lastCompletedCompactionId: String? = null

        fun onChildEvent(event: AgentEvent): Boolean = synchronized(lock) {
            when (event) {
                is AgentEvent.RoundStarted -> {
                    currentAction = "等待模型响应（第 ${event.round} 轮）"
                    true
                }

                is AgentEvent.ModelRetryScheduled -> {
                    // 失败尝试只留作步骤记录，不拼进后续的部分汇报。
                    roundTexts.remove(event.round)
                    // The retry discarded that attempt; await fresh usage rather than keep it.
                    contextTokens = null
                    finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
                    currentAction = event.displayMessage
                    true
                }

                is AgentEvent.UsageReceived -> {
                    val reported = event.usage.contextTokens?.takeIf { it >= 0 }
                    val changed = contextTokens != reported
                    contextTokens = reported
                    changed
                }

                is AgentEvent.ContextCompaction -> {
                    if (event.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED &&
                        lastCompletedCompactionId != event.operationId
                    ) {
                        lastCompletedCompactionId = event.operationId
                        compactionCount = successfulCompactions.incrementAndGet()
                        // Only a supplied post-compaction value is current; never retain old usage.
                        contextTokens = event.tokensAfter?.takeIf { it >= 0 }
                        true
                    } else {
                        false
                    }
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
                    val title = SubagentReadableText.toolTitle(event.name, event.argsPreview, event.command)
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
                contextTokens = contextTokens,
                contextWindow = contextWindow,
                compactionCount = compactionCount,
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
