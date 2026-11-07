package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderAccountLogin
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ProviderLoginPhase { Idle, Loading, Saving, Authorizing, SyncingModels, Disconnecting }

internal data class ProviderLoginState(
    val authMode: String = ProviderAuthModes.API_KEY,
    val account: ProviderAccountSummary? = null,
    val accounts: List<CodexAccountView> = emptyList(),
    /** 正在重新登录或删除的账号；用于只禁用该条目的按钮。 */
    val activeAccountId: String? = null,
    val prompt: ProviderLoginPrompt? = null,
    val phase: ProviderLoginPhase = ProviderLoginPhase.Idle,
    val message: String? = null,
) {
    val isBusy: Boolean get() = phase != ProviderLoginPhase.Idle
    val selected: CodexAccountView? get() = accounts.firstOrNull { it.selected }
}

/**
 * 登录会话归属当前配置页；浏览器切换不取消，离开页面则关闭轮询或回调监听。
 *
 * 一个连接可保存多个账号：添加、重新登录、重命名、选择与删除都只作用于目标账号，
 * 不改变服务商连接与模型配置，也不自动切换当前账号。
 */
internal class ProviderLoginStore(
    private val providerId: String,
    parentScope: CoroutineScope,
    private val operations: ProviderLoginOperations = RepositoryProviderLoginOperations,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(ProviderLoginState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var controller: AgentRunController? = null
    private var generation = 0L

    fun load(authMode: String, interrupted: Boolean = false) {
        if (mutableState.value.isBusy) return
        if (!ProviderAuthModes.isOAuth(authMode)) {
            mutableState.value = ProviderLoginState(authMode = authMode)
            return
        }
        runOperation(authMode, ProviderLoginPhase.Loading) { _, _ ->
            refreshAccounts(
                authMode,
                message = if (interrupted) "登录已中断，请重试" else mutableState.value.message,
            )
        }
    }

    /** 添加账号；[reloginAccountId] 非空时为该条目重新登录，绝不写入其他账号的槽。 */
    fun login(provider: ProviderSetting, reloginAccountId: String? = null) {
        if (mutableState.value.isBusy || provider.id != providerId) return
        runOperation(provider.authMode, ProviderLoginPhase.Saving, reloginAccountId) { activeController, attempt ->
            withContext(ioDispatcher) { operations.save(provider) }
            activeController.throwIfCancelled()
            mutableState.value = mutableState.value.copy(phase = ProviderLoginPhase.Authorizing)
            val result = withContext(ioDispatcher) {
                operations.login(
                    providerId = providerId,
                    authMode = provider.authMode,
                    controller = activeController,
                    onPrompt = { prompt ->
                        scope.launch {
                            if (generation == attempt && !activeController.isCancelled &&
                                mutableState.value.phase == ProviderLoginPhase.Authorizing
                            ) {
                                mutableState.value = mutableState.value.copy(prompt = prompt)
                            }
                        }
                    },
                    accountId = reloginAccountId,
                )
            }
            mutableState.value = mutableState.value.copy(account = result.summary, prompt = null, phase = ProviderLoginPhase.SyncingModels)
            if (!result.selected) {
                // 新账号不替换当前账号，也不为它改动模型目录。
                refreshAccounts(provider.authMode, null)
                mutableState.value = mutableState.value.copy(
                    message = if (result.added) "已添加账号 ${result.summary.email ?: ""}".trim() else "已更新账号登录",
                )
                return@runOperation
            }
            val count = try {
                withContext(ioDispatcher) { operations.syncModels(providerId, provider.authMode) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                refreshAccounts(provider.authMode, "已登录，模型同步失败，请在模型页重新拉取")
                return@runOperation
            }
            refreshAccounts(provider.authMode, "已登录，已同步 $count 个远端模型")
        }
    }

    fun select(accountId: String) {
        val authMode = mutableState.value.authMode
        if (mutableState.value.isBusy || !ProviderAuthModes.isOAuth(authMode)) return
        runOperation(authMode, ProviderLoginPhase.Saving, accountId) { _, _ ->
            withContext(ioDispatcher) { operations.selectAccount(providerId, authMode, accountId) }
            refreshAccounts(authMode, "已切换到所选账号，之后发起的请求使用该账号")
        }
    }

    fun rename(accountId: String, name: String) {
        val authMode = mutableState.value.authMode
        if (!ProviderAuthModes.isOAuth(authMode)) return
        scope.launch {
            try {
                withContext(ioDispatcher) { operations.renameAccount(providerId, accountId, name) }
                refreshAccounts(authMode, "已更新账号备注")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    message = (error as? ProviderAuthException)?.message ?: "保存账号备注失败，请重试",
                )
            }
        }
    }

    fun remove(accountId: String) {
        val authMode = mutableState.value.authMode
        if (mutableState.value.isBusy || !ProviderAuthModes.isOAuth(authMode)) return
        runOperation(authMode, ProviderLoginPhase.Disconnecting, accountId) { activeController, _ ->
            val revoked = withContext(ioDispatcher) {
                operations.removeAccount(providerId, authMode, accountId, activeController)
            }
            refreshAccounts(
                authMode,
                if (revoked == false) "已删除账号，远端撤销未确认" else "已删除账号",
            )
        }
    }

    /** 退出当前账号的登录状态，保留账号条目以便重新登录。 */
    fun disconnect() {
        val authMode = mutableState.value.authMode
        if (mutableState.value.isBusy || !ProviderAuthModes.isOAuth(authMode)) return
        val selectedId = mutableState.value.selected?.accountId
        runOperation(authMode, ProviderLoginPhase.Disconnecting, selectedId) { activeController, _ ->
            val revoked = withContext(ioDispatcher) { operations.disconnect(providerId, authMode, activeController) }
            refreshAccounts(
                authMode,
                if (revoked == false) "已退出登录，远端撤销未确认" else "已退出登录",
            )
        }
    }

    fun cancel() {
        controller?.cancel()
        operation?.cancel()
        mutableState.value = mutableState.value.copy(prompt = null)
    }

    fun close() {
        generation++
        controller?.cancel()
        scope.cancel()
    }

    private suspend fun refreshAccounts(authMode: String, message: String?) {
        val snapshot = withContext(ioDispatcher) {
            operations.accounts(providerId, authMode) to operations.account(providerId, authMode)
        }
        mutableState.value = mutableState.value.copy(
            authMode = authMode,
            accounts = snapshot.first,
            account = snapshot.second,
            message = message,
        )
    }

    private fun runOperation(
        authMode: String,
        phase: ProviderLoginPhase,
        activeAccountId: String? = null,
        action: suspend (AgentRunController, Long) -> Unit,
    ) {
        val attempt = ++generation
        val activeController = AgentRunController()
        controller = activeController
        mutableState.value = mutableState.value.copy(
            authMode = authMode,
            phase = phase,
            activeAccountId = activeAccountId,
            prompt = null,
            message = null,
        )
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                action(activeController, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AgentRunCancelledException) {
                // 取消是用户结束当前授权，不显示失败提示。
            } catch (error: Exception) {
                if (generation == attempt && !activeController.isCancelled) {
                    mutableState.value = mutableState.value.copy(
                        message = when (mutableState.value.phase) {
                            ProviderLoginPhase.SyncingModels -> "已登录，模型同步失败，请在模型页重新拉取"
                            else -> (error as? ProviderAuthException)?.message ?: when (phase) {
                                ProviderLoginPhase.Loading -> "无法读取登录状态，请重试"
                                ProviderLoginPhase.Disconnecting -> "操作失败，请重试"
                                else -> "登录失败，请重试"
                            }
                        },
                    )
                }
            } finally {
                activeController.cancel()
                if (generation == attempt) {
                    controller = null
                    mutableState.value = mutableState.value.copy(
                        phase = ProviderLoginPhase.Idle,
                        activeAccountId = null,
                        prompt = null,
                    )
                }
            }
        }
    }
}

