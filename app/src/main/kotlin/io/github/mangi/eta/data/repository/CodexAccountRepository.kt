package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.auth.ProviderCredentialIdentity
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.auth.identity
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个 Codex 连接下保存的账号。只记录非敏感索引，令牌仍只在加密凭据存储中。
 *
 * [credentialSlotId] 一经生成保持不变：首次升级的账号继续引用原 `providerId` 凭据槽，
 * 新增账号各自使用独立槽，因此新登录不会覆盖旧账号。
 */
@Serializable
internal data class CodexAccountEntry(
    val accountId: String,
    val name: String = "",
    val credentialSlotId: String,
    val identitySubject: String? = null,
    val identityAccountId: String? = null,
    val email: String? = null,
    val displayName: String? = null,
) {
    val identity: ProviderCredentialIdentity?
        get() = identitySubject?.takeIf(String::isNotBlank)?.let {
            ProviderCredentialIdentity(it, identityAccountId?.takeIf(String::isNotBlank))
        }
}

@Serializable
internal data class CodexAccountPool(
    val providerId: String,
    val accounts: List<CodexAccountEntry> = emptyList(),
    val selectedAccountId: String? = null,
)

@Serializable
internal data class CodexAccountIndex(
    val pools: List<CodexAccountPool> = emptyList(),
)

/** UI 使用的账号视图：索引条目 + 实时读取到的登录状态。 */
internal data class CodexAccountView(
    val accountId: String,
    val name: String,
    val email: String?,
    val displayName: String?,
    val selected: Boolean,
    val needsReauthentication: Boolean,
    val planEnabled: Boolean,
) {
    val loggedIn: Boolean get() = !needsReauthentication
    val label: String get() = name.ifBlank { email ?: displayName ?: "未命名账号" }

    /** 只有已登录且订阅可用的账号能被选中。 */
    val usable: Boolean get() = loggedIn && planEnabled
}

internal data class ProviderAccountLogin(
    val accountId: String,
    val summary: ProviderAccountSummary,
    val selected: Boolean,
    val added: Boolean,
)

/**
 * Codex 多账号索引与手动选择的唯一协调层。
 *
 * - 只服务 `OPENAI_CHATGPT`；Grok 等其他 OAuth 连接仍走单账号原语义，不建索引。
 * - 不改变服务商连接、模型选择或请求配置，只把认证绑定解析到选中的账号槽。
 * - 不自动轮换：当前账号只由用户操作改变，请求失败沿用原有错误处理。
 * - 仅主进程使用；索引读写在锁内完成，先落盘再报告成功。
 */
