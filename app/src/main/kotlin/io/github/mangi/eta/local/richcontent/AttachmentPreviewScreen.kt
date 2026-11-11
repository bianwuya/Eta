package io.github.mangi.eta.local.richcontent

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.ImageViewerDialog
import io.github.mangi.eta.ui.components.MiuixScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AttachmentPreviewScreen(reference: String, displayName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    var attempt by rememberSaveable(reference) { mutableIntStateOf(0) }
    var externalAttempt by remember(reference) { mutableIntStateOf(0) }
    var bytes by remember(reference, attempt) { mutableStateOf<ByteArray?>(null) }
    var bitmap by remember(reference, attempt) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(reference, attempt) { mutableStateOf<String?>(null) }
    var encoding by rememberSaveable(reference) { mutableStateOf<String?>(null) }
    val textFile = isTextAttachment(displayName)
    val imageFile = LocalPreviewFiles.isImage(displayName)
    LaunchedEffect(reference, attempt) {
        try {
            when {
                imageFile -> bitmap = withContext(Dispatchers.IO) {
                    val source = openAttachmentSource(context, reference).use { it.readBytesBounded(MAX_ATTACHMENT_PREVIEW_BYTES) }
                    (BitmapFactory.decodeByteArray(source, 0, source.size) ?: error("图片解码失败")).asImageBitmap()
                }
                textFile -> bytes = withContext(Dispatchers.IO) {
                    openAttachmentSource(context, reference).use { it.readBytesBounded(LocalPreviewFiles.MAX_DOCUMENT_BYTES) }
                }
                else -> {
                    openExternalAttachment(context, reference, displayName)
                    currentOnBack()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取附件" }
    }
    LaunchedEffect(externalAttempt) {
        if (externalAttempt == 0) return@LaunchedEffect
        try { openExternalAttachment(context, reference, displayName) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法打开附件" }
    }
    bitmap?.let {
        ImageViewerDialog(bitmap = it, onDismiss = onBack)
        return
    }
    val decoded by produceState<Result<AttachmentText>?>(null, bytes, encoding) {
        val source = bytes
        value = if (source == null) null else withContext(Dispatchers.Default) {
            try { Result.success(decodeAttachmentText(source, encoding)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
    }
    MiuixScaffold(title = displayName, onBack = onBack, actions = {
        TextButton(text = "其他应用", onClick = { externalAttempt++ })
    }) { padding, scrollBehavior, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (textFile && bytes != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = sidePadding)) {
                    items(listOf("自动" to null, "UTF-8" to "UTF-8", "GB18030" to "GB18030", "UTF-16LE" to "UTF-16LE", "UTF-16BE" to "UTF-16BE")) { option ->
                        TextButton(text = if (encoding == option.second) "✓ ${option.first}" else option.first, onClick = { encoding = option.second })
                    }
                }
            }
            val problem = error ?: decoded?.exceptionOrNull()?.let {
                if (it is java.nio.charset.CharacterCodingException) "无法用所选编码读取文件，请切换编码或使用其他应用打开"
                else it.message ?: "无法读取附件"
            }
            when {
                problem != null -> {
                    BasicComponent(title = "无法预览", summary = problem)
                    TextButton(text = "重试", onClick = { attempt++ })
                }
                decoded?.isSuccess == true -> {
                    val document = requireNotNull(decoded).getOrThrow()
                    Text(text = "${document.encoding} · 只读预览", style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = sidePadding, vertical = 8.dp))
                    val chunks = remember(document.text) { document.text.chunked(4000).ifEmpty { listOf("（空文件）") } }
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, bottom = 24.dp)) {
                            items(chunks) { Text(text = it, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
                else -> Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
            }
        }
    }
}
