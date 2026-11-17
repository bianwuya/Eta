package io.github.mangi.eta.local.richcontent

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val FILE_ANNOTATION = "eta.local-file"
internal const val IMAGE_ANNOTATION = "eta.local-image"

/** 缩略图长边上限；与卡片限高配合，竖长图不会因为缩放过小而不是看不清。 */
private const val IMAGE_PREVIEW_MAX_PIXELS = 1024

/** 图片卡片最多占一屏的一部分，超长图由全屏预览承载。 */
private val ImageCardMaxHeight = 360.dp

@Composable
internal fun LocalOutputCards(text: AnnotatedString) {
    val images = text.getStringAnnotations(IMAGE_ANNOTATION, 0, text.length).map { it.item }.toSet()
    val refs = (text.getStringAnnotations(FILE_ANNOTATION, 0, text.length).map { it.item } + images).distinct()
    refs.forEach { reference ->
        key(reference) {
            // 图片以预览为主体，文件名降为次级信息；普通文件仍用一行紧凑文件项。
            if (reference in images) LocalImageCard(reference) else LocalFileCard(reference)
        }
    }
}

@Composable
private fun LocalFileCard(reference: String) {
    val open = LocalRichPreview.current
    // 普通文件保持一行紧凑文件项，不占图片的位置。
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        onClick = { open(reference) },
    ) {
        BasicComponent(
            title = percentName(reference),
            summary = "在浏览器中查看 HTML · 本地文件",
        )
    }
}

/**
 * 图片卡片的三种终态都必须可见：加载中给出占位，解码或读取失败给出原因与重试入口，
 * 成功时按原图比例占位。失败不再被吞成空值，否则离线历史里就只剩一个空壳。
 */
@Composable
private fun LocalImageCard(reference: String) {
    val open = LocalRichPreview.current
    val context = LocalContext.current
    var attempt by remember(reference) { mutableIntStateOf(0) }
    val state by produceState<ImageCardState>(ImageCardState.Loading, reference, attempt) {
        value = ImageCardState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                ImageCardState.Ready(decodePreviewImage(context, reference, IMAGE_PREVIEW_MAX_PIXELS))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImageCardState.Failed(imageFailureReason(e))
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        insideMargin = PaddingValues(10.dp),
    ) {
        when (val current = state) {
            is ImageCardState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is ImageCardState.Ready -> ImageThumbnail(current.bitmap, onClick = { open(reference) })
            is ImageCardState.Failed -> {
                Text(
                    text = "图片无法显示：${current.reason}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                )
                TextButton(text = "重试", onClick = { attempt++ })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = percentName(reference),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private sealed interface ImageCardState {
    data object Loading : ImageCardState
    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : ImageCardState
    data class Failed(val reason: String) : ImageCardState
}

/** 读取或解码失败的真实原因：优先使用具体消息，不把异常统一描述成“加载失败”。 */
internal fun imageFailureReason(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: "无法读取本地图片"

/**
 * 预览盒子的尺寸与是否裁切。
 *
 * 预览始终占满可用宽度：把竖长图等比缩进一个小盒子里，就是“卡片外壳大、图片内容小”的成因。
 * 横图按宽度等比降低高度并完整显示；竖长图按高度上限裁切显示顶部，完整长图由点按后的全屏预览承载。
 */
internal data class ImagePreviewBox(val widthDp: Float, val heightDp: Float, val cropped: Boolean)

internal fun fittedImageBox(aspectRatio: Float, maxWidth: Float, maxHeight: Float): ImagePreviewBox {
    if (maxWidth <= 0f || maxHeight <= 0f) return ImagePreviewBox(0f, 0f, false)
    if (!aspectRatio.isFinite() || aspectRatio <= 0f) return ImagePreviewBox(maxWidth, minOf(maxWidth, maxHeight), false)
    val natural = maxWidth / aspectRatio
    return if (natural > maxHeight) ImagePreviewBox(maxWidth, maxHeight, true)
    else ImagePreviewBox(maxWidth, natural, false)
}

@Composable
private fun ImageThumbnail(bitmap: androidx.compose.ui.graphics.ImageBitmap, onClick: () -> Unit) {
    val height = bitmap.height
    val ratio = if (height > 0) bitmap.width.toFloat() / height else 0f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val box = fittedImageBox(ratio, maxWidth.value, ImageCardMaxHeight.value)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap,
                // 被裁切的长图在点开全屏后能看到完整内容，所以提示文案区分这两种情况。
                contentDescription = if (box.cropped) "本地长图预览已限高，点击查看完整图片" else "本地图片预览，点击查看大图",
                contentScale = if (box.cropped) ContentScale.Crop else ContentScale.Fit,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .size(box.widthDp.dp, box.heightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "查看大图", onClick = onClick),
            )
        }
    }
}