internal class CodexAccountRepository(
    private val sessions: ProviderCredentialSessions,
    private val store: CodexAccountStore,
) {
    private val lock = ReentrantLock()

    fun bindingOrNull(providerId: String): ProviderCredentialBinding? {
        val slotId = selectedSlotId(providerId) ?: throw ProviderAuthException(
            "oauth_account_required",
            if (views(providerId, ProviderAuthModes.OPENAI_CHATGPT).isEmpty()) "请先登录此服务商账号" else "请先在账号列表选择一个账号",
        )
        return sessions.binding(slotId, ProviderAuthModes.OPENAI_CHATGPT, providerId)
    }

    fun binding(providerId: String): ProviderCredentialBinding =
        bindingOrNull(providerId) ?: throw ProviderAuthException("unsupported_auth_mode", "当前连接不支持账号登录")

    fun isCurrent(binding: ProviderCredentialBinding): Boolean = sessions.isCurrent(binding)

    fun accessToken(binding: ProviderCredentialBinding, controller: AgentRunController, rejected: String?): String =
        sessions.accessToken(binding.credentialSlotId, binding.authMode, controller, rejected)

    /** 当前选中账号的摘要；未选择或凭据缺失时返回 null，不回退到其他账号。 */
    fun account(providerId: String, authMode: String): ProviderAccountSummary? {
        val selected = lock.withLock {
            val pool = requirePool(providerId)
            pool.selectedAccountId?.let { id -> pool.accounts.firstOrNull { it.accountId == id } }
        } ?: return null
        return sessions.account(selected.credentialSlotId, authMode)
    }

    fun views(providerId: String, authMode: String): List<CodexAccountView> {
        val pool = lock.withLock { requirePool(providerId) }
        return pool.accounts.map { entry ->
            val summary = sessions.account(entry.credentialSlotId, authMode)
            CodexAccountView(
                accountId = entry.accountId,
                name = entry.name,
                email = summary?.email ?: entry.email,
                displayName = summary?.displayName ?: entry.displayName,
                selected = entry.accountId == pool.selectedAccountId,
                needsReauthentication = summary?.needsReauthentication ?: true,
                planEnabled = summary?.planEnabled ?: false,
            )
        }
    }

    /**
     * 登录并写入账号索引。
     *
     * [targetAccountId] 为空表示新增：首个账号成为当前账号，已有旧凭据继续引用原槽，
     * 之后新增的账号保持原有选择，不自动换号。相同稳定身份的重复添加合入已有条目。
     */
    fun login(
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit,
        targetAccountId: String? = null,
    ): ProviderAccountLogin {
        val target = targetAccountId?.let { id ->
            lock.withLock { requirePool(providerId).accounts.firstOrNull { it.accountId == id } }
                ?: throw ProviderAuthException("oauth_account_missing", "账号不存在，请刷新后重试")
        }
        // 先迁移旧槽；所有新增账号始终写独立槽，索引写入前也不会覆盖旧凭据。
        lock.withLock { requirePool(providerId) }
        val slotId = target?.credentialSlotId ?: newSlotId(providerId)
        val credential = sessions.login(slotId, authMode, controller, accept = { credential ->
            val identity = credential.identity()
                ?: throw ProviderAuthException("oauth_account_unverified", "未取得可验证的账号身份，登录未保存")
            if (target != null) requireSameAccount(target, identity)
        }, onPrompt = onPrompt)
        controller.throwIfCancelled()
        val identity = credential.identity()
            ?: throw ProviderAuthException("oauth_account_unverified", "未取得可验证的账号身份，登录未保存")

        return lock.withLock {
            val pool = requirePool(providerId)
            val currentTarget = target?.let { existing -> pool.accounts.firstOrNull { it.accountId == existing.accountId } }
            if (target != null && currentTarget == null) {
                // 登录期间条目被删除：不保留刚写入的结果，避免留下无主凭据。
                if (target.credentialSlotId != providerId) sessions.forget(target.credentialSlotId)
                throw ProviderAuthException("oauth_account_missing", "账号已被删除，登录结果未保存")
            }
            if (currentTarget != null) {
                val updated = currentTarget.copy(email = credential.email, displayName = credential.displayName)
                writePool(pool.withAccount(updated))
                return ProviderAccountLogin(
                    accountId = currentTarget.accountId,
                    summary = credential.summary(),
                    selected = pool.selectedAccountId == currentTarget.accountId,
                    added = false,
                )
            }

            // 只有可验证身份相同时才合并；合并后旧条目继续使用彼此独立的槽。
            val existing = pool.accounts.firstOrNull { it.identity?.matches(identity) == true }
            if (existing != null) {
                sessions.install(existing.credentialSlotId, credential)
                if (slotId != existing.credentialSlotId) sessions.forget(slotId)
                val updated = existing.copy(email = credential.email, displayName = credential.displayName)
                writePool(pool.withAccount(updated))
                return ProviderAccountLogin(
                    accountId = existing.accountId,
                    summary = credential.summary(),
                    selected = pool.selectedAccountId == existing.accountId,
                    added = false,
                )
            }

            val accountId = UUID.randomUUID().toString()
            val entry = CodexAccountEntry(
                accountId = accountId,
                name = defaultName(pool.accounts.size, credential),
                credentialSlotId = slotId,
                identitySubject = identity.subject,
                identityAccountId = identity.accountId,
                email = credential.email,
                displayName = credential.displayName,
            )
            val selection = pool.selectedAccountId
                ?.takeIf { id -> pool.accounts.any { it.accountId == id } }
                ?: accountId
            writePool(pool.copy(accounts = pool.accounts + entry, selectedAccountId = selection))
            ProviderAccountLogin(
                accountId = accountId,
                summary = credential.summary(),
                selected = selection == accountId,
                added = true,
            )
        }
    }

    fun select(providerId: String, authMode: String, accountId: String) {
        lock.withLock {
            val pool = requirePool(providerId)
            val entry = pool.accounts.firstOrNull { it.accountId == accountId }
                ?: throw ProviderAuthException("oauth_account_missing", "账号不存在，请刷新后重试")
            val credential = sessions.credential(entry.credentialSlotId)
            if (credential == null || credential.subject == null || credential.accessToken.isBlank()) {
                throw ProviderAuthException("oauth_login_required", "该账号需要重新登录后才能使用")
            }
            if (credential.authMode != authMode) {
                throw ProviderAuthException("oauth_session_changed", "服务商登录方式已变更，请重新登录")
            }
            if (!credential.planEnabled) {
                throw ProviderAuthException("oauth_plan_permission_required", "该账号不能用于当前订阅连接，请重新登录")
            }
            writePool(pool.copy(selectedAccountId = accountId))
        }
    }

    fun rename(providerId: String, accountId: String, name: String) {
        val normalized = name.trim().take(MAX_NAME_LENGTH)
        lock.withLock {
            val pool = requirePool(providerId)
            val entry = pool.accounts.firstOrNull { it.accountId == accountId }
                ?: throw ProviderAuthException("oauth_account_missing", "账号不存在，请刷新后重试")
            writePool(pool.withAccount(entry.copy(name = normalized)))
        }
    }

    /**
     * 删除只清理目标账号的槽与索引；删除当前账号后选择置空，不自动挑选其他账号。
     *
     * 网络撤销在锁外完成，索引删除基于**最新**账号池，避免覆盖期间完成的新增、重命名或选择。
     */
    fun remove(providerId: String, authMode: String, accountId: String, controller: AgentRunController): Boolean? {
        val entry = lock.withLock {
            requirePool(providerId).accounts.firstOrNull { it.accountId == accountId }
        } ?: throw ProviderAuthException("oauth_account_missing", "账号不存在，请刷新后重试")
        val revoked = try {
            sessions.disconnect(entry.credentialSlotId, authMode, controller)
        } catch (_: ProviderAuthException) {
            false
        }
        val removed = lock.withLock {
            val pool = requirePool(providerId)
            if (pool.accounts.none { it.accountId == accountId }) return@withLock false
            writePool(
                pool.copy(
                    accounts = pool.accounts.filterNot { it.accountId == accountId },
                    selectedAccountId = pool.selectedAccountId?.takeIf { it != accountId },
                ),
            )
            true
        }
        if (removed) sessions.forget(entry.credentialSlotId)
        return revoked
    }

    /** 退出当前账号的登录态但保留账号条目，行为与原有单账号退出一致。 */
    fun disconnectSelected(providerId: String, authMode: String, controller: AgentRunController): Boolean? {
        val slotId = lock.withLock {
            val pool = requirePool(providerId)
            pool.selectedAccountId?.let { id -> pool.accounts.firstOrNull { it.accountId == id } }?.credentialSlotId
        } ?: return true
        return sessions.disconnect(slotId, authMode, controller)
    }

    /** 服务商连接删除、认证方式变更或恢复备份时清理整个账号池与旧单账号槽。 */
    fun forgetPool(providerId: String) {
        // 不触发迁移：这里只清已有索引与旧单账号槽，不读凭据。
        val pool = lock.withLock {
            val current = store.existingPool(providerId)
            store.clear(providerId)
            current
        }
        pool?.accounts?.forEach { entry ->
            if (entry.credentialSlotId != providerId) sessions.forget(entry.credentialSlotId)
        }
        sessions.forget(providerId)
    }

    private fun selectedSlotId(providerId: String): String? = lock.withLock {
        val pool = requirePool(providerId)
        pool.selectedAccountId
            ?.let { id -> pool.accounts.firstOrNull { it.accountId == id } }
            ?.credentialSlotId
    }

    private fun requirePool(providerId: String): CodexAccountPool =
        store.pool(providerId) ?: CodexAccountPool(providerId)

    private fun writePool(pool: CodexAccountPool) {
        store.write(pool)
    }

    private fun CodexAccountPool.withAccount(entry: CodexAccountEntry): CodexAccountPool =
        copy(accounts = accounts.map { if (it.accountId == entry.accountId) entry else it })

    /** 重新登录只能更新同一个账号；身份不一致时中止，避免把原条目变成另一个账号。 */
    private fun requireSameAccount(entry: CodexAccountEntry, identity: ProviderCredentialIdentity) {
        val previous = entry.identity ?: return
        if (!previous.matches(identity)) {
            throw ProviderAuthException("oauth_account_mismatch", "登录的账号与该条目不一致，请改为添加账号")
        }
    }

    private fun defaultName(index: Int, credential: ProviderCredential): String =
        credential.email?.substringBefore('@')?.takeIf(String::isNotBlank) ?: "账号 ${index + 1}"

    private fun newSlotId(providerId: String): String = "$providerId#account:${UUID.randomUUID()}"

    private companion object { const val MAX_NAME_LENGTH = 40 }
}

