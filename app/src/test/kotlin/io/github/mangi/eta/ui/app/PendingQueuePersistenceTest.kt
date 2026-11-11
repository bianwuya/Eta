package io.github.mangi.eta.ui.app

import android.app.Application
import android.graphics.Bitmap
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PendingQueuePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db") }
    @After fun close() { EtaDatabase.closeForTests() }
    private fun state(queue: List<QueuedMessageUi>) = AgentChatHomeUiState(
        messages = listOf(UserMessageUi("user", "start")), input = "", thinkingEnabled = false,
        isStreaming = true, queuedMessages = queue)
    private fun snapshot(state: AgentChatHomeUiState) = AgentConversationStore.Snapshot(
        "conversation", mapOf("conversation" to state), mapOf("conversation" to "title"), mapOf("conversation" to 1L))

    @Test fun pendingQueueSurvivesDatabaseReopenWithIdsOrderModeAndFlags() = runBlocking {
        val queue = listOf(QueuedMessageUi("second", "引导", immediateInterrupt = true),
            QueuedMessageUi("first", "plan", requestMode = "plan"))
        AgentConversationPersistence().save(context, snapshot(state(queue)))
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages
        assertEquals(queue.map { it.id }, restored.map { it.id })
        assertEquals(queue.map { it.content }, restored.map { it.content })
        assertEquals(queue.map { it.requestMode }, restored.map { it.requestMode })
    }

    @Test fun overlayPendingQueueSurvivesStaleMainSaveAndIsConsumedByIdOnly() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = state(emptyList())
        persistence.save(context, snapshot(initial))
        val dao = EtaDatabase.get(context).conversationDao()
        val voice = QueuedMessageUi("overlay-q", "unconfirmed", steeringRunId = "voice-run")
        AgentPendingQueueCodec.write(dao, AgentPendingQueueCodec.voiceOwner("conversation"),
            AgentPendingQueueCodec.encode(context, listOf(voice)))
        persistence.save(context, snapshot(initial.copy(input = "unrelated")))
        val restored = AgentConversationStore.loadConversation(context, "conversation")!!
        assertEquals("overlay-q", restored.queuedMessages.single().id)
        assertTrue(restored.queuedMessages.single().steeringUnconfirmed)
        persistence.save(context, snapshot(restored))
        persistence.save(context, snapshot(restored.copy(queuedMessages = emptyList())))
        assertTrue(AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.isEmpty())
        assertTrue(AgentPendingQueueCodec.readOwned(dao, AgentPendingQueueCodec.voiceOwner("conversation")).isEmpty())
    }

    @Test fun queuedImageKeepsOriginalBytesAfterTemporarySourceDisappears() = runBlocking {
        val image = java.io.File(context.cacheDir, "queue-original.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        val bytes = image.readBytes()
        val queue = listOf(QueuedMessageUi("image", "", pendingImages = listOf(PendingImageUi("img", image.path,
            "data:image/png;base64,preview", "image/png"))))
        AgentConversationPersistence().save(context, snapshot(state(queue)))
        assertTrue(image.delete())
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.single().pendingImages.single()
        val body = android.util.Base64.decode(restored.uri.substringAfter("base64,"), android.util.Base64.DEFAULT)
        assertArrayEquals(bytes, body)
    }
    @Test fun writeAheadOwnerRestoresUnknownUntilRuntimeConfirmsAndNeverAutomaticallySends() = runBlocking {
        val queue = listOf(QueuedMessageUi("q", "guidance", immediateInterrupt = true, steeringRunId = "voice-run"))
        AgentConversationPersistence().save(context, snapshot(state(queue)))
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.loadConversation(context, "conversation")!!
        val item = restored.queuedMessages.single()
        assertEquals("voice-run", item.steeringRunId)
        assertTrue(item.steeringUnconfirmed)
        assertTrue(item.restoredFromStorage)
        assertFalse(item.immediateInterrupt)
        assertNull(AgentMessageQueueReducer.pollNextToSendAfterRun(restored, false).second)
    }

    @Test fun queuedOnlyInputRestoresButWaitsForExplicitUserAction() = runBlocking {
        AgentConversationPersistence().save(context, snapshot(state(listOf(QueuedMessageUi("q", "wait")))))
        val restored = AgentConversationStore.loadConversation(context, "conversation")!!
        assertFalse(restored.queuedMessages.single().steeringUnconfirmed)
        assertNull(AgentMessageQueueReducer.pollNextToSendAfterRun(restored, false).second)
        assertEquals("q", AgentMessageQueueReducer.promoteToInterrupt(restored, "q").second!!.id)
    }

    @Test fun queueRemovalAndReorderAreDurableWithoutChangingHistory() = runBlocking {
        val persistence = AgentConversationPersistence()
        val original = state(listOf(QueuedMessageUi("a", "A"), QueuedMessageUi("b", "B")))
        persistence.save(context, snapshot(original))
        persistence.save(context, snapshot(original.copy(queuedMessages = original.queuedMessages.reversed())))
        assertEquals(listOf("b", "a"), AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.map { it.id })
        persistence.save(context, snapshot(original.copy(queuedMessages = emptyList())))
        EtaDatabase.closeForTests()
        assertTrue(AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.isEmpty())
        assertEquals("start", (AgentConversationStore.loadAllMessages(context, "conversation").single() as UserMessageUi).content)
    }

    @Test fun failedQueueWriteRollsBackAndWriterCanRetry() = runBlocking {
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(state(listOf(QueuedMessageUi("a", "A")))))
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_queue BEFORE INSERT ON agent_text_chunks WHEN NEW.field = 'pending_queue_v1' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        var failed = false
        try { persistence.save(context, snapshot(state(listOf(QueuedMessageUi("b", "B"))))) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals("a", AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.single().id)
        sql.execSQL("DROP TRIGGER reject_queue")
        persistence.save(context, snapshot(state(listOf(QueuedMessageUi("b", "B")))))
        assertEquals("b", AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.single().id)
    }

    @Test fun unloadedConversationKeepsAndUpdatesItsQueue() = runBlocking {
        val persistence = AgentConversationPersistence()
        val initial = state(listOf(QueuedMessageUi("a", "A")))
        persistence.save(context, snapshot(initial))
        val unloaded = AgentConversationStore.unloadConversation(initial)
        persistence.save(context, snapshot(unloaded.copy(queuedMessages = emptyList())))
        assertTrue(AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.isEmpty())
    }

    @Test fun corruptQueueIsNotMistakenForAnEmptyQueue() = runBlocking {
        AgentConversationPersistence().save(context, snapshot(state(listOf(QueuedMessageUi("q", "text")))))
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "UPDATE agent_text_chunks SET content = 'corrupt' WHERE field = 'pending_queue_v1'")
        var failed = false
        try { AgentConversationStore.loadConversation(context, "conversation") } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }

    @Test fun missingImageCannotSilentlyCommitTextOnlyOrOverwritePreviousQueue() = runBlocking {
        val persistence = AgentConversationPersistence()
        persistence.save(context, snapshot(state(listOf(QueuedMessageUi("a", "A")))))
        var failed = false
        try { persistence.save(context, snapshot(state(listOf(QueuedMessageUi("b", "B", pendingImages = listOf(
            PendingImageUi("missing", java.io.File(context.cacheDir, "missing.png").path, "", "image/png"))))))) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals("a", AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.single().id)
    }

    @Test fun filesAndDirectoriesKeepTheirReferenceIdentity() = runBlocking {
        val files = listOf(PendingFileReferenceUi("file", io.github.mangi.eta.agent.model.AgentFileReference("a.txt", "/sdcard/a.txt",
            io.github.mangi.eta.agent.model.AgentFileReferenceKind.File)),
            PendingFileReferenceUi("directory", io.github.mangi.eta.agent.model.AgentFileReference("docs", "/sdcard/docs",
                io.github.mangi.eta.agent.model.AgentFileReferenceKind.Directory)))
        AgentConversationPersistence().save(context, snapshot(state(listOf(QueuedMessageUi("q", "read", pendingFileReferences = files)))))
        EtaDatabase.closeForTests()
        assertEquals(files, AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.single().pendingFileReferences)
    }

    @Test fun stableImageIsReusedWhenLaterQueueChangesAfterSourceWasDeleted() = runBlocking {
        val image = java.io.File(context.cacheDir, "reuse.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        val persistence = AgentConversationPersistence()
        val original = state(listOf(QueuedMessageUi("q", "", pendingImages = listOf(
            PendingImageUi("image", image.path, "preview", "image/png")))))
        persistence.save(context, snapshot(original))
        assertTrue(image.delete())
        persistence.save(context, snapshot(original.copy(queuedMessages = original.queuedMessages + QueuedMessageUi("next", "next"))))
        assertEquals(2, AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.size)
    }

    @Test fun cacheFileAttachmentIsCopiedAndSurvivesItsTemporarySource() = runBlocking {
        val file = java.io.File(context.cacheDir, "queued.txt").apply { writeText("original file") }
        val reference = PendingFileReferenceUi("file", io.github.mangi.eta.agent.model.AgentFileReference("queued.txt", file.path,
            io.github.mangi.eta.agent.model.AgentFileReferenceKind.File))
        AgentConversationPersistence().save(context, snapshot(state(listOf(QueuedMessageUi("q", "read", pendingFileReferences = listOf(reference))))))
        assertTrue(file.delete())
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.loadConversation(context, "conversation")!!.queuedMessages.single().pendingFileReferences.single()
        assertEquals("original file", java.io.File(restored.reference.absolutePath).readText())
        assertEquals(reference.reference.displayName, restored.reference.displayName)
    }

}
