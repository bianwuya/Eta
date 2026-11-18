package io.github.mangi.eta.ui.app

import android.content.ClipData
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import io.github.mangi.eta.ui.IncomingShareRequest

/** Append to the live field before returning, without publishing each ordinary keystroke. */
internal class IncomingDraftReader {
    class Source(val conversationId: String?, val append: (String) -> String)

    var source by mutableStateOf<Source?>(null)
        private set

    fun bind(conversationId: String?, append: (String) -> String): () -> Unit {
        val current = Source(conversationId, append)
        source = current
        return { if (source === current) source = null }
    }
}

internal class IncomingDraftBinding(val reader: IncomingDraftReader, val conversationId: String?)

internal val LocalIncomingDraftBinding = staticCompositionLocalOf<IncomingDraftBinding?> { null }

@Composable
internal fun BindIncomingDraft(append: (String) -> String) {
    val binding = LocalIncomingDraftBinding.current ?: return
    val latestAppend by rememberUpdatedState(append)
    DisposableEffect(binding) {
        val unbind = binding.reader.bind(binding.conversationId) { text -> latestAppend(text) }
        onDispose { unbind() }
    }
}

internal fun appendSharedDraft(draft: String, text: String): String = when {
    text.isBlank() -> draft
    draft.isBlank() -> text
    else -> draft + (if (draft.endsWith('\n')) "" else "\n") + text
}

internal fun droppedDraftContent(clip: ClipData): IncomingShareRequest? {
    val uris = linkedSetOf<String>()
    val text = mutableListOf<String>()
    for (index in 0 until minOf(clip.itemCount, 24)) {
        val item = clip.getItemAt(index) ?: continue
        // Do not coerce Intent/URI payloads to text or open the provider just to preview a drag.
        item.uri?.toString()?.let(uris::add)
        item.text?.takeIf { it.isNotBlank() }?.toString()?.let(text::add)
    }
    if (uris.isEmpty() && text.isEmpty()) return null
    return IncomingShareRequest(uris.toList(), text = text.joinToString("\n").takeIf { it.isNotEmpty() })
}

@Composable
internal fun incomingDraftDropTarget(
    enabled: Boolean,
    onDrop: (IncomingShareRequest, () -> Unit) -> Boolean,
): Modifier {
    val activity = LocalActivity.current
    val ready by rememberUpdatedState(enabled)
    val accept by rememberUpdatedState(onDrop)
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (!ready) return false
                val raw = event.toAndroidDragEvent()
                val content = raw.clipData?.let(::droppedDraftContent) ?: return false
                val permission = if (content.uris.isEmpty()) null else {
                    runCatching { activity?.requestDragAndDropPermissions(raw) }.getOrNull()
                }
                var accepted = false
                val release = { runCatching { permission?.release() }; Unit }
                try {
                    accepted = accept(content, release)
                    return accepted
                } finally {
                    if (!accepted) release()
                }
            }
        }
    }
    return Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            ready && event.toAndroidDragEvent().clipDescription?.let {
                it.hasMimeType("text/*") || it.hasMimeType("image/*") || it.hasMimeType("application/*") ||
                    it.hasMimeType("audio/*") || it.hasMimeType("video/*")
            } == true
        },
        target = target,
    )
}
