package io.github.mangi.eta.agent.auth

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 刷新锁只合并同一账号的网络请求；注销不等待网络，并使旧请求的回写失效。
 *
 * 所有状态都以**凭据槽**为键：不同 Codex 账号使用各自的槽，因此登录、刷新、注销互不覆盖。
 * 槽标识由调用方解析（`ProviderCredentialRepository`），本类不假设它等于服务商 ID。
 */
internal class ProviderCredentialSessions(
    private val storage: ProviderCredentialStorage,
    private val oauth: ProviderOAuthClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Session {
        val stateLock = ReentrantLock()
        val refreshLock = ReentrantLock()
        var generation = 0L
        var loginGeneration = 0L
    }
    private val sessions = ConcurrentHashMap<String, Session>()

    fun account(slotId: String, authMode: String): ProviderAccountSummary? {
        val session = session(slotId)
        return session.stateLock.withLock { storage.read(slotId)?.takeIf { it.authMode == authMode }?.summary() }
    }

    /** 账号身份的原始凭据；只供主进程做迁移与去重判断，不返回给 UI。 */
    fun credential(slotId: String): ProviderCredential? {
        val session = session(slotId)
        return session.stateLock.withLock { storage.read(slotId) }
    }

    fun binding(credentialSlotId: String, authMode: String, providerId: String = credentialSlotId): ProviderCredentialBinding {
        val session = session(credentialSlotId)
        return session.stateLock.withLock {
            requireCredential(storage.read(credentialSlotId), authMode)
            ProviderCredentialBinding(providerId, authMode, session.generation, credentialSlotId)
        }
    }

    fun isCurrent(binding: ProviderCredentialBinding): Boolean {
        val session = session(binding.credentialSlotId)
        return session.stateLock.withLock {
            val credential = storage.read(binding.credentialSlotId)
            session.generation == binding.generation && credential?.authMode == binding.authMode &&
                credential.subject != null && credential.accessToken.isNotBlank() && credential.planEnabled
        }
    }

    /**
     * 登录只写入传入的槽。[accept] 在写入前校验账号身份，抛出异常即中止，
     * 因此重新登录到另一个账号不会覆盖原账号的凭据。
     *
     * [onPrompt] 保持为最后一个参数，既有 `sessions.login(...) { prompt -> }` 尾随 lambda 调用不受影响。
     */
    fun login(
        slotId: String,
        authMode: String,
        controller: AgentRunController,
        accept: (ProviderCredential) -> Unit = {},
        onPrompt: (ProviderLoginPrompt) -> Unit,
    ): ProviderCredential {
        val provider = OAuthProvider.fromAuthMode(authMode)
        val session = session(slotId)
        val loginGeneration = session.stateLock.withLock { ++session.loginGeneration }
        val credential = oauth.login(provider, controller, onPrompt)
        controller.throwIfCancelled()
        return session.stateLock.withLock {
            controller.throwIfCancelled()
            // 浏览器登录尚未成功时，原会话仍可刷新；取消登录不能丢掉已经轮换的凭据。
            requireGeneration(session.loginGeneration, loginGeneration)
            accept(credential)
            storage.write(slotId, credential)
            ++session.generation
            credential
        }
    }

    /** 用新签发的凭据刷新已有账号槽；供重复添加同一账号时合并，不改变账号槽标识。 */
    fun install(slotId: String, credential: ProviderCredential) {
        val session = session(slotId)
        session.stateLock.withLock {
            storage.write(slotId, credential)
            ++session.generation
            ++session.loginGeneration
        }
    }

    fun accessToken(slotId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String {
        OAuthProvider.fromAuthMode(authMode)
        val session = session(slotId)
        while (!session.refreshLock.tryLock(100, TimeUnit.MILLISECONDS)) controller.throwIfCancelled()
        try {
            controller.throwIfCancelled()
            val (generation, credential) = session.stateLock.withLock {
                session.generation to requireCredential(storage.read(slotId), authMode)
            }
            val rejected = rejectedAccessToken != null && rejectedAccessToken == credential.accessToken
            val expiresSoon = credential.expiresAt <= now() + REFRESH_MARGIN_MS
            if (!rejected && !expiresSoon && credential.expiresAt > now()) return credential.accessToken
            if (credential.refreshToken.isBlank()) {
                invalidate(slotId, session, generation)
                throw ProviderAuthException("oauth_session_expired", "登录已失效，请重新登录")
            }
            val refreshed = try {
                oauth.refresh(credential, controller)
            } catch (error: ProviderAuthException) {
                if (error.code == "oauth_session_expired") invalidate(slotId, session, generation)
                throw error
            }
            // 轮换已在服务端完成，即使调用方刚取消，也先保存有效结果，避免丢失新的 refresh token。
            session.stateLock.withLock {
                requireGeneration(session.generation, generation)
                storage.write(slotId, refreshed)
            }
            controller.throwIfCancelled()
            return requireCredential(refreshed, authMode).accessToken
        } finally {
            session.refreshLock.unlock()
        }
    }

    fun disconnect(slotId: String, authMode: String, controller: AgentRunController): Boolean? {
        val session = session(slotId)
        val credential = session.stateLock.withLock {
            ++session.generation
            ++session.loginGeneration
            val current = try { storage.read(slotId) } catch (_: ProviderAuthException) {
                storage.delete(slotId)
                return false
            }
            if (current != null && current.authMode == authMode) storage.write(slotId, current.withoutTokens())
            current?.takeIf { it.authMode == authMode }
        } ?: return true
        return try { oauth.revoke(credential, controller) } catch (_: ProviderAuthException) { false }
    }

    fun forget(slotId: String) {
        val session = session(slotId)
        session.stateLock.withLock {
            ++session.generation
            ++session.loginGeneration
            storage.delete(slotId)
        }
    }

    private fun invalidate(slotId: String, session: Session, generation: Long) {
        session.stateLock.withLock {
            if (session.generation != generation) return
            storage.read(slotId)?.let { storage.write(slotId, it.withoutTokens()) }
            ++session.generation
        }
    }

    private fun requireCredential(credential: ProviderCredential?, authMode: String): ProviderCredential {
        if (credential == null || credential.subject == null || credential.authMode != authMode || credential.accessToken.isBlank()) {
            throw ProviderAuthException("oauth_login_required", "请先登录此服务商账号")
        }
        if (!credential.planEnabled) throw ProviderAuthException("oauth_plan_permission_required", "账号凭据不能用于当前订阅连接，请重新登录")
        return credential
    }

    private fun session(slotId: String): Session {
        if (slotId.isBlank()) throw ProviderAuthException("oauth_provider_missing", "服务商连接不存在")
        return sessions.computeIfAbsent(slotId) { Session() }
    }

    private fun requireGeneration(current: Long, expected: Long) {
        if (current != expected) throw ProviderAuthException("oauth_session_changed", "登录状态已变更，请重试")
    }

    private companion object { const val REFRESH_MARGIN_MS = 60_000L }
}
