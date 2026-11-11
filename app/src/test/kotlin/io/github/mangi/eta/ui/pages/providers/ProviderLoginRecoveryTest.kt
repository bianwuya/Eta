package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.agent.auth.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ProviderAccountLogin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProviderLoginRecoveryTest {
    @Test fun unreadableIndexRemainsVisibleAndRecoveryRequiresExplicitConfirmation() = runBlocking {
        var broken = true
        var rebuilds = 0
        val operations = object : ProviderLoginOperations {
            override suspend fun save(provider: ProviderSetting) = Unit
            override fun account(providerId: String, authMode: String): ProviderAccountSummary? = null
            override fun accounts(providerId: String, authMode: String): List<CodexAccountView> {
                if (broken) throw ProviderAuthException("oauth_accounts_unreadable", "fixture index error")
                return emptyList()
            }
            override fun login(providerId: String, authMode: String, controller: AgentRunController,
                onPrompt: (ProviderLoginPrompt) -> Unit, accountId: String?): ProviderAccountLogin = error("no OAuth")
            override fun rebuildUnreadableIndex(confirmed: Boolean) { check(confirmed); broken = false; rebuilds++ }
            override suspend fun syncModels(providerId: String, authMode: String): Int = 0
            override fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? = false
        }
        val store = ProviderLoginStore("fixture", this, operations)
        try {
            store.load(ProviderAuthModes.OPENAI_CHATGPT)
            val failed = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals("oauth_accounts_unreadable", failed.errorCode)
            store.rebuildUnreadableIndex(false)
            assertEquals(0, rebuilds)
            store.rebuildUnreadableIndex(true)
            val recovered = withTimeout(2_000) { store.state.first { !it.isBusy && it.errorCode == null } }
            assertEquals(1, rebuilds)
            assertTrue(recovered.message.orEmpty().contains("保全旧索引"))
        } finally { store.close() }
    }
}
