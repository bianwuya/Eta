package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.data.repository.CodexAccountView
import io.github.mangi.eta.data.repository.ProviderAccountLogin
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLoginStoreTest {
    @Test
    fun savesConfigurationBeforeStartingAuthorizationAndIgnoresDoubleClick() = runBlocking {
        val operations = FakeOperations(waitForCancellation = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            store.login(provider)
            withTimeout(2_000) { store.state.first { it.prompt != null } }

            assertEquals(listOf("save", "login"), operations.calls.toList())
            store.cancel()
            withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertNull(store.state.value.prompt)
            assertNull(store.state.value.message)
            assertTrue(operations.controller!!.isCancelled)
        } finally {
            store.close()
        }
    }

    @Test
    fun failedSaveNeverStartsAuthorizationOrExposesUnexpectedException() = runBlocking {
        val operations = FakeOperations(saveError = IllegalStateException("sensitive payload"))
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }

            assertEquals(listOf("save"), operations.calls.toList())
            assertEquals("登录失败，请重试", state.message)
            assertNull(state.prompt)
        } finally {
            store.close()
        }
    }

    @Test
    fun controlledAuthFailureRemainsActionable() = runBlocking {
        val operations = FakeOperations(saveError = ProviderAuthException("reauth_required", "请重新登录"))
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals("请重新登录", state.message)
        } finally {
            store.close()
        }
    }

    @Test
    fun restoredLoginAttemptRequiresExplicitRetryWithoutReopeningBrowser() = runBlocking {
        val operations = FakeOperations()
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.load(provider.authMode, interrupted = true)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }

            assertEquals("登录已中断，请重试", state.message)
            assertNull(state.prompt)
            assertTrue(operations.calls.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun leavingPageCancelsBlockingLoginResource() = runBlocking {
        val operations = FakeOperations(waitForCancellation = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { it.prompt != null } }
            store.close()
            assertTrue(operations.controller!!.isCancelled)
        } finally {
            store.close()
        }
    }

    @Test
    fun disconnectClearsAccountEvenWhenRemoteRevocationIsUnconfirmed() = runBlocking {
        val operations = FakeOperations(revoked = false)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.load(provider.authMode)
            withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(account, store.state.value.account)

            store.disconnect()
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertNull(state.account)
            assertEquals("已退出登录，远端撤销未确认", state.message)
            assertFalse(state.isBusy)
        } finally {
            store.close()
        }
    }

    @Test
    fun successfulLoginFetchesAndSynchronizesModelsBeforeCompleting() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val operations = FakeOperations(syncRelease = release)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val syncing = withTimeout(2_000) { store.state.first { it.phase == ProviderLoginPhase.SyncingModels } }
            assertEquals(account, syncing.account)
            assertNull(syncing.prompt)
            release.complete(Unit)
            val done = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(listOf("save", "login", "sync"), operations.calls.toList())
            assertEquals("已登录，已同步 2 个远端模型", done.message)
        } finally { store.close() }
    }

    @Test
    fun catalogFailureKeepsTheSuccessfulLoginAndDoesNotClaimSynchronizationSucceeded() = runBlocking {
        val operations = FakeOperations(syncError = IllegalStateException("sensitive payload"))
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(account, state.account)
            assertEquals("已登录，模型同步失败，请在模型页重新拉取", state.message)
            assertEquals(listOf("save", "login", "sync"), operations.calls.toList())
        } finally { store.close() }
    }

    @Test
    fun cancellationDuringCatalogFetchDoesNotRemoveTheNewAccount() = runBlocking {
        val operations = FakeOperations(syncRelease = CompletableDeferred())
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { it.phase == ProviderLoginPhase.SyncingModels } }
            store.cancel()
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }
            assertEquals(account, state.account)
            assertNull(state.message)
        } finally { store.close() }
    }

    @Test
    fun addingSecondAccountKeepsTheCurrentSelectionAndSkipsModelSync() = runBlocking {
        val operations = FakeOperations(addUnselectedSecondAccount = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            val state = withTimeout(2_000) { store.state.first { !it.isBusy } }

            assertEquals(listOf("save", "login"), operations.calls.toList())
            assertEquals(account, state.account)
            assertEquals("first", state.selected?.accountId)
            assertEquals(2, state.accounts.size)
            assertFalse(state.accounts.single { it.accountId == "second" }.selected)
            assertEquals("已添加账号 test@example.com", state.message)
        } finally {
            store.close()
        }
    }

    @Test
    fun selectingAnotherAccountSwitchesTheCurrentOneWithoutTouchingModels() = runBlocking {
        val operations = FakeOperations(addUnselectedSecondAccount = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { !it.isBusy } }

            store.select("second")
            val state = withTimeout(2_000) { store.state.first { !it.isBusy && "select:second" in operations.calls } }

            assertEquals("second", operations.views.single { it.selected }.accountId)
            assertEquals("second", state.selected?.accountId)
            assertTrue("sync" !in operations.calls.toList())
            assertTrue(state.message.orEmpty().contains("已切换到所选账号"))
        } finally {
            store.close()
        }
    }

    @Test
    fun reloginTargetsOnlyTheGivenAccountAndKeepsTheSelection() = runBlocking {
        val operations = FakeOperations(addUnselectedSecondAccount = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { !it.isBusy } }

            store.login(provider, reloginAccountId = "second")
            withTimeout(2_000) { store.state.first { !it.isBusy } }

            assertTrue("relogin:second" in operations.calls.toList())
            assertEquals("first", store.state.value.selected?.accountId)
        } finally {
            store.close()
        }
    }

    @Test
    fun deletingCurrentAccountLeavesNoSelectionInsteadOfFallingBack() = runBlocking {
        val operations = FakeOperations(addUnselectedSecondAccount = true)
        val store = ProviderLoginStore(provider.id, this, operations)
        try {
            store.login(provider)
            withTimeout(2_000) { store.state.first { !it.isBusy } }

            store.remove("first")
            val state = withTimeout(2_000) { store.state.first { !it.isBusy && "remove:first" in operations.calls } }

            assertNull(state.account)
            assertNull(state.selected)
            assertEquals("已删除账号", state.message)
        } finally {
            store.close()
        }
    }

    private class FakeOperations(
        val waitForCancellation: Boolean = false,
        val saveError: Exception? = null,
        val revoked: Boolean = true,
        val syncError: Exception? = null,
        val syncRelease: CompletableDeferred<Unit>? = null,
        /** 模拟新增第二个账号：登录成功但不会成为当前账号。 */
        val addUnselectedSecondAccount: Boolean = false,
    ) : ProviderLoginOperations {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var controller: AgentRunController? = null
        @Volatile var currentAccount: ProviderAccountSummary? = account
        val views = Collections.synchronizedList(
            mutableListOf(CodexAccountView("first", "", account.email, null, true, false, true)),
        )

        override suspend fun save(provider: ProviderSetting) {
            calls += "save"
            saveError?.let { throw it }
        }

        override fun account(providerId: String, authMode: String): ProviderAccountSummary? = currentAccount

        override fun accounts(providerId: String, authMode: String): List<CodexAccountView> = views.toList()

        override fun selectAccount(providerId: String, authMode: String, accountId: String) {
            calls += "select:$accountId"
            replaceSelection(accountId)
        }

        override fun renameAccount(providerId: String, accountId: String, name: String) {
            calls += "rename:$accountId"
            views.replaceAll { if (it.accountId == accountId) it.copy(name = name) else it }
        }

        override fun removeAccount(
            providerId: String,
            authMode: String,
            accountId: String,
            controller: AgentRunController,
        ): Boolean {
            calls += "remove:$accountId"
            views.removeAll { it.accountId == accountId }
            if (views.none { it.selected }) currentAccount = null
            return revoked
        }

        override fun login(
            providerId: String,
            authMode: String,
            controller: AgentRunController,
            onPrompt: (ProviderLoginPrompt) -> Unit,
            accountId: String?,
        ): ProviderAccountLogin {
            calls += if (accountId == null) "login" else "relogin:$accountId"
            this.controller = controller
            onPrompt(ProviderLoginPrompt("https://auth.example.com/authorize", "TEST-CODE"))
            if (waitForCancellation) controller.awaitRetryDelay(60_000)
            if (accountId != null) {
                val selected = views.single { it.accountId == accountId }.selected
                return ProviderAccountLogin(accountId, account, selected = selected, added = false)
            }
            if (addUnselectedSecondAccount) {
                views += CodexAccountView("second", "备用", "second@example.com", null, false, false, true)
                return ProviderAccountLogin("second", account, selected = false, added = true)
            }
            return ProviderAccountLogin("first", account, selected = true, added = true)
        }

        private fun replaceSelection(accountId: String) {
            views.replaceAll { it.copy(selected = it.accountId == accountId) }
            if (accountId == "first") currentAccount = account
        }

        override suspend fun syncModels(providerId: String, authMode: String): Int {
            assertEquals(provider.authMode, authMode)
            calls += "sync"
            syncError?.let { throw it }
            syncRelease?.await()
            return 2
        }

        override fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean {
            calls += "disconnect"
            views.replaceAll { it.copy(needsReauthentication = true) }
            currentAccount = null
            return revoked
        }
    }

    private companion object {
        val provider = CustomProviderSetting(
            id = "test-provider",
            name = "测试服务商",
            baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val account = ProviderAccountSummary("test@example.com", null, false, true)
    }
}
