package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentCompactionTarget
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.local.subagentdisplay.SubagentReadableText
import io.github.mangi.eta.local.subagentcontext.SubagentTaskHandoff
import io.github.mangi.eta.local.subagentcontext.AgentRunRole
import io.github.mangi.eta.local.subagentcontext.SubagentTaskResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.github.mangi.eta.core.AppStrings
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
    private val providerFactory: (AgentModelClient.ModelConfig) -> io.github.mangi.eta.agent.model.AgentProviderClient =
        io.github.mangi.eta.agent.model.ProviderClientFactory::getClient,
    private val candidateConfigs: (() -> List<AgentModelClient.ModelConfig>)? = null,
) {
    private val latestSnapshots = ConcurrentHashMap<String, String>()

    fun latestSnapshotJson(toolCallId: String): String? = latestSnapshots[toolCallId]

    fun execute(
        parentRound: Int,
        toolCall: AgentModelClient.ToolCall,
        onStarted: (String, String) -> Unit = { _, _ -> },
    ): AgentModelClient.ToolResult {
        parentController.throwIfCancelled()
        val args = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return failureResult(toolCall, "INVALID_ARGUMENTS", "subagent 参数必须是合法的 JSON 对象")
        if (args.has("group_key")) return failureResult(toolCall, "SUBAGENT_GROUP_UNAVAILABLE", "分组调度协议已退出；本调用未执行")
        val prompt = args.optString("prompt").trim()
        val description = args.optString("description").trim()
            .replace(Regex("\\s+"), " ")
            .takeUtf16Safe(120)
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
            // A prior invocation can stop between the model's tool-call declaration
            // and recording every result. Never resend that incomplete batch to
            // Chat Completions, and never execute an unknown tool again as a repair.
            // The existing recovery utility preserves completed results and marks
            // only unresolved calls as TOOL_INTERRUPTED.
            priorHistory = AgentToolBatchRecovery.completeInterrupted(existing.history)
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
                onStarted = onStarted,
            )
        } catch (failure: Exception) {
            if (failure is AgentRunCancelledException || failure is java.util.concurrent.CancellationException) throw failure
            if (parentController.isCancelled) throw AgentRunCancelledException()
            failureResult(toolCall, "SUBAGENT_START_FAILED", failure.message ?: "启动失败")
        } finally {
            // This invocation exclusively owns the claim until all callbacks and cleanup finish.
            SubagentSessionStore.releaseRunning(conversationKey, entry.subagentId)
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
        onStarted: (String, String) -> Unit,
    ): AgentModelClient.ToolResult {
        val profile = entry.profile
        val subagentId = entry.subagentId
        val childController = AgentRunController()
        val controllerBinding = parentController.registerChild(childController)
        val controlBinding = controlRegistry?.register(
            subagentId, description, toolCall.id, childController,
            onCancel = { entry.explicitlyCancelled.set(true); SubagentSessionStore.persist(conversationKey, entry) },
        )
        var ownedTools: AgentModelClient.ToolExecutor? = null
        var toolBinding: AgentRunController.ResourceBinding? = null
        var detailStoreForCleanup: SubagentDetailStore? = null
        var historyRecorderForFailure: SubagentHistoryRecorder? = null
        try {
        val prepared = runCatching {
            val orderedConfigs = candidateConfigs?.invoke() ?: listOf(configProvider())
            require(orderedConfigs.isNotEmpty()) { "没有可用的子代理模型配置" }
            val primaryCandidate = orderedConfigs.first()
            val accountBinding = if (io.github.mangi.eta.data.model.ProviderAuthModes.isOAuth(primaryCandidate.authMode))
                io.github.mangi.eta.data.repository.ProviderCredentialRepository.binding(primaryCandidate.providerId, primaryCandidate.authMode)
            else null
            val accountSlot = accountBinding?.credentialSlotId.orEmpty()
            // Resume carries the original transcript even after successful completion.
            // Reordering candidates must never send that history to another model/account.
            val boundIdentity = entry.modelIdentity
            val resolvedCandidates = if (boundIdentity != null) {
                val bound = orderedConfigs.firstOrNull { SubagentSessionStore.modelIdentity(it, accountSlot) == boundIdentity }
                    ?: throw IllegalStateException("原子代理模型或账号不可用；未切换账号或重跑旧任务")
                listOf(bound)
            } else {
                val minimumContext = if (orderedConfigs.size > 1) {
                    taskPrompt.length + priorHistory.sumOf { it.content.length + it.contentJson.length }
                } else {
                    0
                }
                SubagentModelPolicy.eligible(
                    orderedConfigs.map { SubagentModelPolicy.Candidate(it) },
                    minimumContext,
                ).also { require(it.isNotEmpty()) { "没有可用的子代理候选模型" } }
            }
            Triple(accountBinding, accountSlot, resolvedCandidates)
        }.getOrElse { throwable ->
            if (throwable is AgentRunCancelledException || throwable is java.util.concurrent.CancellationException) throw throwable
            if (parentController.isCancelled || childController.isCancelled) throw AgentRunCancelledException()
            val previousCompactions = entry.compactionCount.get()
            val previousReading = entry.lastContext?.reading?.copy(previousInvocation = true, modelChanged = null)
            val message = throwable.message ?: "无法加载 Subagent 模型配置"
            // 启动失败必须是真实失败状态，不能停留在合成卡片初始的“正在启动”。
            val failedStore = runCatching { detailStoreFactory?.invoke() }.getOrNull()
                .also { detailStoreForCleanup = it }
            val failed = SubagentActivitySnapshot.failureFromArguments(toolCall.argumentsJson, message)
                .copy(subagentId = subagentId, compactionCount = previousCompactions, contextReading = previousReading,
                    detailId = if (failedStore != null) toolCall.id else "")
            runCatching {
                failedStore?.save(toolCall.id, failed)
                failedStore?.let { SubagentHistoryRecorder(it, toolCall.id).finish("failed", message) }
            }.onFailure {
                io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled("subagent_history_write_failed") {
                    "Subagent history storage failed: type=${it.javaClass.simpleName}"
                }
            }
            val failedWire = failed.encodeForWire()
            latestSnapshots[toolCall.id] = failedWire
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = failedWire,
                ),
            )
            return (AgentModelClient.ToolResult(
                content = SubagentTaskResult.runtimeFailure(message).attach(JSONObject()
                    .put("ok", false)
                    .put("code", "SUBAGENT_CONFIG_ERROR")
                    .put("status", SubagentActivitySnapshot.STATUS_FAILED)
                    .put("message", message)
                    ).toString(),
                command = latestSnapshots[toolCall.id],
            ))
        }
        val (accountBinding, accountSlot, runCandidates) = prepared
        val initialConfig = runCandidates.first()
        entry.completed = false
        var modelLabel = initialConfig.modelDisplayName.ifBlank { initialConfig.model }
        val tracker = StepTracker(
            subagentId = subagentId,
            subagentType = profile.name,
            description = description,
            prompt = prompt.truncateWithEllipsis(MAX_SNAPSHOT_PROMPT_CHARS),
            model = modelLabel,
            resumed = isResume,
            contextWindow = initialConfig.knownContextWindow,
            successfulCompactions = entry.compactionCount,
            previousContext = if (isResume) entry.lastContext else null,
            modelId = initialConfig.model,
            providerId = initialConfig.providerId.ifBlank { initialConfig.baseUrl }, // In-memory comparison only.
            onContextChanged = { entry.lastContext = it },
        )

        val detailStore = detailStoreFactory?.invoke().also { detailStoreForCleanup = it }
        val detailId = toolCall.id
        val historyRecorder = detailStore?.let { SubagentHistoryRecorder(it, detailId) }
            .also { historyRecorderForFailure = it }
        var historyIncomplete = false
        fun recordHistory(block: SubagentHistoryRecorder.() -> Unit) {
            if (historyRecorder == null) return
            runCatching { historyRecorder.block() }.onFailure {
                historyIncomplete = true
                io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled("subagent_history_write_failed") {
                    "Subagent history storage failed: type=${it.javaClass.simpleName}"
                }
            }
        }
        var lastEmitNanos = 0L
        var lastEmittedJson: String? = null
        var lastEmittedContent: SubagentActivitySnapshot? = null
        var snapshotSequence = 0L
        val publicationLock = Any()
        fun publishSnapshot(force: Boolean): String = synchronized(publicationLock) {
            val now = nanoTime()
            if (!force && lastEmittedJson != null && now - lastEmitNanos < PROGRESS_THROTTLE_NANOS) {
                return@synchronized lastEmittedJson.orEmpty()
            }
            recordHistory { checkpoint() }
            val source = tracker.snapshot()
            val content = source.copy(
                detailId = detailId,
                historyIncomplete = historyIncomplete,
                updatedAt = 0L,
                prompt = SubagentDisplayText.redact(source.prompt),
                output = SubagentDisplayText.redact(source.output),
                error = source.error?.let(SubagentDisplayText::redact),
            )
            if (!force && content == lastEmittedContent) {
                return@synchronized lastEmittedJson.orEmpty()
            }
            snapshotSequence = maxOf(snapshotSequence + 1, System.currentTimeMillis())
            val full = content.copy(updatedAt = snapshotSequence)
            if (force) {
                runCatching { detailStore?.save(detailId, full) }
                    .onFailure {
                        io.github.mangi.eta.core.AndroidAgentLogger.warnThrottled("subagent_detail_write_failed") {
                            "Subagent detail storage failed: type=${it.javaClass.simpleName}"
                        }
                    }
            }
            val encoded = full.encodeForWire()
            lastEmitNanos = now
            latestSnapshots[toolCall.id] = encoded
            lastEmittedContent = content
            lastEmittedJson = encoded
            onProgress(
                AgentEvent.SubagentProgress(
                    round = parentRound,
                    toolCallId = toolCall.id,
                    subagentId = subagentId,
                    subagentType = profile.name,
                    snapshotJson = encoded,
                ),
            )
            encoded
        }

        publishSnapshot(force = true)

        ownedTools = childToolFactory?.invoke(childController)
        val tools = ownedTools ?: baseToolExecutor
        toolBinding = (ownedTools as? AutoCloseable)?.let { childController.register { it.close() } }

        val childToolExecutor = AgentModelClient.ToolExecutor { subCall ->
            childController.throwIfCancelled()
            if (childController.hasPendingParentDirective) {
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
                val result = tools.execute(subCall)
                result.also { result ->
                    recordHistory { onToolResult(subCall, result) }
                    val safeResult = if (result.sensitive || io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy.isSensitive(subCall.name)) {
                        "[敏感工具结果不保存]"
                    } else {
                        SubagentDisplayText.redact(result.content)
                    }
                    runCatching { detailStore?.capture(detailId, stepId, "${subCall.name}\n$safeArguments\n$safeResult") }
                }
            }
        }

        val subagentConfig = initialConfig.copy(
            systemPrompt = profile.buildSystemPrompt(),
            subagentTools = false,
            supportsSearchSuggestions = false,
        )
        val activeSubagentConfig = AtomicReference(subagentConfig)

        var historyCommittedOnSuccess = false
        return try {
            childController.throwIfCancelled()
            AgentModelClient.validateConfig(subagentConfig)
            onStarted(subagentId, latestSnapshots[toolCall.id].orEmpty())
            var finalStopReason = io.github.mangi.eta.agent.model.AssistantStopReason.UNKNOWN
            val delegate = SubagentModelPolicy.provider(runCandidates.map { candidate ->
                candidate.copy(systemPrompt = profile.buildSystemPrompt(), subagentTools = false, supportsSearchSuggestions = false)
            }, { selected ->
                io.github.mangi.eta.local.subagentstats.SubagentRequestStats.provider(
                    delegate = providerFactory(selected),
                    template = io.github.mangi.eta.local.subagentstats.SubagentRequestStat(
                        conversationId = conversationKey, subagentId = subagentId, invocationId = toolCall.id,
                        parentRunId = parentRunId, profile = profile.name, providerId = selected.providerId,
                        model = selected.model, purpose = "CHAT", ordinal = 0, resumed = isResume),
                    baseline = entry.requestStatsBaseline, collectionEpoch = requestStatsEpoch, save = onRequestStat)
            }) { selected, reason ->
                activeSubagentConfig.set(selected)
                modelLabel = selected.modelDisplayName.ifBlank { selected.model }
                tracker.selectModel(selected, reason)
                entry.modelIdentity = SubagentSessionStore.modelIdentity(selected, accountSlot)
                SubagentSessionStore.persist(conversationKey, entry)
                publishSnapshot(force = true)
            }
            entry.modelIdentity = SubagentSessionStore.modelIdentity(initialConfig, accountSlot)
            SubagentSessionStore.persist(conversationKey, entry)
            val resultProvider = object : io.github.mangi.eta.agent.model.AgentProviderClient by delegate {
                override fun complete(
                    request: io.github.mangi.eta.agent.model.ProviderRequest,
                    runController: AgentRunController,
                    onEvent: (io.github.mangi.eta.agent.model.ProviderEvent) -> Unit,
                ): io.github.mangi.eta.agent.model.ProviderResponse =
                    delegate.complete(request.copy(credentialBinding = accountBinding), runController, onEvent).also {
                        if (request.purpose == io.github.mangi.eta.agent.model.ProviderRequestPurpose.CHAT) finalStopReason = it.stopReason
                    }
            }
            val response = AgentModelClient.complete(
                config = subagentConfig,
                runRole = AgentRunRole.SUBAGENT,
                provider = resultProvider,
                compactionTarget = compactionTarget?.let { resolve ->
                    { ->
                        val target = resolve(activeSubagentConfig.get())
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
                // A resumed child has no fresh usage until its first HTTP response. Reuse only
                // a completed reading from the same model/provider to trigger safe compaction
                // before a nearly full context is sent to the provider.
                initialContextTokens = if (isResume &&
                    entry.lastContext?.model == subagentConfig.model &&
                    entry.lastContext?.providerId == subagentConfig.providerId &&
                    entry.lastContext?.reading?.source == SubagentContextReading.SOURCE_USAGE &&
                    entry.lastContext?.reading?.attempt == SubagentContextReading.ATTEMPT_COMPLETED &&
                    entry.lastContext?.reading?.beforeCompaction == false
                ) entry.lastContext?.reading?.tokens else null,
                runController = childController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = profile.filterTools(mcpTools),
                executionContext = executionContext,
                capabilitiesProvider = capabilitiesProvider,
                sessionId = "$conversationKey-$subagentId",
                operationId = "${toolCall.id}-$subagentId",
                toolFilter = profile::filterTools,
                onTranscript = { transcript ->
                    entry.history = SubagentContinuation.history(priorHistory, taskPrompt, transcript, null,
                        "user-${toolCall.id}-$subagentId")
                    SubagentSessionStore.persist(conversationKey, entry)
                    recordHistory { onTranscript(transcript) }
                },
                onEvent = { childEvent ->
                    recordHistory { onEvent(childEvent) }
                    val force = tracker.onChildEvent(childEvent)
                    publishSnapshot(force = force)
                },
            )
            // A child stopped while the model returned must not publish a successful completion.
            // A durable answer can win before a simultaneous cancellation. Preserve the
            // completed real tool result in child history before honoring cancellation.
            val updatedHistory = SubagentContinuation.history(
                priorHistory,
                taskPrompt,
                response.transcript,
                response.contextSnapshot,
                "user-${toolCall.id}-$subagentId",
            )
            // Commit the real transcript before checking cancellation, while keeping the
            // running guard until terminal tracking and measurement publication finish.
            entry.history = updatedHistory
            entry.completed = true
            SubagentSessionStore.persist(conversationKey, entry)
            historyCommittedOnSuccess = true
            childController.throwIfCancelled()
            tracker.markCompleted(response.content)
            val terminalSnapshot = publishSnapshot(force = true)
            childController.throwIfCancelled()
            val taskResult = SubagentTaskResult.fromFinal(response.content,
                finalStopReason == io.github.mangi.eta.agent.model.AssistantStopReason.END_TURN)
            val result = AgentModelClient.ToolResult(
                content = taskResult.attach(JSONObject()
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
                    ).toString(),
                command = terminalSnapshot,
            )
            val finalized = result
            childController.throwIfCancelled()
            val selected = controlRegistry?.finish(subagentId, toolCall.id, finalized) ?: finalized
            recordHistory {
                finish("completed", "子任务已完成。", response.content)
            }
            selected
        } catch (throwable: Throwable) {
            val partialHistory = (throwable as? AgentModelExecutionException)?.let { ex ->
                SubagentContinuation.history(priorHistory, taskPrompt, ex.transcript, ex.contextSnapshot,
                    "user-${toolCall.id}-$subagentId")
            }
            // Keep the known transcript before failure publication can itself throw.
            if (partialHistory != null && !historyCommittedOnSuccess) {
                entry.history = partialHistory
                SubagentSessionStore.persist(conversationKey, entry)
            }
            val partial = tracker.partialOutput()

            val cancellationCause = (throwable as? AgentModelExecutionException)?.cause
            val cancelled = parentController.isCancelled ||
                childController.isCancelled ||
                throwable is AgentRunCancelledException || throwable is java.util.concurrent.CancellationException ||
                cancellationCause is AgentRunCancelledException || cancellationCause is java.util.concurrent.CancellationException
            val errorMessage = if (cancelled) {
                AppStrings.get(R.string.subagent_status_stopped, "已停止")
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                tracker.markCancelled(partial)
            } else {
                tracker.markFailed(errorMessage, partial)
            }
            val terminalSnapshot = if (cancelled) {
                recordHistory { finish("cancelled", errorMessage) }
                // Display failures must not turn cancellation into a startup error.
                runCatching { publishSnapshot(force = true) }
                    .getOrElse { latestSnapshots[toolCall.id].orEmpty() }
            } else {
                publishSnapshot(force = true)
            }

            if (parentController.isCancelled) {
                throw AgentRunCancelledException()
            }
            if (!cancelled) publishSnapshot(force = true)

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
                command = terminalSnapshot,
            )
            try {
                val finalized = result
                childController.throwIfCancelled()
                val selected = controlRegistry?.finish(subagentId, toolCall.id, finalized) ?: finalized
                recordHistory { finish(if (cancelled) "cancelled" else "failed", errorMessage) }
                selected
            } catch (cancelledAtPublication: AgentRunCancelledException) {
                tracker.markCancelled(partial)
                recordHistory { finish("cancelled", "已停止") }
                runCatching { publishSnapshot(force = true) }
                throw cancelledAtPublication
            }
        }
        } catch (failure: Throwable) {
            val cancelled = parentController.isCancelled || childController.isCancelled ||
                failure is AgentRunCancelledException || failure is java.util.concurrent.CancellationException
            runCatching {
                historyRecorderForFailure?.finish(if (cancelled) "cancelled" else "failed",
                    if (cancelled) "已停止" else failure.message ?: failure.javaClass.simpleName)
            }
            throw failure
        } finally {
            runCatching { toolBinding?.close() }
            (ownedTools as? AutoCloseable)?.let { runCatching { it.close() } }
            runCatching { kotlinx.coroutines.runBlocking { detailStoreForCleanup?.prune() } }
            runCatching { controlBinding?.close() }
            runCatching { controllerBinding.close() }
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
        command = SubagentActivitySnapshot.failureFromArguments(toolCall.argumentsJson, message).encodeForWire()
            .also { latestSnapshots[toolCall.id] = it },
    )

    internal class StepTracker(
        private val subagentId: String,
        private val subagentType: String,
        private val description: String,
        private val prompt: String,
        private var model: String,
        private val resumed: Boolean,
        private var contextWindow: Int? = null,
        private val successfulCompactions: AtomicInteger = AtomicInteger(0),
        previousContext: SubagentSessionStore.ContextState? = null,
        private var modelId: String = model,
        private var providerId: String = "",
        private val onContextChanged: (SubagentSessionStore.ContextState) -> Unit = {},
        private val now: () -> Long = System::currentTimeMillis,
    ) {
        private val lock = Any()
        private val steps = mutableListOf<SubagentStepSnapshot>()
        private var totalStepCount = 0
        private var totalToolSteps = 0
        private var status = SubagentActivitySnapshot.STATUS_RUNNING
        private var currentAction = AppStrings.get(R.string.subagent_i18n_action_thinking, "正在思考…")
        private val roundTexts = linkedMapOf<Int, StringBuilder>()
        private var finalOutput = ""
        private var retainedTruncated = false
        private var errorText: String? = null
        // Previous invocations are historical even when the model happens to be unchanged.
        private var contextState = previousContext?.let { previous ->
            previous.copy(reading = previous.reading.copy(
                previousInvocation = true,
                modelChanged = when {
                    previous.model.isBlank() || modelId.isBlank() -> null
                    previous.model != modelId -> true
                    previous.providerId.isBlank() || providerId.isBlank() -> null
                    else -> previous.providerId != providerId
                },
            ))
        }
        private var currentAttemptHasReading = false
        private var contextRequestState = SubagentActivitySnapshot.REQUEST_UNKNOWN
        private var compactionCount = successfulCompactions.get()
        private val completedCompactionIds = hashSetOf<String>()

        private fun setContextState(state: SubagentSessionStore.ContextState) {
            contextState = state
            onContextChanged(state)
        }

        private fun markCurrentAttempt(outcome: String) {
            val state = contextState ?: return
            if (!currentAttemptHasReading || state.reading.source != SubagentContextReading.SOURCE_USAGE) return
            setContextState(state.copy(reading = state.reading.copy(attempt = outcome)))
        }

        private fun closeCurrentRequest(state: String, attempt: String) {
            if (contextRequestState != SubagentActivitySnapshot.REQUEST_IN_PROGRESS &&
                contextRequestState != SubagentActivitySnapshot.REQUEST_RETRYING
            ) return
            markCurrentAttempt(attempt)
            contextRequestState = state
            currentAttemptHasReading = false
        }
        private var latestTurnUsage: AgentTokenUsage? = null
        private var pendingStepUsage: AgentTokenUsage? = null
        private var compactionInputTokens = 0
        private var compactionCachedTokens = 0
        private var compactionCacheWriteTokens = 0
        private var compactionOutputTokens = 0
        private var compactionReasoningTokens = 0
        private var compactionLlmMs = 0L

        fun onChildEvent(event: AgentEvent): Boolean = synchronized(lock) {
            when (event) {
                is AgentEvent.RoundStarted -> {
                    currentAttemptHasReading = false
                    pendingStepUsage = null
                    // Keep a previous failure visible while its retry is running.
                    if (contextRequestState != SubagentActivitySnapshot.REQUEST_RETRYING) {
                        contextRequestState = SubagentActivitySnapshot.REQUEST_IN_PROGRESS
                    }
                    currentAction = if (event.round <= 1) {
                        AppStrings.get(R.string.subagent_i18n_action_thinking, "正在思考…")
                    } else {
                        AppStrings.get(R.string.subagent_i18n_action_thinking_round, "第 %1\$d 轮思考…", event.round)
                    }
                    true
                }

                is AgentEvent.ModelRetryScheduled -> {
                    // The latest report may itself belong to the failed attempt; label it honestly.
                    markCurrentAttempt(SubagentContextReading.ATTEMPT_FAILED)
                    currentAttemptHasReading = false
                    contextRequestState = SubagentActivitySnapshot.REQUEST_RETRYING
                    // 失败尝试只留作步骤记录，不拼进后续的部分汇报。
                    roundTexts.remove(event.round)
                    finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
                    currentAction = event.displayMessage
                    true
                }

                is AgentEvent.UsageReceived -> {
                    if (event.usage.stepCount != null) {
                        latestTurnUsage = event.usage
                        pendingStepUsage = null
                    } else {
                        pendingStepUsage = event.usage
                    }
                    val reported = event.usage.contextTokens?.takeIf { it >= 0 }
                    if (reported == null) {
                        false
                    } else {
                        currentAttemptHasReading = true
                        if (contextRequestState == SubagentActivitySnapshot.REQUEST_UNKNOWN) {
                            contextRequestState = SubagentActivitySnapshot.REQUEST_IN_PROGRESS
                        }
                        setContextState(SubagentSessionStore.ContextState(
                            reading = SubagentContextReading(
                                tokens = reported,
                                source = SubagentContextReading.SOURCE_USAGE,
                                at = now(),
                                attempt = SubagentContextReading.ATTEMPT_REPORTED,
                            ),
                            model = modelId,
                            providerId = providerId,
                        ))
                        true // Even equal values are a new report with its own timestamp/attempt.
                    }
                }

                is AgentEvent.AssistantReceived -> {
                    markCurrentAttempt(SubagentContextReading.ATTEMPT_COMPLETED)
                    currentAttemptHasReading = false
                    contextRequestState = SubagentActivitySnapshot.REQUEST_COMPLETED
                    true
                }

                is AgentEvent.ContextCompaction -> {
                    if (event.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED &&
                        completedCompactionIds.add(event.operationId)
                    ) {
                        compactionCount = successfulCompactions.incrementAndGet()
                        currentAttemptHasReading = false
                        event.usage?.let { usage ->
                            compactionInputTokens += usage.inputTokens ?: 0
                            compactionCachedTokens += usage.cachedTokens ?: 0
                            compactionCacheWriteTokens += usage.cacheWriteTokens ?: 0
                            compactionOutputTokens += usage.outputTokens ?: 0
                            compactionReasoningTokens += usage.reasoningTokens ?: 0
                            compactionLlmMs += usage.llmMs ?: 0L
                        }
                        val after = event.tokensAfter?.takeIf { it >= 0 }
                        if (after != null) {
                            setContextState(SubagentSessionStore.ContextState(
                                reading = SubagentContextReading(after, SubagentContextReading.SOURCE_COMPACTION, now()),
                                model = modelId,
                                providerId = providerId,
                            ))
                        } else {
                            contextState?.let { state ->
                                setContextState(state.copy(reading = state.reading.copy(beforeCompaction = true)))
                            }
                        }
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
                                detail = (current.detail + event.delta).takeLastUtf16Safe(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        } else {
                            totalStepCount++
                            if (steps.size >= MAX_STEPS) steps.removeAt(0)
                            steps += SubagentStepSnapshot(
                                id = stepId,
                                kind = SubagentStepSnapshot.KIND_THINKING,
                                title = AppStrings.get(R.string.subagent_i18n_step_thinking, "思考"),
                                detail = event.delta.takeLastUtf16Safe(MAX_STEP_DETAIL_CHARS),
                                status = SubagentStepSnapshot.STATUS_RUNNING,
                            )
                        }
                        currentAction = AppStrings.get(R.string.subagent_i18n_action_thinking, "正在思考…")
                        false
                    }

                    AgentEvent.AssistantBlockKind.TEXT -> {
                        val text = roundTexts.getOrPut(event.round) { StringBuilder() }
                        text.append(event.delta)
                        if (text.length > MAX_OUTPUT_CHARS) {
                            retainedTruncated = true
                            val retained = text.toString().takeLastUtf16Safe(MAX_OUTPUT_CHARS)
                            text.clear()
                            text.append(retained)
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
                                    .takeLastUtf16Safe(MAX_STEP_DETAIL_CHARS),
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
                    val detail = event.command?.trim().orEmpty().takeUtf16Safe(MAX_STEP_DETAIL_CHARS)
                    if (steps.none { it.id == stepId }) {
                        totalToolSteps += 1
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
                        ).joinToString("\n").takeUtf16Safe(MAX_STEP_DETAIL_CHARS)
                        steps[existingIndex] = current.copy(
                            status = stepStatus,
                            detail = combinedDetail,
                        )
                    }
                    currentAction = AppStrings.get(R.string.subagent_i18n_action_analyzing, "正在分析结果…")
                    true
                }

                else -> false
            }
        }

        fun markCompleted(content: String) = synchronized(lock) {
            finishRunningThinkingSteps()
            status = SubagentActivitySnapshot.STATUS_COMPLETED
            val trimmed = content.trim()
            retainedTruncated = retainedTruncated || trimmed.length > MAX_OUTPUT_CHARS
            finalOutput = trimmed.takeUtf16Safe(MAX_OUTPUT_CHARS)
            val tools = toolCount()
            currentAction = if (tools > 0) {
                AppStrings.get(R.string.subagent_completed_with_tools, "完成 · %1\$d 次工具调用", tools)
            } else {
                AppStrings.get(R.string.subagent_status_completed, "完成")
            }
        }

        fun markFailed(error: String, partial: String) = synchronized(lock) {
            closeCurrentRequest(SubagentActivitySnapshot.REQUEST_FAILED, SubagentContextReading.ATTEMPT_FAILED)
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_FAILED
            errorText = error.takeUtf16Safe(1_000)
            if (partial.isNotBlank()) finalOutput = partial.takeUtf16Safe(MAX_OUTPUT_CHARS)
            currentAction = error.lineSequence().firstOrNull()?.takeUtf16Safe(80)
                ?: AppStrings.get(R.string.subagent_i18n_action_failed, "执行失败")
        }

        fun markCancelled(partial: String) = synchronized(lock) {
            closeCurrentRequest(SubagentActivitySnapshot.REQUEST_CANCELLED, SubagentContextReading.ATTEMPT_CANCELLED)
            finishAllRunningSteps(SubagentStepSnapshot.STATUS_FAILED)
            status = SubagentActivitySnapshot.STATUS_CANCELLED
            val stopped = AppStrings.get(R.string.subagent_status_stopped, "已停止")
            errorText = stopped
            if (partial.isNotBlank()) finalOutput = partial.takeUtf16Safe(MAX_OUTPUT_CHARS)
            currentAction = stopped
        }

        fun partialOutput(): String = synchronized(lock) {
            if (finalOutput.isNotBlank()) return finalOutput
            roundTexts.values
                .map { it.toString().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
                .takeLastUtf16Safe(MAX_OUTPUT_CHARS)
        }

        fun selectModel(config: AgentModelClient.ModelConfig, reason: String?) = synchronized(lock) {
            model = config.modelDisplayName.ifBlank { config.model }
            modelId = config.model
            providerId = config.providerId.ifBlank { config.baseUrl }
            contextWindow = config.knownContextWindow
            currentAction = "模型不可用（$reason），改用 $model；缓存重新开始"
        }

        fun toolCount(): Int = synchronized(lock) { totalToolSteps }

        fun snapshot(): SubagentActivitySnapshot = synchronized(lock) {
            val liveOutput = if (finalOutput.isNotBlank()) {
                finalOutput
            } else {
                roundTexts.values.lastOrNull()?.toString()?.trim().orEmpty().takeLastUtf16Safe(MAX_OUTPUT_CHARS)
            }
            val settled = latestTurnUsage
            val pending = pendingStepUsage
            val totalInput = (settled?.inputTokens ?: 0) + (pending?.inputTokens ?: 0) + compactionInputTokens
            val totalCached = (settled?.cachedTokens ?: 0) + (pending?.cachedTokens ?: 0) + compactionCachedTokens
            val totalCacheWrite = (settled?.cacheWriteTokens ?: 0) + (pending?.cacheWriteTokens ?: 0) + compactionCacheWriteTokens
            val totalOutput = (settled?.outputTokens ?: 0) + (pending?.outputTokens ?: 0) + compactionOutputTokens
            val totalReasoning = (settled?.reasoningTokens ?: 0) + (pending?.reasoningTokens ?: 0) + compactionReasoningTokens
            val totalLlmMs = (settled?.llmMs ?: 0L) + (pending?.llmMs ?: 0L) + compactionLlmMs
            val usageSnapshot = if (
                totalInput > 0 || totalCached > 0 || totalCacheWrite > 0 || totalOutput > 0 || totalLlmMs > 0L
            ) {
                SubagentUsageSnapshot(
                    inputTokens = totalInput,
                    cachedTokens = totalCached,
                    cacheWriteTokens = totalCacheWrite,
                    outputTokens = totalOutput,
                    reasoningTokens = totalReasoning,
                    llmMs = totalLlmMs,
                )
            } else null
            val retainedSteps = steps.takeLast(MAX_STEPS)
            SubagentActivitySnapshot(
                subagentId = subagentId,
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                model = model,
                resumed = resumed,
                status = status,
                currentAction = currentAction,
                steps = retainedSteps,
                totalSteps = totalStepCount,
                previewTruncated = retainedTruncated || totalStepCount > MAX_STEPS,
                output = liveOutput,
                error = errorText,
                contextTokens = contextState?.reading?.takeUnless {
                    it.beforeCompaction || it.previousInvocation
                }?.tokens,
                contextWindow = contextWindow,
                compactionCount = compactionCount,
                contextReading = contextState?.reading,
                contextRequestState = contextRequestState,
                usage = usageSnapshot,
                totalToolSteps = totalToolSteps,
                omittedSteps = (totalStepCount - retainedSteps.size).coerceAtLeast(0),
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
        private const val PROGRESS_THROTTLE_NANOS = 200_000_000L
        private const val MAX_SNAPSHOT_PROMPT_CHARS = 4_000
        private const val MAX_STEP_DETAIL_CHARS = 2_000
        private const val MAX_OUTPUT_CHARS = 128_000
        private const val MAX_STEPS = 256
    }
}
