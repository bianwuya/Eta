package io.github.mangi.eta.testing

import android.content.Context
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.agent.auth.CodexOAuthFixture
import io.github.mangi.eta.agent.auth.OAuthProvider
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.auth.ProviderOAuthClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.repository.CodexAccountRepository
import io.github.mangi.eta.data.repository.CodexAccountStore
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import java.util.concurrent.ConcurrentHashMap

/** Installs synthetic accounts at the credential boundary; no Keystore or network login. */
internal class CodexUsageFixture(context: Context, val providerId: String) : AutoCloseable {
    private val credentials = ConcurrentHashMap<String, ProviderCredential>()
    private val storage = object : ProviderCredentialStorage {
        override fun read(providerId: String) = credentials[providerId]
        override fun write(providerId: String, credential: ProviderCredential) { credentials[providerId] = credential }
        override fun delete(providerId: String) { credentials.remove(providerId) }
    }
    private var nextWorkspace = "workspace-first"
    private val oauth = object : ProviderOAuthClient {
        override fun login(provider: OAuthProvider, controller: AgentRunController, onPrompt: (ProviderLoginPrompt) -> Unit) =
            ProviderCredential(authMode = ProviderAuthModes.OPENAI_CHATGPT, clientId = CodexOAuth.CLIENT_ID,
                subject = nextWorkspace, email = "$nextWorkspace@example.invalid", accountId = nextWorkspace,
                accessToken = CodexOAuthFixture.accessToken(nextWorkspace), refreshToken = "fixture-refresh",
                expiresAt = System.currentTimeMillis() + 3_600_000)
        override fun refresh(credential: ProviderCredential, controller: AgentRunController) = credential
        override fun revoke(credential: ProviderCredential, controller: AgentRunController): Boolean = true
    }
    private val sessions = ProviderCredentialSessions(storage, oauth)
    val accounts = CodexAccountRepository(sessions, CodexAccountStore(context) { credentials[it] })
    private val sessionsField = ProviderCredentialRepository::class.java.getDeclaredField("sessions").apply { isAccessible = true }
    private val accountsField = ProviderCredentialRepository::class.java.getDeclaredField("codexAccounts").apply { isAccessible = true }
    private val previousSessions = sessionsField.get(null)
    private val previousAccounts = accountsField.get(null)
    val first: String
    val second: String

    init {
        sessionsField.set(null, sessions)
        accountsField.set(null, accounts)
        first = accounts.login(providerId, ProviderAuthModes.OPENAI_CHATGPT, AgentRunController(), {}).accountId
        nextWorkspace = "workspace-second"
        second = accounts.login(providerId, ProviderAuthModes.OPENAI_CHATGPT, AgentRunController(), {}).accountId
    }

    override fun close() {
        sessionsField.set(null, previousSessions)
        accountsField.set(null, previousAccounts)
    }
}
