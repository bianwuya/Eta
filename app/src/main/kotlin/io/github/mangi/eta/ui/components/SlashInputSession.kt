package io.github.mangi.eta.ui.components

import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Observe real IME commits without replacing the project's TextField or editing its contents.
 * Most soft keyboards commitText instead of emitting KeyEvent. Programmatic draft edits do not
 * reach this connection; Android context-menu paste and composing text deliberately do not arm.
 * A private IME which reports its own clipboard as commitText("/") is indistinguishable from
 * typing that symbol at the Android boundary. That can at most show a menu: never select or send.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SlashInputSession(
    state: TextFieldState,
    tracker: SlashCommandTracker,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val enabledNow by rememberUpdatedState(enabled)
    val interceptor = remember(state, tracker) {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    return object : InputConnectionWrapper(connection, false) {
                        override fun beginBatchEdit(): Boolean {
                            tracker.beginImeBatch()
                            return super.beginBatchEdit()
                        }
                        override fun endBatchEdit(): Boolean = try {
                            // Compose can apply the buffered InputTransformation here, so keep
                            // the evidence alive until the target connection has completed it.
                            super.endBatchEdit()
                        } finally {
                            tracker.endImeBatch()
                        }
                        private fun prepare(text: CharSequence?) {
                            if (enabledNow) tracker.imeCommit(state.text.toString(), state.selection.start,
                                state.selection.end, text?.toString(), state.composition != null)
                            else tracker.clear()
                        }
                        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                            prepare(text)
                            return super.commitText(text, newCursorPosition)
                        }
                        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
                            prepare(text)
                            return super.commitText(text, newCursorPosition, textAttribute)
                        }
                        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                            tracker.indirectImeEdit()
                            return super.setComposingText(text, newCursorPosition)
                        }
                        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
                            tracker.indirectImeEdit()
                            return super.setComposingText(text, newCursorPosition, textAttribute)
                        }
                        override fun performContextMenuAction(id: Int): Boolean {
                            tracker.indirectImeEdit()
                            return super.performContextMenuAction(id)
                        }
                        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                            tracker.indirectImeEdit()
                            return super.deleteSurroundingText(beforeLength, afterLength)
                        }
                        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                            tracker.indirectImeEdit()
                            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                        }
                        override fun setComposingRegion(start: Int, end: Int): Boolean {
                            tracker.indirectImeEdit()
                            return super.setComposingRegion(start, end)
                        }
                        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean {
                            tracker.indirectImeEdit()
                            return super.setComposingRegion(start, end, textAttribute)
                        }
                        override fun commitCompletion(text: CompletionInfo?): Boolean {
                            tracker.indirectImeEdit()
                            return super.commitCompletion(text)
                        }
                        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
                            tracker.indirectImeEdit()
                            return super.commitCorrection(correctionInfo)
                        }
                        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int,
                            textAttribute: TextAttribute?): Boolean {
                            tracker.indirectImeEdit()
                            return super.replaceText(start, end, text, newCursorPosition, textAttribute)
                        }
                        override fun closeConnection() {
                            tracker.closeImeSession()
                            super.closeConnection()
                        }
                    }
                }
            })
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
