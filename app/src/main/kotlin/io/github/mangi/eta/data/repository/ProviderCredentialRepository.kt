package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.LiveProviderOAuthClient
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes

/** 只有 Codex 连接需要账号池；Grok 等其余认证方式保持单账号原语义。 */
internal fun usesCodexAccountPool(authMode: String): Boolean = authMode == ProviderAuthModes.OPENAI_CHATGPT

/**
 * 仅由 Eta 主进程初始化和访问；调用方在 I/O 线程执行，不将返回的令牌写入配置。
 *
 * - Codex（`OPENAI_CHATGPT`）走 [CodexAccountRepository] 的账号池：请求开始时的绑定固定到
 *   当时选中的账号，之后的手动切换不会改变在途请求与 401 刷新使用的账号。
 * - 其他认证方式保持原有语义：Grok 仍是单个 `providerId` 槽，API Key 不涉及凭据。
 */
internal object ProviderCredentialRepository {
    @Volatile private var sessions: ProviderCredentialSessions? = null
    @Volatile private var codexAccounts: CodexAccountRepository? = null

    @Synchronized
    fun init(context: Context) {
        if (sessions != null) return
        val created = ProviderCredentialSessions(EncryptedProviderCredentialStorage(context), LiveProviderOAuthClient())
        codexAccounts = CodexAccountRepository(created, CodexAccountStore(context) { providerId -> created.credential(providerId) })
        sessions = created
    }

    private fun isCodex(authMode: String): Boolean = usesCodexAccountPool(authMode)

    fun account(providerId: String, authMode: String): ProviderAccountSummary? = when {
        isCodex(authMode) -> codex().account(providerId, authMode)
        ProviderAuthModes.isOAuth(authMode) -> sessions().account(providerId, authMode)
        else -> null
    }

    /** 账号列表只对 Codex 有意义；其他认证方式返回空列表。 */
    fun accounts(providerId: String, authMode: String): List<CodexAccountView> =
        if (isCodex(authMode)) codex().views(providerId, authMode) else emptyList()

    fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
        accountId: String? = null,
    ): ProviderAccountLogin {
        if (isCodex(authMode)) return codex().login(providerId, authMode, controller, onPrompt, accountId)
        if (!ProviderAuthModes.isOAuth(authMode)) {
            throw ProviderAuthException("unsupported_auth_mode", "当前连接不支持账号登录")
        }
        // Grok 保持单账号：始终写回原 providerId 槽，不建索引，登录即成为当前账号。
        val credential = sessions().login(providerId, authMode, controller, onPrompt = onPrompt)
        return ProviderAccountLogin(
            accountId = providerId,
            summary = credential.summary(),
            selected = true,
            added = true,
        )
    }

    fun selectAccount(providerId: String, authMode: String, accountId: String) {
        if (isCodex(authMode)) codex().select(providerId, authMode, accountId)
    }

    fun renameAccount(providerId: String, accountId: String, name: String) {
        codex().rename(providerId, accountId, name)
    }

    fun removeAccount(providerId: String, authMode: String, accountId: String,
        controller: AgentRunController): Boolean? =
        if (isCodex(authMode)) codex().remove(providerId, authMode, accountId, controller) else null

    /** 请求开始时的账号绑定；无可用账号时明确失败，不回退其他账号。 */
    fun binding(providerId: String, authMode: String): ProviderCredentialBinding = when {
        isCodex(authMode) -> codex().binding(providerId)
        ProviderAuthModes.isOAuth(authMode) -> sessions().binding(providerId, authMode)
        else -> throw ProviderAuthException("unsupported_auth_mode", "当前连接不支持账号登录")
    }

    fun bindingOrNull(providerId: String, authMode: String): ProviderCredentialBinding? = when {
        isCodex(authMode) -> codex().bindingOrNull(providerId)
        ProviderAuthModes.isOAuth(authMode) -> sessions().binding(providerId, authMode)
        else -> null
    }

    fun codexAccountBinding(providerId: String, accountId: String): ProviderCredentialBinding =
        codex().bindingForAccount(providerId, accountId)

    fun isCurrent(binding: ProviderCredentialBinding): Boolean = sessions().isCurrent(binding)

    fun accessToken(providerId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String =
        accessToken(binding(providerId, authMode), controller, rejectedAccessToken)

    /** 401 后针对同一绑定刷新；不使用绑定以外的账号。 */
    fun accessToken(binding: ProviderCredentialBinding, controller: AgentRunController,
        rejectedAccessToken: String?): String =
        sessions().accessToken(binding.credentialSlotId, binding.authMode, controller, rejectedAccessToken)

    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? = when {
        isCodex(authMode) -> codex().disconnectSelected(providerId, authMode, controller)
        ProviderAuthModes.isOAuth(authMode) -> sessions().disconnect(providerId, authMode, controller)
        else -> true
    }

    /** 连接删除、认证方式变更或恢复备份：清账号池并删除原 `providerId` 槽。 */
    fun forget(providerId: String) = codex().forgetPool(providerId)

    private fun sessions(): ProviderCredentialSessions = sessions
        ?: throw ProviderAuthException("oauth_not_initialized", "登录服务尚未就绪，请重新打开 Eta")

    private fun codex(): CodexAccountRepository = codexAccounts
        ?: throw ProviderAuthException("oauth_not_initialized", "登录服务尚未就绪，请重新打开 Eta")
}
