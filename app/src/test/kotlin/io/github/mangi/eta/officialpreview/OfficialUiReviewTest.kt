package io.github.mangi.eta.officialpreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Group
import com.itos.coui.layout.Footer
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.components.coui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import java.io.File
import java.time.Duration

/** Isolated review composition. Never referenced by the application or backed by real settings. */
private enum class ReviewPage { IMAGE, SUBAGENT, DETAIL }
private enum class ReviewState { READY, LOADING, ERROR, UNAVAILABLE, RECOVERED }

@Composable
private fun ReviewGroup(title: String, content: @Composable () -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Group(title = title) { content() }
    else { SmallTitle(title); Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { content() } }
}

@Composable
private fun ReviewRow(title: String, summary: String = "", onClick: (() -> Unit)? = null) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiPreference(title = title, summary = summary, onClick = onClick)
    else BasicComponent(title = title, summary = summary, onClick = onClick)
}

@Composable
private fun ReviewNote(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Footer(text = text)
    else Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
}

@Composable
private fun ReviewField(label: String, value: String, secret: Boolean = false) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiTextField(value = value, onValueChange = {}, label = label,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
    else TextField(value = value, onValueChange = {}, label = label, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReviewAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiButton(text, onClick,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = enabled, role = CouiButtonRole.Primary)
    else TextButton(text, onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = enabled, colors = ButtonDefaults.textButtonColorsPrimary())
}

@Composable
private fun ReviewContent(page: ReviewPage, state: ReviewState, advanced: Boolean,
    orderReversed: Boolean, openDialog: () -> Unit) {
    when (page) {
        ReviewPage.IMAGE -> {
            ReviewGroup("独立生图连接") {
                ReviewRow("OpenAI Images 兼容协议", "独立于聊天模型与账号；请求只执行一次")
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReviewField("中转 URL", "https://relay.example")
                    ReviewField("独立 API Key", "preview-key-hidden", secret = true)
                    ReviewField("生图模型", "image-model")
                }
            }
            ReviewNote("地址支持根 URL、/v1 或完整 Images endpoint，会自动补齐生成路径。")
            ReviewGroup("高级设置") {
                ReviewRow("完整 endpoint 覆盖", if (advanced) "仅特殊中转路径需要填写" else "默认自动生成", openDialog)
                if (advanced) Column(Modifier.padding(16.dp)) { ReviewField("完整 endpoint", "https://relay.example/custom/images") }
            }
            val message = when (state) {
                ReviewState.READY -> "配置就绪。保存不会发起生图请求。"
                ReviewState.LOADING -> "正在保存独立连接…"
                ReviewState.ERROR -> "认证失败（401）。请检查此连接的 Key；未重试或切换账号。"
                ReviewState.UNAVAILABLE -> "尚未填写 Key。此连接尚未启用。"
                ReviewState.RECOVERED -> "连接已保存。生图按本页模型执行。"
            }
            ReviewNote(message)
            ReviewAction("保存连接", openDialog, state != ReviewState.LOADING)
        }
        ReviewPage.SUBAGENT -> {
            ReviewGroup("运行模型与统计") {
                ReviewRow("当前模型", when (state) {
                    ReviewState.ERROR -> "model-low · 从 model-high 降级"
                    ReviewState.UNAVAILABLE -> "暂无可用模型"
                    else -> "model-high"
                })
                ReviewRow("汇总用量", "输入 128,000 · 输出 3,200 · 缓存 96,000")
                ReviewRow("缓存命中率", "75% · 按服务端实际返回统计；缺失显示未知")
            }
            ReviewGroup("模型优先级") {
                val names = if (orderReversed) listOf("model-low", "model-high") else listOf("model-high", "model-low")
                names.forEachIndexed { i, name -> ReviewRow("${i + 1}  $name", when {
                    state == ReviewState.UNAVAILABLE -> "暂不可用 · 保留此顺序"
                    state == ReviewState.ERROR && i == 0 -> "额度不足 · 本次已跳过"
                    state == ReviewState.ERROR -> "正在使用 · 工具能力可用"
                    i == 0 -> "优先启动 · 同账号 · 工具能力可用"
                    else -> "下一级 · 明确不可用时启用"
                }, openDialog) }
            }
            ReviewNote("长按调整顺序；也可使用上移/下移。已开始的任务保留原模型与断点。")
            ReviewGroup("上下文与提示词") {
                ReviewRow("压缩模型", "独立配置 · 完整历史单次摘要", openDialog)
                ReviewRow("子代理提示词", "复用现有 profile 编辑器", openDialog)
            }
            ReviewNote(when (state) {
                ReviewState.ERROR -> "model-high 额度不足 → model-low。缓存重新开始，历史费用分开记录。"
                ReviewState.UNAVAILABLE -> "没有兼容的候选模型。原任务保留，未跨账号重跑。"
                ReviewState.LOADING -> "正在读取原统计记录…"
                ReviewState.RECOVERED -> "已恢复原实例，保留已完成工具结果。"
                else -> "统计与单实例明细共用同一份记录。"
            })
        }
        ReviewPage.DETAIL -> {
            ReviewGroup("子代理 · 检查代码") {
                ReviewRow("当前模型", if (state == ReviewState.ERROR) "model-low · 从 model-high 降级" else "model-high")
                ReviewRow("状态", if (state == ReviewState.UNAVAILABLE) "结果未知，等待核实" else if (state == ReviewState.RECOVERED) "原实例已恢复" else "正在执行")
                ReviewRow("本实例用量", "输入 32,000 · 输出 800 · 缓存未知")
            }
            ReviewGroup("执行记录") {
                ReviewRow("已完成：读取文件", "检查点已保存，恢复时不再执行")
                ReviewRow("当前：模型请求", "临时断流沿原请求最多重试两次")
                ReviewRow("待处理：汇总结果", "结束后恰好一次返回主代理")
            }
            ReviewNote("未知工具副作用时停止自动重放。手动确认后再决定下一步。")
            ReviewAction("查看完整记录", openDialog)
        }
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun ReviewScreen(page: ReviewPage, state: ReviewState = ReviewState.READY,
    advanced: Boolean = false, reversed: Boolean = false, dialog: Boolean = false,
    onDialog: () -> Unit = {}) {
    val title = when (page) { ReviewPage.IMAGE -> "生图配置"; ReviewPage.SUBAGENT -> "子代理设置"; ReviewPage.DETAIL -> "子代理详情" }
    val overlay: @Composable () -> Unit = {
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiConfirmDialog(visible = dialog, title = "完整 endpoint",
            message = "仅特殊路径需要覆盖；保留当前独立连接。", confirmText = "保存", onConfirm = {}, onDismissRequest = {},
            content = { ReviewField("完整 endpoint", "https://relay.example/custom/images") })
        else WindowDialog(show = dialog, title = "完整 endpoint", onDismissRequest = {}) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ReviewField("完整 endpoint", "https://relay.example/custom/images")
                MiuixDialogActions("保存", onCancel = {}, onConfirm = {})
            }
        }
    }
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiScaffoldPage(title, onBack = {}, overlay = overlay) {
        ReviewContent(page, state, advanced, reversed, onDialog)
    } else MiuixScaffoldPage(title, onBack = {}, overlay = overlay) {
        item { ReviewContent(page, state, advanced, reversed, onDialog) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfficialUiReviewTest {
    private val output = File(System.getProperty("eta.uiReview.output")
        ?: System.getenv("ETA_UI_REVIEW_OUTPUT") ?: "build/official-preview/v1")

    private fun render(name: String, style: AppearanceUiStyle, dark: Boolean, fontScale: Float = 1f,
        page: ReviewPage, state: ReviewState = ReviewState.READY, advanced: Boolean = false, reversed: Boolean = false,
        motion: Boolean = false, interaction: String = "", width: Int = 720, height: Int = 1800) {
        output.mkdirs()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        activity.resources.configuration.fontScale = fontScale
        val frames = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames)
        val recomposer = Recomposer(scope.coroutineContext)
        val host = ComposeView(activity)
        val showDialog = mutableStateOf(false)
        val changed = mutableStateOf(false)
        lateinit var push: () -> Unit
        lateinit var pop: () -> Unit
        try {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            host.setContent {
                AgentAppTheme(AppearanceSettings(uiStyle = style, blurEnabled = false,
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false, LocalCouiContourGlowEnabled provides false) {
                        if (interaction == "navigation") {
                            val stack = rememberNavBackStack<AppRoute>(AppRoute.Settings)
                            push = { stack.add(AppRoute.ImageGenSettings) }
                            pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                            NavDisplay(backStack = stack, onBack = pop, effects = NavDisplayEffects()) {
                                entry<AppRoute.Settings> { ReviewScreen(ReviewPage.SUBAGENT) }
                                entry<AppRoute.ImageGenSettings> { ReviewScreen(ReviewPage.IMAGE) }
                            }
                        } else if (interaction == "reorder") {
                            val names = if (changed.value) listOf("model-low", "model-high") else listOf("model-high", "model-low")
                            MiuixScaffoldPage("模型优先级", onBack = {}) {
                                item { ReviewNote("同一账号内排序；下方演示复用官方列表 animateItem") }
                                items(names, key = { it }) { model ->
                                    Box(Modifier.animateItem()) { ReviewGroup(model) { ReviewRow(model, "同账号 · 工具能力可用") } }
                                }
                            }
                        } else ReviewScreen(page,
                            if (interaction == "status" && changed.value) ReviewState.ERROR else state,
                            advanced, reversed, showDialog.value) { showDialog.value = true }
                    }
                }
            }
            activity.setContentView(host)
            fun frame() {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, width, height)
            }
            fun capture(suffix: String) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                host.draw(canvas)
                android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it !== activity.window.decorView }
                    .forEach { window ->
                        if (window.width > 0 && window.height > 0) {
                            val x = ((width - window.width) / 2).coerceAtLeast(0)
                            val y = if (style == AppearanceUiStyle.COUI) (height - window.height).coerceAtLeast(0)
                                else ((height - window.height) / 2).coerceAtLeast(0)
                            canvas.save(); canvas.translate(x.toFloat(), y.toFloat()); window.draw(canvas); canvas.restore()
                        }
                    }
                File(output, "$name$suffix.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            repeat(35) { frame() }
            capture("")
            if (motion) {
                showDialog.value = true
                frame(); capture("-dialog-start")
                repeat(7) { frame() }; capture("-dialog-mid")
                repeat(35) { frame() }; capture("-dialog-end")
                showDialog.value = false
                repeat(35) { frame() }; capture("-dialog-return")
            }
            if (interaction.isNotBlank()) {
                if (interaction == "navigation") push() else changed.value = true
                frame(); capture("-start")
                repeat(7) { frame() }; capture("-mid")
                repeat(35) { frame() }; capture("-end")
                if (interaction == "navigation") pop() else changed.value = false
                frame(); capture("-back-start")
                repeat(7) { frame() }; capture("-back-mid")
                repeat(35) { frame() }; capture("-return")
            }
            assertTrue(File(output, "$name.png").length() > 1000)
        } finally {
            host.disposeComposition(); recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy()
        }
    }

    @Test fun renderOneReviewPackageFromRealComponents() {
        for (style in AppearanceUiStyle.entries) for (dark in listOf(false, true)) {
            val name = "${style.name.lowercase()}-${if (dark) "dark" else "light"}"
            render("$name-image", style, dark, page = ReviewPage.IMAGE, motion = true)
            render("$name-subagent", style, dark, page = ReviewPage.SUBAGENT)
            render("$name-detail", style, dark, page = ReviewPage.DETAIL, state = ReviewState.RECOVERED)
        }
        render("coui-light-image-error", AppearanceUiStyle.COUI, false, page = ReviewPage.IMAGE, state = ReviewState.ERROR)
        render("coui-light-image-loading", AppearanceUiStyle.COUI, false, page = ReviewPage.IMAGE, state = ReviewState.LOADING)
        render("miuix-dark-subagent-unavailable", AppearanceUiStyle.MIUIX, true, page = ReviewPage.SUBAGENT, state = ReviewState.UNAVAILABLE)
        render("coui-light-subagent-downgrade", AppearanceUiStyle.COUI, false, page = ReviewPage.SUBAGENT, state = ReviewState.ERROR)
        render("coui-light-subagent-reordered", AppearanceUiStyle.COUI, false, page = ReviewPage.SUBAGENT, reversed = true)
        render("coui-light-image-large-text", AppearanceUiStyle.COUI, false, fontScale = 1.3f, page = ReviewPage.IMAGE, advanced = true)
        render("coui-light-image-narrow", AppearanceUiStyle.COUI, false, page = ReviewPage.IMAGE, width = 640, height = 1600)
        render("miuix-light-image-landscape", AppearanceUiStyle.MIUIX, false, page = ReviewPage.IMAGE, width = 1600, height = 800)
        render("miuix-light-navigation", AppearanceUiStyle.MIUIX, false, page = ReviewPage.IMAGE, interaction = "navigation")
        render("miuix-light-reorder", AppearanceUiStyle.MIUIX, false, page = ReviewPage.SUBAGENT, interaction = "reorder")
        render("coui-light-failover", AppearanceUiStyle.COUI, false, page = ReviewPage.SUBAGENT, interaction = "status")
    }
}
