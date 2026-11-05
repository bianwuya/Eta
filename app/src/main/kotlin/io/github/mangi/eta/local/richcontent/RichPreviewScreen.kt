package io.github.mangi.eta.local.richcontent

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.ui.components.MiuixScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalRichPreview = staticCompositionLocalOf<(String) -> Unit> { {} }

private data class PreviewDocument(val html: String? = null, val bitmap: ImageBitmap? = null, val error: String? = null)

@Composable
internal fun RichPreviewScreen(reference: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generation by remember { mutableIntStateOf(0) }
    var showSource by rememberSaveable(reference) { mutableStateOf(false) }
    var scripts by rememberSaveable(reference) { mutableStateOf(false) }
    var blocked by remember(generation, scripts) { mutableStateOf(false) }
    var rendererFailed by remember(generation, scripts) { mutableStateOf(false) }
    val document by produceState<PreviewDocument?>(null, reference, generation) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                val mime = if (reference.startsWith("content://")) context.contentResolver.getType(android.net.Uri.parse(reference)) else null
                if (LocalPreviewFiles.isImage(reference) || mime?.startsWith("image/") == true) {
                    PreviewDocument(bitmap = decodePreviewImage(context, reference))
                } else {
                    require(mime == null || mime in setOf("text/html", "application/xhtml+xml", "text/plain")) { "不支持的附件类型" }
                    PreviewDocument(html = LocalPreviewFiles.text(context, reference))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { PreviewDocument(error = e.message ?: "无法读取文件") }
        }
    }
    val image = document?.bitmap != null || LocalPreviewFiles.isImage(reference)
    val source = document?.html
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null && source != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(source.toByteArray()) } ?: error("无法保存") }.isSuccess
            }
            Toast.makeText(context, if (ok) "HTML 已保存" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
    val title = if (image) "图片预览" else "HTML 预览"
    MiuixScaffold(title = title, onBack = onBack, actions = {
        if (!image && source != null) {
            IconButton(onClick = { showSource = !showSource }, modifier = Modifier.semantics { contentDescription = "预览或源码" }) {
                Icon(Icons.Rounded.Code, contentDescription = "预览或源码")
            }
            IconButton(onClick = { save.launch("Eta-page.html") }) { Icon(Icons.Rounded.Download, contentDescription = "另存 HTML") }
        }
        IconButton(onClick = { generation++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "重新加载") }
    }) { padding, _, _ ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val error = document?.error
            when {
                error != null -> BasicComponent(title = "无法预览", summary = "$error。请确认文件仍存在且已授予访问权限。")
                document == null -> Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
                image -> PreviewImage(requireNotNull(document?.bitmap))
                showSource -> SelectionContainer {
                    val chunks = remember(source) { source.orEmpty().chunked(4000) }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(chunks) { chunk -> Text(chunk, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth()) }
                    }
                }
                rendererFailed -> BasicComponent(title = "网页渲染已停止", summary = "可以查看源码或点击刷新重试。")
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (scripts) "交互预览 · 禁止联网" else "静态预览 · 禁止联网", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.weight(1f).padding(8.dp))
                        TextButton(text = if (scripts) "关闭脚本" else "启用交互", onClick = { scripts = !scripts })
                    }
                    if (blocked) Text("部分外链或资源已拦截；页面不会自动联网或调用手机工具。", style = MiuixTheme.textStyles.footnote2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                    val html by produceState<String?>(null, source) {
                        value = withContext(Dispatchers.Default) { previewHtml(source.orEmpty()) }
                    }
                    if (html != null) key(reference, generation, scripts, html) {
                        AndroidView(
                            factory = { ctx -> RichWebView(ctx, scripts, onBlocked = { blocked = true }, onFailed = { rendererFailed = true }).apply {
                                resourceRoot = if (reference.startsWith("content://")) null else runCatching { LocalPreviewFiles.file(ctx, reference).parentFile }.getOrNull()
                                show(requireNotNull(html))
                            } },
                            onRelease = { it.release() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

internal fun decodePreviewImage(context: android.content.Context, reference: String, maxPixels: Int = 2048): ImageBitmap {
    val bytes = LocalPreviewFiles.open(context, reference).use { it.readBytesBounded(LocalPreviewFiles.MAX_IMAGE_BYTES) }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "不支持的图片或文件损坏" }
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxPixels) options.inSampleSize *= 2
    return (BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片解码失败")).asImageBitmap()
}

@Composable
private fun PreviewImage(bitmap: ImageBitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().clipToBounds().transformable(transform)) {
        Image(bitmap, contentDescription = "本地图片，可双指缩放", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}