internal interface ProviderLoginOperations {
    suspend fun save(provider: ProviderSetting)
    fun account(providerId: String, authMode: String): ProviderAccountSummary?
    /** 账号列表与状态。 */
    fun accounts(providerId: String, authMode: String): List<CodexAccountView> = emptyList()
    fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
        accountId: String? = null,
    ): ProviderAccountLogin
    fun selectAccount(providerId: String, authMode: String, accountId: String) = Unit
    fun renameAccount(providerId: String, accountId: String, name: String) = Unit
    fun removeAccount(providerId: String, authMode: String, accountId: String, controller: AgentRunController): Boolean? = true
    suspend fun syncModels(providerId: String, authMode: String): Int
    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean?
}

private object RepositoryProviderLoginOperations : ProviderLoginOperations {
    override suspend fun save(provider: ProviderSetting) {
        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
    }

    override fun account(providerId: String, authMode: String): ProviderAccountSummary? =
        ProviderCredentialRepository.account(providerId, authMode)

    override fun accounts(providerId: String, authMode: String): List<CodexAccountView> =
        ProviderCredentialRepository.accounts(providerId, authMode)

    override fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
        accountId: String?,
    ): ProviderAccountLogin = ProviderCredentialRepository.login(providerId, authMode, controller, onPrompt, accountId)

    override fun selectAccount(providerId: String, authMode: String, accountId: String) =
        ProviderCredentialRepository.selectAccount(providerId, authMode, accountId)

    override fun renameAccount(providerId: String, accountId: String, name: String) =
        ProviderCredentialRepository.renameAccount(providerId, accountId, name)

    override fun removeAccount(
        providerId: String,
        authMode: String,
        accountId: String,
        controller: AgentRunController,
    ): Boolean? = ProviderCredentialRepository.removeAccount(providerId, authMode, accountId, controller)

    override suspend fun syncModels(providerId: String, authMode: String): Int {
        val provider = requireNotNull(ProviderRepository.providerById(providerId)) { "服务商连接不存在" }
        if (provider.authMode != authMode) {
            throw ProviderAuthException("oauth_session_changed", "服务商登录方式已变更，请重新拉取模型")
        }
        val catalog = RemoteModelFetcher.fetchCatalog(provider).getOrThrow()
        val result = ModelRepository.syncRemoteCatalog(catalog)
        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
        return result.fetchedCount
    }

    override fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? =
        ProviderCredentialRepository.disconnect(providerId, authMode, controller)
}
