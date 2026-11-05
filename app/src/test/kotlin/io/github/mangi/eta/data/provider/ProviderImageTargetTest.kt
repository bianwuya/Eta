package io.github.mangi.eta.data.provider

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ProviderImageTargetTest {
    @Test fun codexImageGenerationUsesOnlyTheExactOfficialTarget() {
        val profile = ProviderConnectionProfile.OPENAI_CODEX
        assertEquals("https://chatgpt.com/backend-api/codex/images/generations", profile.imageGenerationUrl)
        profile.requireRequestTarget(profile.imageGenerationUrl!!.toHttpUrl())
        for (url in listOf("https://chatgpt.com.evil.invalid/backend-api/codex/images/generations",
            "http://chatgpt.com/backend-api/codex/images/generations",
            "https://chatgpt.com/backend-api/codex/images/generations?redirect=fixture",
            "https://chatgpt.com/backend-api/codex/images/edits",
            "https://fixture.invalid/images/generations")) {
            assertTrue(url, runCatching { profile.requireRequestTarget(url.toHttpUrl()) }.isFailure)
        }
    }
    @Test fun otherSubscriptionProfilesDoNotGainImageCredentialTargets() {
        val profile = ProviderConnectionProfile.XAI_GROK
        assertNull(profile.imageGenerationUrl)
        assertTrue(runCatching { profile.requireRequestTarget("${profile.baseUrl}/images/generations".toHttpUrl()) }.isFailure)
        profile.requireRequestTarget(profile.inferenceUrl.toHttpUrl())
        profile.requireRequestTarget(profile.modelsUrl.toHttpUrl())
    }
}