/**
 * 账号索引的持久化：只存非敏感元数据，复用已有的本地 Agent 偏好文件
 * （`Prefs.LOCAL_AGENT_GROUP`），不新增独立存储体系。
 *
 * 首次读取时若发现旧单账号凭据且尚无索引，则引用原 `providerId` 槽建立条目，
 * 不搬移或覆盖旧密文；凭据读取失败会向上抛出，不能当成“没有旧账号”。
 */
internal class CodexAccountStore(
    context: Context,
    private val legacyCredential: (String) -> ProviderCredential?,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        Prefs.LOCAL_AGENT_GROUP,
        Context.MODE_PRIVATE,
    )
    private val lock = ReentrantLock()
    private var cached: CodexAccountIndex? = null

    fun pool(providerId: String): CodexAccountPool? = lock.withLock {
        existingPool(providerId)?.let { return it }
        val migrated = migrate(providerId) ?: return null
        write(CodexAccountIndex(read().pools + migrated))
        migrated
    }

    /** 只读已有索引，不触发旧单账号迁移。 */
    fun existingPool(providerId: String): CodexAccountPool? = lock.withLock {
        read().pools.firstOrNull { it.providerId == providerId }
    }

    fun write(pool: CodexAccountPool) = lock.withLock {
        val index = read()
        write(CodexAccountIndex(index.pools.filterNot { it.providerId == pool.providerId } + pool))
    }

    fun clear(providerId: String) = lock.withLock {
        // 索引读取失败要报告错误，不能清空其他连接的账号记录。
        val index = read()
        write(CodexAccountIndex(index.pools.filterNot { it.providerId == providerId }))
    }

    private fun migrate(providerId: String): CodexAccountPool? {
        // 读取失败必须向上抛出：否则会把“密钥不可用”误判成没有旧账号并覆盖原槽。
        val credential = legacyCredential(providerId) ?: return null
        val identity = credential.identity()
        val entry = CodexAccountEntry(
            accountId = UUID.randomUUID().toString(),
            name = credential.email?.substringBefore('@')?.takeIf(String::isNotBlank).orEmpty(),
            credentialSlotId = providerId,
            identitySubject = identity?.subject,
            identityAccountId = identity?.accountId,
            email = credential.email,
            displayName = credential.displayName,
        )
        return CodexAccountPool(providerId, listOf(entry), entry.accountId)
    }

    private fun read(): CodexAccountIndex {
        cached?.let { return it }
        val raw = preferences.getString(INDEX_KEY, "").orEmpty()
        val index = if (raw.isBlank()) {
            CodexAccountIndex()
        } else {
            // 索引损坏不能默默当成“没有账号”，否则界面会误导用户重新添加账号。
            runCatching { json.decodeFromString<CodexAccountIndex>(raw) }.getOrElse {
                throw ProviderAuthException("oauth_accounts_unreadable", "账号列表无法读取，请重新打开 Eta 后重试")
            }
        }
        cached = index
        return index
    }

    private fun write(index: CodexAccountIndex) {
        val encoded = json.encodeToString(index)
        // 先确认落盘成功再更新内存缓存，写入失败不能表现为已保存。
        if (!preferences.edit().putString(INDEX_KEY, encoded).commit()) {
            throw ProviderAuthException("oauth_accounts_save_failed", "无法保存账号列表，请重试")
        }
        cached = index
    }

    private companion object {
        const val INDEX_KEY = "codex_accounts_v1"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
