package io.github.mangi.eta.ui.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.viewModelScope
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.local.incoming.IncomingContentHost
import io.github.mangi.eta.local.incoming.IncomingContentInbox
import io.github.mangi.eta.local.incoming.IncomingShareActivity
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.latestContextUsage
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Regressions from integrating v3.4.1 into the local draft and paging contracts. */
@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36], shadows = [io.github.mangi.eta.testing.WindowsAtomicFileShadow::class])
class LocalMergeRegressionTest {
    @Test fun portableRestoreCannotBypassRuntimeStatusGuard() = withFixture(true) { f ->
        val archive = ByteArrayOutputStream()
        runBlocking {
            io.github.mangi.eta.local.imagegen.ImageGenConfigStore.write(f.activity,
                io.github.mangi.eta.local.imagegen.ImageGenConfig(profileId = "backup-guard",
                    source = io.github.mangi.eta.local.imagegen.ImageGenSource.OPENAI_API,
                    endpoint = "https://fixture.invalid", model = "image"))
            io.github.mangi.eta.data.repository.EtaPortableBackup.export(f.activity, archive,
                setOf(io.github.mangi.eta.data.repository.EtaPortablePart.IMAGEGEN))
            io.github.mangi.eta.local.imagegen.ImageGenConfigStore.remove(f.activity, "backup-guard")
            io.github.mangi.eta.data.repository.EtaPortableBackup.inspect(f.activity,
                ByteArrayInputStream(archive.toByteArray())).use { staged ->
                val failure = runCatching { f.state.restorePortableBackup(staged, staged.manifest.parts) }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("Runtime"))
                assertTrue(io.github.mangi.eta.local.imagegen.ImageGenConfigStore.profiles(f.activity).profiles.isEmpty())
                assertTrue(staged.file("imagegen/config.json").exists())
            }
        }
    }

    @Test fun sessionTotalsRemainCompleteWhenTheConversationIsLoadedInPages() = withFixture(false) { f ->
        runBlocking {
            val messages = (0 until 100).flatMap { index -> listOf(
                UserMessageUi("paging-user-$index", "question $index"),
                AgentMessageUi("paging-assistant-$index", "answer $index", usage = TokenUsageUi(
                    contextTokens = 110, inputTokens = 100, outputTokens = 10, stepCount = 1,
                )),
            ) }
            val source = AgentChatHomeUiState(messages = messages, input = "", isStreaming = false, thinkingEnabled = false)
            AgentConversationStore.save(f.activity, "paging-source", mapOf("paging-source" to source),
                mapOf("paging-source" to "paged conversation"), mapOf("paging-source" to 1L))
            val recent = requireNotNull(AgentConversationStore.loadConversation(f.activity, "paging-source"))
            assertTrue(recent.hasEarlierMessages)
            assertEquals(128, recent.messages.size)
            val whole = AgentConversationStore.loadAllMessages(f.activity, "paging-source")
            val expected = latestContextUsage(whole, null).sessionStats
            val actual = latestContextUsage(recent.messages, null, recent.sessionStatsPrefix).sessionStats
            assertEquals(100, expected.turns)
            assertEquals(10_000, expected.promptTokens)
            assertEquals("Session totals must include the stored prefix", expected, actual)
        }
    }

    @Test fun sharesheetOffersOnlyOneEtaTarget() = withFixture(false) { f ->
        val request = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(f.activity.packageName)
        @Suppress("DEPRECATION")
        val matches = f.activity.packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(matches.map { it.activityInfo.name }.toString(), 1, matches.size)
    }

    @Test fun sharingTextKeepsTheActualUnsentDraft() = withFixture(false) { f ->
        f.type("draft-before-share")
        assertEquals("", f.state.homeState.input)
        f.share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared-text"))
        f.await { f.editor().config[SemanticsProperties.EditableText].text.contains("shared-text") }
        val actual = f.editor().config[SemanticsProperties.EditableText].text
        assertTrue("Actual editor text after share: $actual", actual.contains("draft-before-share"))
        assertTrue(actual.contains("shared-text"))
    }

    @Test fun branchButtonPreservesTheActualSourceDraft() = assertBranchDraft("draft-before-branch")

    @Test fun branchButtonPreservesChineseDraft() = assertBranchDraft("尚未发送的中文草稿，标点。")

    @Test fun branchButtonAcceptsAnEmptyDraft() = assertBranchDraft("")

    @Test fun branchButtonDoesNothingWithoutAMatchingEditorBinding() = withFixture(true, bindDraft = false) { f ->
        f.type("unbound draft")
        f.clickBranch()
        f.advance()
        assertEquals("source", f.state.conversationPaneState.selectedConversationId)
        assertEquals(1, f.state.conversationPaneState.conversations.size)
        assertFalse(f.state.isBranchingCurrentConversation)
    }

    private fun assertBranchDraft(draft: String) = withFixture(true) { f ->
        f.type(draft)
        assertEquals("", f.state.homeState.input)
        f.clickBranch()
        f.await { f.state.conversationPaneState.selectedConversationId != "source" &&
            f.state.homeState.isConversationLoaded && !f.state.isLoadingConversation }
        assertEquals("", f.state.homeState.input)
        f.rebuild()
        f.advance()
        assertEquals("", f.editor().config[SemanticsProperties.EditableText].text)
        f.state.selectConversation("source")
        f.await { f.state.homeState.isConversationLoaded && !f.state.isLoadingConversation }
        f.advance()
        assertEquals("Source editor after branching and returning", draft,
            f.editor().config[SemanticsProperties.EditableText].text)
    }

    @Test fun shareAppendsStableAttachmentsOnceAndKeepsExistingDraftAndAttachments() = withFixture(true) { f ->
        f.type("existing draft")
        val existing = importFile(f, "existing", "existing content")
        f.state.attachIncomingContent(existing.images, existing.files)
        val shared = Uri.parse("content://incoming-test/shared.pdf")
        shadowOf(f.activity.contentResolver).registerInputStream(shared, ByteArrayInputStream("shared pdf".toByteArray()))
        f.share(Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, shared).putExtra(Intent.EXTRA_TEXT, "caption"))
        f.await { f.state.homeState.pendingFileReferences.size == 2 }
        f.await { runBlocking { IncomingContentInbox.list(f.activity).isEmpty() } }
        assertEquals("source", f.state.conversationPaneState.selectedConversationId)
        assertEquals("existing draft\ncaption", f.editor().config[SemanticsProperties.EditableText].text)
        val copy = File(f.state.homeState.pendingFileReferences.last().reference.absolutePath)
        shadowOf(f.activity.contentResolver).registerInputStream(shared, ByteArrayInputStream(ByteArray(0)))
        assertEquals("shared pdf", copy.readText())
        f.advance()
        assertEquals(2, f.state.homeState.pendingFileReferences.size)
        assertFalse(f.state.homeState.isStreaming)
    }

    @Test fun sharingWaitsWhileEditingAndImportFailureKeepsTheDraft() = withFixture(true) { f ->
        f.state.beginMessageEdit("user-source")
        assertNotNull(f.state.homeState.messageEdit)
        f.advance()
        f.type("editing text")
        f.share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "waiting share"))
        assertNotNull(f.state.homeState.messageEdit)
        assertEquals(1, runBlocking { IncomingContentInbox.list(f.activity).size })
        assertEquals("source", f.state.conversationPaneState.selectedConversationId)
        assertEquals("editing text", f.editor().config[SemanticsProperties.EditableText].text)
        f.state.cancelMessageEdit()
        f.advance()
        f.await { f.editor().config[SemanticsProperties.EditableText].text.contains("waiting share") }
        f.await { runBlocking { IncomingContentInbox.list(f.activity).isEmpty() } }
        f.type("draft before failure")
        val inboxMetadata = f.activity.filesDir.resolve("local-inbox/queue.json")
        inboxMetadata.writeText("corrupt")
        f.share(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "must not replace"))
        assertEquals("draft before failure", f.editor().config[SemanticsProperties.EditableText].text)
        assertEquals("corrupt", inboxMetadata.readText())
        assertEquals("source", f.state.conversationPaneState.selectedConversationId)
    }

    @Test fun multipleShareCopiesImagesAndDocumentsBeforeTheGrantIsLost() = withFixture(true) { f ->
        f.type("image draft")
        val imageUri = Uri.parse("content://incoming-test/picture.png")
        val pdfUri = Uri.parse("content://incoming-test/multiple.pdf")
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        shadowOf(f.activity.contentResolver).registerInputStream(imageUri, ByteArrayInputStream(png))
        shadowOf(f.activity.contentResolver).registerInputStream(pdfUri, ByteArrayInputStream("document".toByteArray()))
        f.share(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(imageUri, pdfUri, imageUri)))
        f.await { f.state.homeState.pendingImages.size == 1 && f.state.homeState.pendingFileReferences.size == 1 }
        assertEquals("image draft", f.editor().config[SemanticsProperties.EditableText].text)
        assertEquals("source", f.state.conversationPaneState.selectedConversationId)
        val imageCopy = File(f.state.homeState.pendingImages.single().uri)
        assertTrue(imageCopy.isFile)
        assertArrayEquals(png, imageCopy.readBytes())
        f.await { runBlocking { IncomingContentInbox.list(f.activity).isEmpty() } }
        f.rebuild()
        f.advance()
        assertEquals(1, f.state.homeState.pendingImages.size)
        assertEquals(1, f.state.homeState.pendingFileReferences.size)
    }

    @Test fun branchButtonPreservesExistingAttachmentsAndRejectsDoubleClick() = withFixture(true) { f ->
        val existing = importFile(f, "branch-file", "keep file")
        f.state.attachIncomingContent(existing.images, existing.files)
        f.type("draft with attachment")
        f.clickBranch()
        f.clickBranch()
        f.await { f.state.conversationPaneState.selectedConversationId != "source" &&
            f.state.homeState.isConversationLoaded && !f.state.isLoadingConversation }
        assertEquals(2, f.state.conversationPaneState.conversations.size)
        f.advance()
        assertTrue(f.state.homeState.pendingFileReferences.isEmpty())
        f.state.selectConversation("source")
        f.await { f.state.homeState.isConversationLoaded && !f.state.isLoadingConversation }
        assertEquals(existing.files, f.state.homeState.pendingFileReferences)
        assertEquals("draft with attachment", f.state.homeState.input)
    }

    @Test fun branchWriteFailureKeepsTheActualEditorAndSourceConversation() = withFixture(true) { f ->
        EtaDatabase.get(f.activity).openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_branch BEFORE INSERT ON conversations WHEN NEW.id != 'source' " +
                "BEGIN SELECT RAISE(ABORT, 'branch_test_failure'); END",
        )
        f.type("keep draft after failure")
        f.clickBranch()
        f.await { !f.state.isBranchingCurrentConversation }
        assertEquals("source", f.state.conversationPaneState.selectedConversationId)
        assertEquals(1, f.state.conversationPaneState.conversations.size)
        assertEquals("keep draft after failure", f.editor().config[SemanticsProperties.EditableText].text)
    }

    @Test fun switchingWhileBranchIsSavingDoesNotStealTheNewSelection() = withFixture(true) { f ->
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        AgentAppState::class.java.getDeclaredField("persistenceJob").apply { isAccessible = true }.set(f.state, gate)
        f.type("draft while saving")
        f.clickBranch()
        assertTrue(f.state.isBranchingCurrentConversation)
        f.state.createConversation()
        gate.complete(Unit)
        f.await { f.state.conversationPaneState.conversations.size == 2 }
        assertNull(f.state.conversationPaneState.selectedConversationId)
        f.state.selectConversation("source")
        f.await { f.state.homeState.isConversationLoaded && !f.state.isLoadingConversation }
        assertEquals("draft while saving", f.state.homeState.input)
    }

    private fun importFile(f: Fixture, id: String, text: String): io.github.mangi.eta.local.incoming.IncomingContent {
        val uri = Uri.parse("content://incoming-test/$id.pdf")
        shadowOf(f.activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        return requireNotNull(runBlocking {
            IncomingContentInbox.import(f.activity,
                io.github.mangi.eta.local.incoming.IncomingPayload("", listOf(uri), "application/pdf"), id)
                .also { IncomingContentInbox.remove(f.activity, id) }
        })
    }

    private fun withFixture(seedConversation: Boolean, bindDraft: Boolean = true, test: (Fixture) -> Unit) {
        // Robolectric replaces Application per test; AndroidViewModelFactory keeps a process singleton.
        androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .firstOrNull { it.type == androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java }
            ?.apply { isAccessible = true }?.set(null, null)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        shadowOf(activity.application).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests()
        activity.deleteDatabase("eta.db")
        activity.filesDir.resolve("local-inbox").deleteRecursively()
        ShadowContentResolver.registerProviderInternal("incoming-test", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "application/pdf"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        if (seedConversation) runBlocking {
            val journal = listOf(ConversationMessage("user", "question", messageId = "user-source"),
                ConversationMessage("assistant", "answer", messageId = "assistant-source-1"))
            val source = AgentChatHomeUiState(
                messages = listOf(UserMessageUi("user-source", "question"), AgentMessageUi("assistant-source-1-0", "answer")),
                history = journal, journal = journal, input = "", isStreaming = false, thinkingEnabled = false,
            )
            AgentConversationStore.save(activity, "source", mapOf("source" to source), mapOf("source" to "source"), mapOf("source" to 1L))
        }
        val fixture = Fixture(activity, bindDraft)
        try { test(fixture) } finally {
            fixture.close()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
            EtaDatabase.closeForTests()
        }
    }

    private class Fixture(val activity: ComponentActivity, private val bindDraft: Boolean) {
        private val frames = BroadcastFrameClock()
        private var compositionFailure: Throwable? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frames +
            kotlinx.coroutines.CoroutineExceptionHandler { _, failure -> compositionFailure = failure })
        private val recomposer = Recomposer(scope.coroutineContext)
        private val host = ComposeView(activity)
        val state = AgentAppState(activity, scope)

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.setParentCompositionContext(recomposer)
            mount()
            activity.setContentView(host)
            advance()
        }

        private fun mount() {
            host.setContent {
                AgentAppTheme(AppearanceSettings(uiStyle = AppearanceUiStyle.MIUIX), applyInterfaceScale = false) {
                    val screen: @androidx.compose.runtime.Composable () -> Unit = {
                        AgentHomeScreen(state.homeState, state.modelPickerState, state.conversationPaneState.selectedConversationId,
                            isBranching = state.isBranchingCurrentConversation,
                            onAction = { action ->
                                if (action is AgentHomeAction.BranchConversation) {
                                    state.branchConversation(action.conversationId, action.messageId, action.draftText)
                                }
                            })
                    }
                    if (bindDraft) IncomingContentHost(state, dropEnabled = true, onOpenHome = {}, content = screen)
                    else screen()
                }
            }
        }

        fun rebuild() {
            host.disposeComposition()
            activity.viewModelStore.clear()
            mount()
        }

        fun clickBranch() {
            val label = activity.getString(R.string.conversation_branch)
            val target = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            var clickable: SemanticsNode? = target
            while (clickable != null && !clickable.config.contains(SemanticsActions.OnClick)) clickable = clickable.parent
            assertTrue(requireNotNull(clickable).config[SemanticsActions.OnClick].action?.invoke() == true)
        }

        fun share(intent: Intent) {
            val share = Robolectric.buildActivity(IncomingShareActivity::class.java, intent).setup()
            await { share.get().isFinishing }
            share.pause().stop().destroy()
        }

        fun type(text: String) {
            assertTrue(editor().config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text)) == true)
            advance()
            assertEquals(text, editor().config[SemanticsProperties.EditableText].text)
        }
        fun awaitEditor() = await { nodes().any { it.config.contains(SemanticsActions.SetText) } }

        fun editor() = nodes().firstOrNull { it.config.contains(SemanticsActions.SetText) }
            ?: error("Missing editor: selected=${state.conversationPaneState.selectedConversationId} loaded=${state.homeState.isConversationLoaded} loading=${state.isLoadingConversation} recomposer=${recomposer.currentState.value} nodes=${nodes().map { it.config }}")
        fun advance() {
            repeat(8) {
                Snapshot.sendApplyNotifications()
                frames.sendFrame(SystemClock.uptimeMillis() * 1_000_000)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                val decor = activity.window.decorView
                fun invalidateLayout(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) repeat(view.childCount) { invalidateLayout(view.getChildAt(it)) }
                }
                invalidateLayout(decor)
                decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 1080, 2400)
                compositionFailure?.let { throw AssertionError("Composition failed", it) }
            }
        }
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition() && System.nanoTime() < deadline) { advance(); Thread.sleep(1) }
            assertTrue("Regression condition timed out", condition())
        }
        fun nodes(): List<SemanticsNode> {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            fun roots(view: View): List<ViewRootForTest> = when (view) {
                is ViewRootForTest -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
                else -> emptyList()
            }
            return roots(activity.window.decorView).flatMap { collect(it.semanticsOwner.rootSemanticsNode) }
        }
        private fun root(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) root(view.getChildAt(i))?.let { return it }
            return null
        }
        fun close() {
            host.disposeComposition()
            recomposer.cancel()
            val model = androidx.lifecycle.ViewModelProvider(activity)[io.github.mangi.eta.local.incoming.IncomingContentViewModel::class.java]
            val modelJob = model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
            activity.viewModelStore.clear()
            scope.cancel()
            await { modelJob.isCompleted && scope.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted }
        }
    }
}
