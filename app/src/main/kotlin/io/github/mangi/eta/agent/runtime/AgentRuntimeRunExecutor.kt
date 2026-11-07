package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.agent.subagent.SubagentControlRegistry
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.local.conversationcommunication.ConversationMessagingPolicy
import io.github.mangi.eta.local.conversationcommunication.ConversationMessagingToolCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val guiControl: GuiControlCoordinator,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
    private val conversationMessaging: AgentRuntimeConversationMessaging? = null,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        io.github.mangi.eta.agent.model.ModelDebugTrace.initialize(appContext)
        val runController = session.controller
        val archivedEvents = AgentRunArchiveStore.createEventBuffer(request.handoff)
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var guiRun: GuiControlCoordinator.RunControl? = null
        var browserBinding: AutoCloseable? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        var childOwner: io.github.mangi.eta.agent.subagent.YieldableSubagents? = null
        var childCheckpoint: AgentSubagentCheckpoint? = null
        val childController = AgentRunController()
        val childCancellation = runController.registerChild(childController)
        val timing = AgentRunTiming(AndroidAgentLogger)
        var planGate: io.github.mangi.eta.local.planmode.PlanRunGate? = null
        fun endPlan(completedRun: Boolean) {
            if (planGate == null) return
            runCatching { io.github.mangi.eta.local.planmode.PlanModeStore.end(request.runId, completedRun) }
                .onFailure { AndroidAgentLogger.error("Plan run final state persistence failed: type=${it.safeLogType()}") }
            io.github.mangi.eta.local.planmode.PlanModeStore.unlisten(request.runId)
        }

        val result = try {
            val contextWindow = request.config.knownContextWindow
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            // 由跨会话消息启动的 run 不再获得通信工具，避免会话间自动互唤。
            if (request.requestMode == "plan") {
                require(request.operation == AgentRuntimeWire.OP_CHAT && !conversationId.isNullOrBlank()) { "计划模式需要聊天会话" }
                io.github.mangi.eta.local.planmode.PlanModeStore.initialize(appContext)
                planGate = io.github.mangi.eta.local.planmode.PlanRunGate(conversationId, request.runId, runController, request.planLineageId.ifBlank { request.runId })
                io.github.mangi.eta.local.planmode.PlanModeStore.listen(request.runId, runController::signalWorkAvailable) { planGate.mayAct() }
            }
            val crossConversationOrigin =
                request.origin == AgentRuntimeWire.RUN_ORIGIN_CONVERSATION_MESSAGE
            val conversationTools = conversationMessaging
                ?.takeIf { !crossConversationOrigin && request.operation == AgentRuntimeWire.OP_CHAT }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    ConversationMemoryPins.contextFor(
                        conversationId = conversationId,
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，避免把检测中的授权当成普通设备。
                runBlocking {
                    while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                        runController.throwIfCancelled()
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val browserKey = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            browserBinding = BrowserSessionRegistry.acquireRun(browserKey, request.runId)
            guiRun = guiControl.openRun(request.runId, runController) { status ->
                acceptEvent(
                    session, AgentEvent.GuiControlChanged(status), archivedEvents,
                    entrySurfaceGuard, checkpointRecorder,
                )
            }
            fun localTools(
                gui: GuiControlCoordinator.RunControl?,
                conversationExecutor: AgentModelClient.ToolExecutor? = null,
                toolController: AgentRunController = runController,
            ) = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                runController = toolController,
                userQuestions = io.github.mangi.eta.local.userquestions.UserQuestionHost(appContext, conversationId, request.runId, toolController, planIdentity = {
                    io.github.mangi.eta.local.planmode.PlanModeStore.current(request.runId)?.let { it.planId to it.revision }
                }),
                browserSession = BrowserSessionRegistry.get(browserKey),
                guiControl = gui,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = if (gui != null) githubSkillSource else null,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                virtualDisplayEnabled = { currentPermissions().virtualDisplay },
                conversationMessaging = conversationExecutor,
            )
            val executor = localTools(guiRun, conversationTools?.tool)
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = conversationId?.let { id ->
                ConversationHistoryTool {
                    val checkpoint = runBlocking { EtaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                    val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                        .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                    journal + session.transcript
                }
            }
            val subagentAllowed = {
                request.config.subagentTools && currentPermissions().subagentTools
            }
            val controlRegistry = SubagentControlRegistry()
            val supportsChildGroups = checkpointRecorder != null && request.operation == AgentRuntimeWire.OP_CHAT
            val groupStore = if (supportsChildGroups) AgentSubagentCheckpoint(appContext, request.runId) { runController.isCancelled } else null
            val subagentExecutor = SubagentExecutor(
                conversationKey = browserKey,
                parentRunId = request.runId,
                compactionTarget = { primary -> runBlocking { RuntimeConfigRepository.compactionTarget(primary) } },
                requestStatsEpoch = {
                    // Read in the actual runtime process at each attempt boundary, not a UI-only flag.
                    val settings = runBlocking { io.github.mangi.eta.data.datastore.SettingsDataStore.subagentSettings() }
                    settings.statsEpoch.takeIf { settings.collectRequestStats }
                },
                onRequestStat = { record ->
                    io.github.mangi.eta.local.subagentstats.SubagentStatsStore.get(appContext).save(record)
                },
                parentController = childController,
                controlRegistry = controlRegistry,
                mailboxStorage = groupStore,
                mailboxToolGate = { call, delegate ->
                    if (planGate == null) delegate() else planGate.executeChild(call, delegate)
                },
                configProvider = {
                    val rawSubagentConfig = runBlocking {
                        RuntimeConfigRepository.subagentRuntimeConfig(request.config)
                    }
                    AgentRuntimePolicy.constrain(rawSubagentConfig, currentPermissions())
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                baseToolExecutor = routingExecutor,
                childToolFactory = { child ->
                    val childRouting = RoutingToolExecutor(localTools(null, toolController = child), McpToolExecutor(mcpSnapshot))
                    if (planGate == null) childRouting else object : AgentModelClient.ToolExecutor, AutoCloseable {
                        override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
                            planGate.executeChild(toolCall) { childRouting.execute(toolCall) }
                        override fun close() = childRouting.close()
                    }
                },
                detailStoreFactory = { io.github.mangi.eta.agent.subagent.SubagentDetailStore(appContext) },
                skillContext = skillContext,
                memoryContext = memoryContext,
                mcpTools = mcpTools,
                executionContext = executor.terminalExecutionContext,
                onProgress = { progressEvent ->
                    acceptEvent(
                        session,
                        progressEvent,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                },
            )
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (subagentAllowed()) AgentSubagentToolCatalog.appendSchemas(tools, groupsSupported = supportsChildGroups)
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (conversationTools != null) ConversationMessagingToolCatalog.appendSchemas(tools)
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
                if (planGate != null) io.github.mangi.eta.local.planmode.PlanRunGate.schemas(tools)
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (crossConversationOrigin && ConversationMessagingPolicy.isOriginBlockedTool(call.name)) {
                    AgentModelClient.ToolResult(
                        JSONObject()
                            .put("ok", false)
                            .put("code", "CONVERSATION_ORIGIN_BLOCKED")
                            .put("message", "本运行由其他会话的消息启动，禁止据此执行提权、安装或安全设置操作；" +
                                "需要时请让用户在当前会话直接授权")
                            .toString(),
                    )
                } else if (call.name == AgentSubagentToolCatalog.SEAL_GROUP_TOOL_NAME) {
                    childOwner?.sealGroup(call) ?: AgentModelClient.ToolResult(JSONObject().put("ok", false)
                        .put("code", "SUBAGENT_GROUP_UNAVAILABLE").put("message", "当前运行不支持持久化关联组。").toString())
                } else if (call.name == AgentSubagentToolCatalog.CONTROL_TOOL_NAME) {
                    // Disabling future delegation must not block the parent from stopping
                    // children that are already running in this run.
                    controlRegistry.execute(call)
                } else if (call.name in AgentSubagentToolCatalog.names) {
                    if (!subagentAllowed()) {
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭")
                                .toString(),
                        )
                    } else if (call.name == AgentSubagentToolCatalog.PROFILE_TOOL_NAME) {
                        subagentExecutor.executeProfileManagement(call)
                    } else {
                        subagentExecutor.execute(call.round, call)
                    }
                } else if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else routingExecutor.execute(call).also { result ->
                    if (call.name == "memory_write") {
                        runCatching { JSONObject(result.content) }.getOrNull()
                            ?.takeIf { it.optBoolean("ok") }
                            ?.let { ConversationMemoryPins.recordOwnWrite(conversationId, it.optString("revision")) }
                    }
                }
            }
            // UI and archive-capable external handoffs have durable in-flight recovery. A caller
            // without a recoverable handoff retains the synchronous protocol.
            if (supportsChildGroups) {
                val store = checkNotNull(groupStore)
                childCheckpoint = store
                session.setChildRecovery { transcript -> AgentSubagentCheckpoint.recover(appContext, request.runId, transcript) }
                childOwner = io.github.mangi.eta.agent.subagent.YieldableSubagents(
                    controller = runController,
                    persistence = store,
                    cancelChildren = childController::cancel,
                    parentRunId = request.runId,
                    executeGrouped = { call, invocation, started ->
                        if (!subagentAllowed()) {
                            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭").toString())
                        } else subagentExecutor.execute(call.round, call, invocation, started)
                    },
                    execute = { call, started ->
                        if (!subagentAllowed()) {
                            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭").toString())
                        } else {
                            subagentExecutor.execute(call.round, call, onStarted = started)
                        }
                    },
                )
            }
            val completedResponse = AgentModelClient.complete(
                config = request.config,
                compactionTarget = { runBlocking { RuntimeConfigRepository.compactionTarget(request.config) } },
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                roleplayContext = roleplayContext,
                rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                onContextSnapshot = { snapshot ->
                    // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                    ConversationMemoryPins.forget(conversationId)
                    val committed = snapshot.copy(operationId = request.runId)
                    session.commitContext(committed) { AgentRunCheckpointStore.saveContext(appContext, request.runId, committed) }
                },
                onTranscript = { transcript ->
                    session.commitTranscript(transcript) {
                        val saved = AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                        // Non-handoff callers retain their existing in-memory synchronous path.
                        check(checkpointRecorder == null || saved) { "运行中的 transcript 检查点不存在" }
                    }
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                prompt = request.prompt,
                assistantScreenContext = request.assistantScreenContext,
                toolExecutor = runToolExecutor,
                images = request.images,
                history = request.history,
                runController = runController,
                yieldableSubagents = childOwner,
                planGate = planGate,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = runTools,
                executionContext = executor.terminalExecutionContext,
                toolFilter = if (crossConversationOrigin) {
                    { tools -> ConversationMessagingPolicy.filterOriginRunTools(tools) }
                } else {
                    null
                },
            ) { event ->
                timing.accept(event)
                acceptEvent(
                    session,
                    event,
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
            }
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            val abortFailure = runCatching { childOwner?.abort() }.exceptionOrNull()
            // 中断并不改变会话的系统前缀；保留记忆 pin，避免此前 own-write 后重新注入最新快照。
            // 外部记忆变化仍由 contextFor 检测；真正压缩历史时由 onContextSnapshot 失效。
            val modelFailure = throwable as? AgentModelExecutionException
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException ||
                throwable is java.util.concurrent.CancellationException ||
                modelFailure?.cause is AgentRunCancelledException || modelFailure?.cause is java.util.concurrent.CancellationException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            val partialTranscript = modelFailure?.transcript ?: session.transcript
            val childRecovery = runCatching {
                if (childCheckpoint != null) AgentSubagentCheckpoint.recover(appContext, request.runId, partialTranscript)
                else AgentToolBatchRecovery.completeInterrupted(partialTranscript)
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = if (abortFailure != null || childRecovery.isFailure) "$message；子代理恢复记录读写失败，结果可能不完整。" else message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = childRecovery.getOrDefault(AgentToolBatchRecovery.completeInterrupted(partialTranscript)),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            planGate?.end()
            runCatching { childOwner?.close() }
            runCatching { childController.cancel() }
            childCancellation.close()
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            runCatching { guiRun?.close() }
            runCatching { browserBinding?.close() }
        }

        if (cancelled && session.isTerminal) {
            endPlan(completedRun = false)
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents.orEmpty())
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        var committedResult = result
        val committed = session.complete(result) { resolved ->
            committedResult = resolved
            endPlan(completedRun = resolved.ok)
            runCatching { synchronized(this) { checkpointRecorder?.seal() } }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, resolved, archivedEvents.orEmpty()) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        if (!committed) endPlan(completedRun = false)
        return Outcome(
            result = committedResult,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed && committedResult.ok },
            shouldUpdateHost = committed,
        )
    }

    @Synchronized
    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>?,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        if (!session.emit(event) { checkpointRecorder?.accept(event) }) return
        if (event !is AgentEvent.GuiControlChanged) archivedEvents?.add(event)
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
}
