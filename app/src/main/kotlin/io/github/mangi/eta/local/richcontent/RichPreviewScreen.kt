package io.github.mangi.eta.local.richcontent

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.ImageViewerDialog
import io.github.mangi.eta.ui.components.MiuixScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*

internal val LocalRichPreview = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
internal fun RichPreviewScreen(reference: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var generation by rememberSaveable(reference) { mutableIntStateOf(0) }
    var launchError by remember(reference, generation) { mutableStateOf<String?>(null) }
    var imageBitmap by remember(reference, generation) { mutableStateOf<ImageBitmap?>(null) }
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(reference, generation) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                val mime = if (reference.startsWith("content://")) context.contentResolver.getType(android.net.Uri.parse(reference)) else null
                if (LocalPreviewFiles.isImage(reference) || mime?.startsWith("image/") == true) {
                    decodePreviewImage(context, reference)
                } else {
                    require(mime == null || mime in setOf("text/html", "application/xhtml+xml", "text/plain")) { "不支持的附件类型" }
                    null
                }
            }
            if (bitmap != null) imageBitmap = bitmap
            else {
                openExternalHtmlPreview(context, reference)
                currentOnBack()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { launchError = e.message ?: "无法打开文件" }
    }
    imageBitmap?.let { bitmap ->
        ImageViewerDialog(bitmap = bitmap, onDismiss = onBack)
        return
    }
    MiuixScaffold(title = if (LocalPreviewFiles.isImage(reference)) "图片预览" else "浏览器查看", onBack = onBack, actions = {
        IconButton(onClick = { generation++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "重试打开") }
    }) { padding, _, _ ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val error = launchError
            if (error != null) BasicComponent(title = "无法打开", summary = error)
            else Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
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
