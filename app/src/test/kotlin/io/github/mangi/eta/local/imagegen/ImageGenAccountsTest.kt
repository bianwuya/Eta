package io.github.mangi.eta.local.imagegen

import io.github.mangi.eta.agent.auth.ProviderAuthException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ImageGenAccountsTest {
    @Test fun missingCredentialKeyIsPresentedWithoutCrashingOrChangingConfig() {
        val config = ImageGenConfig(ImageGenSource.CODEX_SUBSCRIPTION, "fixed", "account")
        val result = loadImageGenAccounts(config) { _, _ ->
            throw ProviderAuthException("credential_key_unavailable", "登录凭据的密钥不可用，请退出后重新登录")
        }
        assertTrue(result.accounts.isEmpty())
        assertTrue(result.error.orEmpty().contains("重新登录"))
        assertEquals("fixed", config.providerId)
        assertEquals("account", config.accountId)
    }

    @Test fun relayAndUnconfiguredSourcesNeverReadSubscriptionCredentials() {
        for (source in listOf(ImageGenSource.OPENAI_API, ImageGenSource.NONE)) {
            val state = loadImageGenAccounts(ImageGenConfig(source, "relay")) { _, _ -> error("Must not read subscription credentials") }
            assertTrue(state.accounts.isEmpty())
            assertNull(state.error)
        }
    }

    @Test fun cancellationIsNeverTurnedIntoAnAccountError() {
        assertThrows(CancellationException::class.java) {
            loadImageGenAccounts(ImageGenConfig(ImageGenSource.CODEX_SUBSCRIPTION, "fixed")) { _, _ -> throw CancellationException() }
        }
    }
}
