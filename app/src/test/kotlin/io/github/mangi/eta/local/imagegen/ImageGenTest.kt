package io.github.mangi.eta.local.imagegen

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageGenTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val settings = ImageGenConfig(ImageGenSource.CODEX_SUBSCRIPTION, "fixed-provider", "fixed-account")

    @Test fun configurationRequiresExplicitBillingSourceAndAccount() {
        assertNotNull(ImageGenConfig().validate())
        assertNotNull(settings.copy(accountId = "").validate())
        assertNotNull(settings.copy(model = "chat-model").validate())
        assertNotNull(settings.copy(size = "999999x1").validate())
        assertNull(settings.validate())
        assertNull(settings.copy(source = ImageGenSource.OPENAI_API, accountId = "", model = "gpt-image-1").validate())
    }
    @Test fun requestUsesOnlyFixedConfigurationAndOneImage() {
        val body = ImageGenClient.requestBody("真实图片", settings)
        assertEquals("gpt-image-2", body.getString("model"))
        assertEquals(1, body.getInt("n"))
        assertFalse(body.has("response_format"))
        assertFalse(body.has("accountId"))
        assertFalse(body.has("apiKey"))
        assertEquals("真实图片", body.getString("prompt"))
    }
    @Test fun duplicateCallNeverGeneratesTwiceAndReturnsNoBase64() {
        ImageGenConfigStore.write(context, settings)
        var count = 0
        val tool = ImageGenTool(context, { AgentRunController() }, { _, _, actual ->
            count++; assertEquals(settings, actual)
            JSONObject().put("ok", true).put("path", "/sdcard/Download/fixture.png")
        })
        val call = AgentModelClient.ToolCall("same-call", "generate_image", "{\"prompt\":\"画树\"}")
        assertEquals(tool.execute(call), tool.execute(call))
        assertEquals(1, count)
        assertTrue(tool.execute(call).images.isEmpty())
        assertFalse(tool.execute(call).content.contains("b64_json"))
    }
    @Test fun uncertainOutcomeAlsoCannotBeAutomaticallyRepeated() {
        ImageGenConfigStore.write(context, settings)
        var count = 0
        val tool = ImageGenTool(context, { AgentRunController() }, { _, _, _ -> count++; throw java.io.IOException() })
        val call = AgentModelClient.ToolCall("unknown", "generate_image", "{\"prompt\":\"画树\"}")
        val first = tool.execute(call)
        assertFalse(JSONObject(first.content).getBoolean("retryable"))
        assertTrue(JSONObject(first.content).getBoolean("outcome_unknown"))
        assertEquals(first, tool.execute(call))
        assertEquals(1, count)
    }
    @Test fun configStoreRoundTripsFixedAccountWithoutFollowingChatSelection() {
        ImageGenConfigStore.write(context, settings)
        assertEquals(settings, ImageGenConfigStore.read(context))
    }
    @Test fun invalidAndCancelledImagesLeaveNoPartialFiles() {
        val directory = File(context.cacheDir, "imagegen-test-${System.nanoTime()}")
        try {
            assertTrue(runCatching { ImageGenOutput.save("bm90IGFuIGltYWdl", AgentRunController(), directory) }.isFailure)
            val cancelled = AgentRunController().apply { cancel() }
            assertTrue(runCatching { ImageGenOutput.save("eA==", cancelled, directory) }.isFailure)
            assertTrue(directory.listFiles().isNullOrEmpty())
        } finally { directory.deleteRecursively() }
    }
}
