package io.github.mangi.eta.local.codexusage

import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.local.imagegen.*
import io.github.mangi.eta.officialpreview.ProductionUiHarness
import io.github.mangi.eta.testing.CodexUsageFixture
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36], qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionEntryRegressionTest(private val style: AppearanceUiStyle, private val dark: Boolean) {
    companion object {
        @JvmStatic @Parameters(name = "{0}-dark={1}") fun styles() = AppearanceUiStyle.entries.flatMap { style ->
            listOf(false, true).map { dark -> arrayOf<Any>(style, dark) }
        }
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @org.junit.Before fun freshProviderObservation() {
        // Room fixtures can replace the database while the process-level observer retains replay.
        ProviderRepository.resetObservationForTests()
    }

    @org.junit.After fun closeProviderObservation() {
        ProviderRepository.resetObservationForTests()
    }

    @Test fun actualChatProviderPageShowsUsageAndResetsPinnedAccount() {
        val id = "entry-chat-${style.name}-$dark"
        runBlocking { ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(id = id, name = "Codex fixture",
            baseUrl = "https://api.openai.com/v1", authMode = ProviderAuthModes.OPENAI_CHATGPT)) }
        CodexUsageFixture(context, id).use { accounts ->
            withService { requests ->
                ProductionUiHarness(style, dark) { ModelProviderDetailScreen(providerId = id, onBack = {}) }.use { ui ->
                    ui.await("chat usage wired") { ui.texts().any { it.contains("60.0%") } }
                    reset(ui, requests, "chat")
                    assertEquals("workspace-first", requests.single { it.method == "POST" }.header("ChatGPT-Account-Id"))
                }
            }
        }
    }

    @Test fun actualImagePageBindsLoginResultThenPersistsAndResetsItsAccount() {
        val profile = ImageGenConfig(profileId = "entry-image-${style.name}-$dark", source = ImageGenSource.CODEX_SUBSCRIPTION,
            name = "Image fixture", model = "gpt-image-2")
        ImageGenConfigStore.write(context, profile)
        CodexUsageFixture(context, profile.credentialPoolId).use { accounts ->
            withService { requests ->
                ProductionUiHarness(style, dark) { ImageGenSettingsScreen(onBack = {}) }.use { ui ->
                    visible(ui, "添加独立 Codex 账号"); ui.click("添加独立 Codex 账号")
                    ui.scrollTo("剩余额度")
                    ui.await("login result binds fixed account") { ui.texts().any { it.contains("60.0%") } }
                    reset(ui, requests, "image")
                    assertEquals("workspace-second", requests.single { it.method == "POST" }.header("ChatGPT-Account-Id"))
                    visible(ui, "保存连接"); ui.click("保存连接")
                    ui.await("fixed image account saved") { ImageGenConfigStore.read(context).accountId == accounts.second }
                    assertNull(ImageGenConfigStore.read(context).validate())
                }
            }
        }
    }

    // Native Robolectric lacks MeshSpecification. Replace only the decorative COUI shadow,
    // retaining the actual menu, selection callback, repository and page persistence.
    @Test @Config(shadows = [HostCouiShadow::class])
    fun actualChatPageSwitchesFromApiKeyToSubscriptionAndPersistsTheSelection() {
        val id = "entry-switch-${style.name}-$dark"
        runBlocking { ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(id = id, name = "Switch fixture",
            sourceType = ProviderSourceTypes.OPENAI, baseUrl = "https://api.openai.com/v1")) }
        CodexUsageFixture(context, id).use {
            withService { requests ->
                ProductionUiHarness(style, dark) { ModelProviderDetailScreen(providerId = id, onBack = {}) }.use { ui ->
                    openDropdown(ui, "认证方式"); ui.click("账号登录")
                    ui.await("subscription authentication wired") { ui.texts().any { it.contains("60.0%") } }
                    val save = context.getString(io.github.mangi.eta.R.string.page_save_configuration_817af1)
                    visible(ui, save); ui.click(save)
                    ui.await("subscription selection persisted") {
                        runBlocking { ProviderRepository.providerById(id) }?.authMode == ProviderAuthModes.OPENAI_CHATGPT
                    }
                    assertFalse(requests.any { it.method == "POST" })
                }
            }
        }
    }

    @Test @Config(shadows = [HostCouiShadow::class])
    fun actualImagePageSwitchesFromApiToSubscriptionAndPersistsItsAccount() {
        val profile = ImageGenConfig(profileId = "entry-image-switch-${style.name}-$dark",
            source = ImageGenSource.OPENAI_API, name = "Image switch", endpoint = "https://fixture.invalid/v1", model = "api-image")
        ImageGenConfigStore.write(context, profile)
        CodexUsageFixture(context, profile.credentialPoolId).use { accounts ->
            withService { requests ->
                ProductionUiHarness(style, dark) { ImageGenSettingsScreen(onBack = {}) }.use { ui ->
                    openDropdown(ui, "生图来源"); ui.click("Codex 订阅")
                    visible(ui, "添加独立 Codex 账号"); ui.click("添加独立 Codex 账号")
                    visible(ui, "保存连接"); ui.click("保存连接")
                    ui.await("image subscription selection persisted") {
                        ImageGenConfigStore.read(context).let { it.source == ImageGenSource.CODEX_SUBSCRIPTION && it.accountId == accounts.second }
                    }
                    assertNull(ImageGenConfigStore.read(context).validate())
                    assertFalse(requests.any { it.method == "POST" })
                }
            }
        }
    }

    private fun reset(ui: ProductionUiHarness, requests: List<Request>, entry: String) {
        val asset = "${style.name.lowercase()}-${if (dark) "dark" else "light"}-$entry"
        val expand = ui.nodes().first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.contains("展开额度详情") == true }
        assertTrue(ui.actionNode(expand, androidx.compose.ui.semantics.SemanticsActions.OnClick)
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke())
        ui.advance(); visible(ui, "重置额度"); ui.capture("$asset-usage"); ui.click("重置额度")
        ui.await("target confirmation") { ui.texts().contains("确认重置") }
        ui.capture("$asset-confirm")
        assertFalse(requests.any { it.method == "POST" })
        ui.click("确认重置")
        ui.await("reset completed") { ui.texts().any { it.contains("重置成功") } }
        assertEquals(1, requests.count { it.method == "POST" })
        assertTrue(ui.texts().any { it.contains("primary") })
        visible(ui, "重置额度")
        ui.capture("$asset-success")
    }

    private fun visible(ui: ProductionUiHarness, text: String) {
        repeat(24) {
            val bounds = ui.find(text)?.boundsInRoot
            if (bounds != null && bounds.height > 0 && bounds.top >= 200f && bounds.bottom <= 1760f) return
            val distance = if (bounds != null && bounds.height > 0) {
                if (bounds.top < 200f) bounds.top - 250f else bounds.bottom - 1700f
            } else 350f
            val scroll = ui.nodes().first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)?.action != null &&
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) != null }
            assertTrue(scroll.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action!!.invoke(0f, distance))
            ui.advance()
        }
        ui.capture("${style.name.lowercase()}-visible-failure")
        fail("Control not visible in the production viewport: $text; bounds=${ui.find(text)?.boundsInRoot}; " +
            ui.nodes().filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)?.action != null }
                .map { it.boundsInRoot to it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) })
    }

    private fun openDropdown(ui: ProductionUiHarness, text: String) {
        ui.click(text)
    }

    private fun withService(block: (List<Request>) -> Unit) {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val oldUsage = CodexUsageRepository.httpClientProvider
        val oldReset = CodexResetRepository.httpClientProvider
        val oldRefresh = CodexResetRepository.usageRefresher
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val body = when {
                request.method == "POST" -> """{"code":"reset","windows_reset":["primary"]}"""
                request.url.encodedPath.endsWith("/usage") -> """{"rate_limit":{"primary_window":{"used_percent":40,"limit_window_seconds":18000}}}"""
                else -> """{"available_count":2,"applicable_available_count":1,"credits":[]}"""
            }
            Response.Builder().request(request).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        CodexUsageRepository.httpClientProvider = { client }; CodexResetRepository.httpClientProvider = { client }
        CodexResetRepository.usageRefresher = { ctx, p, a, c -> CodexUsageRepository.refresh(ctx, p, a, c) }
        try { block(requests) } finally {
            CodexUsageRepository.httpClientProvider = oldUsage; CodexResetRepository.httpClientProvider = oldReset
            CodexResetRepository.usageRefresher = oldRefresh
        }
    }
}

@org.robolectric.annotation.Implements(value = com.itos.coui.effect.CouiShadowRenderer::class,
    isInAndroidSdk = false)
class HostCouiShadow {
    @org.robolectric.annotation.Implementation
    fun __constructor__(capacity: Int) = Unit
    @org.robolectric.annotation.Implementation
    fun draw(canvas: android.graphics.Canvas, slot: Int, x: Float, y: Float, width: Float, height: Float,
        radius: Float, spread: Float, blur: Float, color: Int, alpha: Float, scale: Float, offsetX: Float,
        offsetY: Float, outline: com.itos.coui.effect.CouiShadowOutline, clip: Float, enabled: Boolean) = Unit
}
