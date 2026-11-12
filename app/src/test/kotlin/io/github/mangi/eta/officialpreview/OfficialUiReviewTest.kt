package io.github.mangi.eta.officialpreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.Alignment
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
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import java.io.File
import java.time.Duration

/** Isolated review composition. Never referenced by the application or backed by real settings. */
private enum class ReviewPage { IMAGE, SUBAGENT, DETAIL, MODELS }
private enum class ReviewState { READY, LOADING, ERROR, UNAVAILABLE, RECOVERED }

/** Test-only interactive candidate. Order stays in this composition, never real settings. */
@Composable
private fun ReviewPriorityRows(initialReversed: Boolean = false) {
    val names = remember { mutableStateListOf<String>().apply { addAll(if (initialReversed)
        listOf("model-low", "model-high") else listOf("model-high", "model-low")) } }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    names.toList().forEachIndexed { index, model ->
        key(model) {
            val handle = Modifier.size(48.dp).semantics {
                contentDescription = "拖动调整 $model 的优先级"
                customActions = listOf(CustomAccessibilityAction("上移") {
                    val at = names.indexOf(model)
                    if (at > 0) { names.removeAt(at); names.add(at - 1, model); true } else false
                }, CustomAccessibilityAction("下移") {
                    val at = names.indexOf(model)
                    if (at < names.lastIndex) { names.removeAt(at); names.add(at + 1, model); true } else false
                })
            }.pointerInput(model) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragged = model; offset = 0f },
                    onDragEnd = { dragged = null; offset = 0f },
                    onDragCancel = { dragged = null; offset = 0f },
                ) { change, delta ->
                    change.consume(); offset += delta.y
                    val at = names.indexOf(model)
                    val target = when { offset > rowHeight / 2 && at < names.lastIndex -> at + 1
                        offset < -rowHeight / 2 && at > 0 -> at - 1; else -> at }
                    if (at != target) { names.removeAt(at); names.add(target, model); offset -= (target - at) * rowHeight }
                }
            }
            val trailing: @Composable () -> Unit = {
                Box(handle, contentAlignment = Alignment.Center) { ReviewLabel("≡") }
            }
            val body: @Composable () -> Unit = {
                if (LocalUiStyle.current == AppearanceUiStyle.COUI)
                    CouiPreference(title = "${index + 1}  $model", summary = if (index == 0) "优先使用" else "备用模型",
                        trailing = trailing, showChevron = false)
                else BasicComponent(title = "${index + 1}  $model", summary = if (index == 0) "优先使用" else "备用模型",
                    endActions = { trailing() })
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).graphicsLayer { translationY = if (dragged == model) offset else 0f }) { body() }
        }
    }
}

@Composable private fun ReviewLabel(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiText(text = text, kind = CouiTextKind.PreferenceTitle)
    else Text(text)
}

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
                ReviewRow("OpenAI Images 兼容协议", "使用独立连接")
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReviewField("中转 URL", "https://relay.example")
                    ReviewField("独立 API Key", "preview-key-hidden", secret = true)
                }
                ReviewRow("生图模型", "image-model-a · 点按选择", openDialog)
                ReviewRow("刷新模型列表", "从此连接读取可用模型", openDialog)
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
                ReviewPriorityRows(orderReversed)
            }
            ReviewNote("长按右侧拖动柄上下拖动，松手保存顺序。已运行的子代理继续使用原模型。")
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
        ReviewPage.MODELS -> {
            Column(Modifier.padding(16.dp)) { ReviewField("搜索模型", "") }
            ReviewGroup("此连接的生图模型") {
                ReviewRow("image-model-a", "已选择")
                ReviewRow("image-model-b", "可用模型", {})
            }
            ReviewGroup("其他") { ReviewRow("手动添加模型", "目录未列出时使用", {}) }
            ReviewNote("模型列表来自此生图连接。选择后返回连接设置。")
        }
    }
    Spacer(Modifier.height(32.dp))
}

