package io.github.mangi.eta.local.richcontent

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val MATH_ANNOTATION = "eta.math"
internal const val FILE_ANNOTATION = "eta.local-file"
internal const val IMAGE_ANNOTATION = "eta.local-image"

@Composable
internal fun LocalOutputCards(text: AnnotatedString) {
    val open = LocalRichPreview.current
    val context = LocalContext.current
    val images = text.getStringAnnotations(IMAGE_ANNOTATION, 0, text.length).map { it.item }.toSet()
    val refs = (text.getStringAnnotations(FILE_ANNOTATION, 0, text.length).map { it.item } + images).distinct()
    refs.forEach { reference ->
        key(reference) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { open(reference) }) {
                if (reference in images) {
                    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, reference) {
                        value = withContext(Dispatchers.IO) {
                            try { decodePreviewImage(context, reference, 640) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        }
                    }
                    bitmap?.let { Image(it, contentDescription = "本地图片缩略图，点击查看", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                }
                BasicComponent(title = reference.substringAfterLast('/'), summary = "点击${if (reference in images) "查看图片" else "预览 HTML"} · 本地文件", onClick = { open(reference) })
            }
        }
    }
}

/** Only completed mathematical paragraphs use WebView. Plain chat never initializes this component. */
@Composable
internal fun MathParagraph(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, display: Boolean = false) {
    val density = LocalDensity.current
    val color = style.color.takeIf { it != Color.Unspecified } ?: MiuixTheme.colorScheme.onSurface
    // CSS pixels track interface scaling and font scaling, not physical screen pixels.
    val fontSize = style.fontSize.value * density.fontScale
    val lineHeight = style.lineHeight.value.takeIf { it.isFinite() }?.times(density.fontScale) ?: fontSize * 1.6f
    val document = remember(text, fontSize, lineHeight, color, display) { mathDocument(text, fontSize, lineHeight, color, display) }
    var failed by remember(document) { mutableStateOf(false) }
    var heightPx by remember(document) { mutableIntStateOf(with(density) { (if (display) 64.dp else 32.dp).roundToPx() }) }
    if (failed) {
        Text(text = text, style = style, modifier = modifier)
        return
    }
    val viewDensity = LocalContext.current.resources.displayMetrics.density
    val height = with(density) { heightPx.toDp() }.coerceIn(24.dp, 4096.dp)
    var visible by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val host = androidx.compose.ui.platform.LocalView.current
    Box(modifier.fillMaxWidth().height(height).onGloballyPositioned { coordinates ->
        val y = coordinates.positionInWindow().y
        val frame = android.graphics.Rect().also { host.getWindowVisibleDisplayFrame(it) }
        visible = y + coordinates.size.height > frame.top && y < frame.bottom
    }) {
        // A long historical message must not allocate a renderer for every offscreen formula.
        if (visible) key(document) {
            AndroidView(
                factory = { ctx -> RichWebView(ctx, scripts = true, onFailed = { failed = true },
                    onUserLink = { uri -> runCatching { uriHandler.openUri(uri.toString()) } }).apply {
                    settings.textZoom = (100 * density.density / viewDensity).toInt().coerceIn(50, 300)
                    onHeightChanged = { heightPx = it }
                    show(document)
                } },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal fun htmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

internal fun mathDocument(text: AnnotatedString, fontSize: Float, lineHeight: Float, color: Color, display: Boolean): String {
    val maths = text.getStringAnnotations(MATH_ANNOTATION, 0, text.length)
    val links = text.getLinkAnnotations(0, text.length)
    val ranges = sortedSetOf(0, text.length)
    links.forEach { ranges.add(it.start); ranges.add(it.end) }
    maths.forEach { ranges.add(it.start); ranges.add(it.end) }
    text.spanStyles.forEach { ranges.add(it.start); ranges.add(it.end) }
    val points = ranges.toList()
    val body = if (display) "<span data-math data-display=\"true\">${htmlEscape(text.text)}</span>" else buildString {
        var skipTo = -1
        for (i in 0 until points.lastIndex) {
            val start = points[i]; val end = points[i + 1]
            if (start < skipTo || end <= start) continue
            val math = maths.firstOrNull { it.start == start }
            if (math != null) {
                append("<span data-math>${htmlEscape(text.text.substring(math.start, math.end))}</span>")
                skipTo = math.end
            } else {
                val styles = text.spanStyles.filter { it.start <= start && it.end >= end }.map { it.item }
                val css = buildString {
                    if (styles.any { (it.fontWeight ?: FontWeight.Normal) >= FontWeight.SemiBold }) append("font-weight:600;")
                    if (styles.any { it.fontStyle == FontStyle.Italic }) append("font-style:italic;")
                    if (styles.any { it.fontFamily == FontFamily.Monospace }) append("font-family:monospace;")
                }
                val link = links.firstOrNull { it.start <= start && it.end >= end }?.item as? androidx.compose.ui.text.LinkAnnotation.Url
                if (link != null) append("<a href=\"${htmlEscape(link.url)}\">")
                append("<span style=\"$css\">${htmlEscape(text.text.substring(start, end)).replace("\n", "<br>")}</span>")
                if (link != null) append("</a>")
            }
        }
    }
    val foreground = "#%06x".format(color.toArgb() and 0xffffff)
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'">
        <link rel="stylesheet" href="/math/katex/katex.min.css">
        <style>html,body{margin:0;padding:0;background:transparent;color:$foreground;font:normal ${fontSize}px/${lineHeight}px system-ui,sans-serif;overflow-wrap:anywhere}body{padding:2px 0}.katex-display{margin:8px 0;overflow-x:auto;overflow-y:hidden;padding:4px 0}.katex{font-size:1.1em}</style>
        </head><body>$body<script src="/math/katex/katex.min.js"></script><script>
        document.querySelectorAll('[data-math]').forEach(function(el){
          var tex=el.textContent;
          try { katex.render(tex,el,{displayMode:el.hasAttribute('data-display'),throwOnError:false,trust:false,maxExpand:500,maxSize:20,output:'htmlAndMathml'}); }
          catch(e){el.textContent=tex;}
        });</script></body></html>
    """.trimIndent()
}
