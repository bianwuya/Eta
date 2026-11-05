package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.agent.auth.OAuthProvider
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.auth.ProviderOAuthClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodexAccountRepositoryTest {
    private lateinit var context: Context
    private lateinit var storage: MemoryStorage
    private lateinit var oauth: FakeOAuthClient
    private lateinit var sessions: ProviderCredentialSessions

    private fun repository(legacy: (String) -> ProviderCredential? = { storage.read(it) }) =
        CodexAccountRepository(sessions, CodexAccountStore(context) { providerId -> legacy(providerId) })

    private fun setUpWith(legacy: (String) -> ProviderCredential? = { storage.read(it) }): CodexAccountRepository {
        context = RuntimeEnvironment.getApplication()
        storage = MemoryStorage()
        oauth = FakeOAuthClient()
        sessions = ProviderCredentialSessions(storage, oauth) { NOW }
        return repository(legacy)
    }

    @Test
    fun firstLoginCreatesOnlyOneIndependentSlot() {
        val accounts = setUpWith()
        oauth.next = credential("subject-a", "workspace-a")

        val added = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})

        assertTrue(added.selected)
        assertEquals(1, accounts.views(PROVIDER_ID, AUTH_MODE).size)
        assertNotEquals(PROVIDER_ID, accounts.binding(PROVIDER_ID).credentialSlotId)
        assertNull(storage.read(PROVIDER_ID))
    }

    @Test
    fun inFlightBindingAndRefreshStayOnOriginalAccountAfterSelectionChanges() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        val oldBinding = accounts.binding(PROVIDER_ID)
        oauth.next = credential("subject-b", "workspace-b")
        val second = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})
        accounts.select(PROVIDER_ID, AUTH_MODE, second.accountId)

        assertEquals("access-subject-a", accounts.accessToken(oldBinding, AgentRunController(), null))
        assertEquals("access-subject-a", accounts.accessToken(oldBinding, AgentRunController(), "access-subject-a"))
        assertEquals("access-subject-b", accounts.accessToken(accounts.binding(PROVIDER_ID), AgentRunController(), null))
        assertEquals(1, oauth.refreshes.get())
    }

    @Test
    fun fixedImageAccountBindingDoesNotFollowOrChangeChatSelection() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        val first = accounts.views(PROVIDER_ID, AUTH_MODE).single().accountId
        oauth.next = credential("subject-b", "workspace-b")
        val second = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})
        accounts.select(PROVIDER_ID, AUTH_MODE, second.accountId)
        val fixed = accounts.bindingForAccount(PROVIDER_ID, first)
        assertEquals("access-subject-a", accounts.accessToken(fixed, AgentRunController(), null))
        assertEquals(second.accountId, accounts.views(PROVIDER_ID, AUTH_MODE).single { it.selected }.accountId)
        assertThrows(ProviderAuthException::class.java) { accounts.bindingForAccount(PROVIDER_ID, "missing") }
    }

    @Test
    fun legacySingleAccountBecomesTheSelectedAccountWithoutMovingCiphertext() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("legacy-subject", "workspace-legacy"))

        val views = accounts.views(PROVIDER_ID, AUTH_MODE)

        assertEquals(1, views.size)
        assertTrue(views.single().selected)
        assertTrue(views.single().loggedIn)
        // 旧数据继续引用原 providerId 槽，不搬移、不重写密文。
        assertEquals(listOf(PROVIDER_ID), storage.writes.toList())
        assertEquals(PROVIDER_ID, accounts.binding(PROVIDER_ID).credentialSlotId)
        assertEquals("access-legacy-subject", accounts.accessToken(accounts.binding(PROVIDER_ID), AgentRunController(), null))
    }

    @Test
    fun legacyCredentialReadFailureIsNotTreatedAsMissingAccount() {
        val accounts = setUpWith(legacy = { throw ProviderAuthException("credential_storage_failed", "无法读写登录凭据") })

        val failure = assertThrows(ProviderAuthException::class.java) { accounts.views(PROVIDER_ID, AUTH_MODE) }

        assertEquals("credential_storage_failed", failure.code)
    }

    @Test
    fun accountIndexSurvivesANewStoreInstance() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        oauth.next = credential("subject-b", "workspace-b")
        val added = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})
        accounts.select(PROVIDER_ID, AUTH_MODE, added.accountId)

        val restarted = repository()

        assertEquals(2, restarted.views(PROVIDER_ID, AUTH_MODE).size)
        assertEquals(added.accountId, restarted.views(PROVIDER_ID, AUTH_MODE).single { it.selected }.accountId)
        assertNotEquals(PROVIDER_ID, restarted.binding(PROVIDER_ID).credentialSlotId)
    }

    @Test
    fun addingSecondAccountKeepsFirstCredentialAndSelection() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        val originalCredential = storage.read(PROVIDER_ID)

        oauth.next = credential("subject-b", "workspace-b")
        val added = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})

        assertTrue(added.added)
        assertFalse(added.selected)
        assertEquals(originalCredential, storage.read(PROVIDER_ID))
        assertFalse(accounts.views(PROVIDER_ID, AUTH_MODE).single { it.accountId == added.accountId }.selected)
        // 当前账号仍是第一个。
        assertEquals(PROVIDER_ID, accounts.binding(PROVIDER_ID).credentialSlotId)
    }

    @Test
    fun selectingSecondAccountPinsItsSlotWithoutTouchingTheFirst() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        oauth.next = credential("subject-b", "workspace-b")
        val added = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})
        val firstCredential = storage.read(PROVIDER_ID)

        accounts.select(PROVIDER_ID, AUTH_MODE, added.accountId)

        val binding = accounts.binding(PROVIDER_ID)
        assertNotEquals(PROVIDER_ID, binding.credentialSlotId)
        assertEquals("access-subject-b", accounts.accessToken(binding, AgentRunController(), null))
        assertEquals(firstCredential, storage.read(PROVIDER_ID))
    }

    @Test
    fun reAddingSameStableAccountMergesIntoTheExistingEntry() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        oauth.next = credential("subject-a", "workspace-a")
        val added = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})

        assertFalse(added.added)
        assertEquals(1, accounts.views(PROVIDER_ID, AUTH_MODE).size)
    }

    @Test
    fun reloginWithDifferentAccountDoesNotOverwriteTheOriginalCredential() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        val entry = accounts.views(PROVIDER_ID, AUTH_MODE).single()
        val original = storage.read(PROVIDER_ID)

        oauth.next = credential("subject-other", "workspace-other")
        val failure = assertThrows(ProviderAuthException::class.java) {
            accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {}, entry.accountId)
        }

        assertEquals("oauth_account_mismatch", failure.code)
        assertEquals(original, storage.read(PROVIDER_ID))
    }

    @Test
    fun deletingCurrentAccountLeavesNoSelectionAndRefusesToGuess() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        oauth.next = credential("subject-b", "workspace-b")
        accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})
        val first = accounts.views(PROVIDER_ID, AUTH_MODE).single { it.selected }

        accounts.remove(PROVIDER_ID, AUTH_MODE, first.accountId, AgentRunController())

        val remaining = accounts.views(PROVIDER_ID, AUTH_MODE)
        assertEquals(1, remaining.size)
        assertFalse(remaining.single().selected)
        assertNull(accounts.account(PROVIDER_ID, AUTH_MODE))
        val failure = assertThrows(ProviderAuthException::class.java) { accounts.binding(PROVIDER_ID) }
        assertEquals("oauth_account_required", failure.code)
        // 不回退：剩下那个账号的凭据没有被自动选中，也没有被删除。
        assertEquals("access-subject-b", storage.read(remaining.single().let { slotOf(accounts, it.accountId) })?.accessToken)
    }

    @Test
    fun deletingOneAccountKeepsOtherCredentials() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        oauth.next = credential("subject-b", "workspace-b")
        val second = accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})
        val originalFirst = storage.read(PROVIDER_ID)

        accounts.remove(PROVIDER_ID, AUTH_MODE, second.accountId, AgentRunController())

        assertEquals(originalFirst, storage.read(PROVIDER_ID))
        assertEquals(PROVIDER_ID, accounts.binding(PROVIDER_ID).credentialSlotId)
        assertEquals(1, accounts.views(PROVIDER_ID, AUTH_MODE).size)
    }

    @Test
    fun forgettingThePoolClearsEverySlotAndTheIndex() {
        val accounts = setUpWith()
        storage.write(PROVIDER_ID, credential("subject-a", "workspace-a"))
        oauth.next = credential("subject-b", "workspace-b")
        accounts.login(PROVIDER_ID, AUTH_MODE, AgentRunController(), {})

        accounts.forgetPool(PROVIDER_ID)

        assertTrue(accounts.views(PROVIDER_ID, AUTH_MODE).isEmpty())
        assertEquals(emptySet<String>(), storage.keys())
    }

    @Test
    fun onlyCodexUsesTheAccountPool() {
        assertTrue(usesCodexAccountPool(ProviderAuthModes.OPENAI_CHATGPT))
        assertFalse(usesCodexAccountPool(ProviderAuthModes.XAI_GROK))
        assertFalse(usesCodexAccountPool(ProviderAuthModes.API_KEY))
    }

    /** 测试用：账号槽 ID 通过重新读取索引获得，避免把实现细节复制进断言。 */
    private fun slotOf(accounts: CodexAccountRepository, accountId: String): String =
        CodexAccountStore(context) { null }.existingPool(PROVIDER_ID)!!
            .accounts.single { it.accountId == accountId }
            .credentialSlotId

    private class MemoryStorage : ProviderCredentialStorage {
        private val credentials = ConcurrentHashMap<String, ProviderCredential>()
        val writes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun read(providerId: String): ProviderCredential? = credentials[providerId]
        override fun write(providerId: String, credential: ProviderCredential) {
            writes += providerId
            credentials[providerId] = credential
        }
        override fun delete(providerId: String) {
            credentials.remove(providerId)
        }
        fun keys(): Set<String> = credentials.keys.toSet()
    }

    private class FakeOAuthClient(var next: ProviderCredential? = null) : ProviderOAuthClient {
        val logins = AtomicInteger()
        val refreshes = AtomicInteger()
        override fun login(
            provider: OAuthProvider,
            controller: AgentRunController,
            onPrompt: (ProviderLoginPrompt) -> Unit,
        ): ProviderCredential {
            logins.incrementAndGet()
            onPrompt(ProviderLoginPrompt("https://auth.example.invalid/authorize"))
            return requireNotNull(next) { "测试未准备登录结果" }
        }

        override fun refresh(credential: ProviderCredential, controller: AgentRunController): ProviderCredential {
            refreshes.incrementAndGet()
            return credential
        }

        override fun revoke(credential: ProviderCredential, controller: AgentRunController): Boolean? = true
    }

    private companion object {
        const val PROVIDER_ID = "codex-connection"
        const val AUTH_MODE = ProviderAuthModes.OPENAI_CHATGPT
        const val NOW = 1_700_000_000_000L

        fun credential(subject: String, workspace: String) = ProviderCredential(
            authMode = AUTH_MODE,
            clientId = CodexOAuth.CLIENT_ID,
            subject = subject,
            email = "$subject@example.invalid",
            accessToken = "access-$subject",
            refreshToken = "refresh-$subject",
            accountId = workspace,
            expiresAt = NOW + 3_600_000,
        )
    }
}