@Composable
private fun ReviewScreen(page: ReviewPage, state: ReviewState = ReviewState.READY,
    advanced: Boolean = false, reversed: Boolean = false, dialog: Boolean = false,
    onDialog: () -> Unit = {}) {
    val title = when (page) { ReviewPage.IMAGE -> "生图配置"; ReviewPage.SUBAGENT -> "子代理设置"; ReviewPage.DETAIL -> "子代理详情"; ReviewPage.MODELS -> "选择生图模型" }
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
        ?: System.getenv("ETA_UI_REVIEW_OUTPUT") ?: "build/official-preview/v2")

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
                            val deviceRadius = rememberNavSystemCornerRadius()
                            // Robolectric has no physical display corners; 32dp is a labelled fixture value.
                            NavDisplay(backStack = stack, onBack = pop,
                                effects = NavDisplayEffects(cornerClipRadius = if (deviceRadius.value > 0f) deviceRadius else 32.dp)) {
                                entry<AppRoute.Settings> { ReviewScreen(ReviewPage.SUBAGENT) }
                                entry<AppRoute.ImageGenSettings> { ReviewScreen(ReviewPage.IMAGE) }
                            }
                        } else if (interaction == "reorder") {
                            ReviewScreen(ReviewPage.SUBAGENT)
                        } else ReviewScreen(if (interaction == "models" && showDialog.value) ReviewPage.MODELS else page,
                            if (interaction == "status" && changed.value) ReviewState.ERROR else state,
                            advanced, reversed, showDialog.value && interaction != "models") { showDialog.value = true }
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
                            val layer = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
                            window.draw(Canvas(layer))
                            canvas.drawBitmap(layer, x.toFloat(), y.toFloat(), null)
                            layer.recycle()
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
                if (interaction == "navigation") push()
                else if (interaction == "reorder") {
                    fun findRoot(view: View): ViewRootForTest? {
                        if (view is ViewRootForTest) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    val all = nodes(requireNotNull(findRoot(host)).semanticsOwner.rootSemanticsNode)
                    val handle = all.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("拖动调整 model-high 的优先级") == true }.boundsInRoot.center
                    val down = SystemClock.uptimeMillis()
                    fun touch(action: Int, y: Float) {
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, handle.x, y, 0).also {
                            host.dispatchTouchEvent(it); it.recycle()
                        }
                        frame()
                    }
                    touch(MotionEvent.ACTION_DOWN, handle.y)
                    repeat(40) { frame() }
                    capture("-drag-start")
                    touch(MotionEvent.ACTION_MOVE, handle.y + 20f)
                    touch(MotionEvent.ACTION_MOVE, handle.y + 90f)
                    capture("-drag-mid")
                    touch(MotionEvent.ACTION_MOVE, handle.y + 145f)
                    touch(MotionEvent.ACTION_UP, handle.y + 145f)
                    repeat(12) { frame() }
                    val after = nodes(requireNotNull(findRoot(host)).semanticsOwner.rootSemanticsNode)
                    assertTrue("real pointer drag must reorder the list", after.any {
                        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "1  model-low" } == true
                    })
                    capture("-drag-end")
                } else if (interaction == "models") showDialog.value = true
                else changed.value = true
                frame(); capture("-start")
                repeat(7) { frame() }; capture("-mid")
                repeat(35) { frame() }; capture("-end")
                if (interaction == "navigation") pop() else { changed.value = false; showDialog.value = false }
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
        render("coui-light-models", AppearanceUiStyle.COUI, false, page = ReviewPage.IMAGE, interaction = "models")
        render("miuix-light-models", AppearanceUiStyle.MIUIX, false, page = ReviewPage.IMAGE, interaction = "models")
        render("coui-light-reorder", AppearanceUiStyle.COUI, false, page = ReviewPage.SUBAGENT, interaction = "reorder")
    }
}
