package io.github.mangi.eta.local.incoming

import android.app.Application
import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class IncomingContentInboxTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearInbox() {
        app.filesDir.resolve("local-inbox").deleteRecursively()
    }

    @Test fun queuePersistsDeduplicatesRemovesAndRejectsOverflow() = runBlocking {
        repeat(20) { index ->
            IncomingContentInbox.import(app, IncomingPayload("text$index", emptyList()), "id$index")
        }
        assertEquals(20, IncomingContentInbox.list(app).size)
        assertEquals("text0", IncomingContentInbox.list(app).first().text)
        IncomingContentInbox.import(app, IncomingPayload("duplicate", emptyList()), "id0")
        assertEquals("text0", IncomingContentInbox.list(app).first().text)
        try {
            IncomingContentInbox.import(app, IncomingPayload("overflow", emptyList()), "overflow")
            fail("expected full inbox")
        } catch (e: IOException) {
            assertEquals("inbox full", e.message)
        }
        IncomingContentInbox.remove(app, "id0")
        val reloaded = IncomingContentInbox.list(app)
        assertEquals(19, reloaded.size)
        assertFalse(reloaded.any { it.id == "id0" })
        assertTrue(reloaded.any { it.id == "id19" })
    }

    @Test fun copiesAnAuthorizedFileAndRemovingMetadataKeepsTheAttachment() = runBlocking {
        val uri = Uri.parse("content://incoming-test/document")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("shared file".toByteArray()))
        val imported = IncomingContentInbox.import(app, IncomingPayload("caption", listOf(uri), "application/pdf"), "file-id")!!
        assertEquals(1, imported.files.size)
        val copied = java.io.File(imported.files.single().reference.absolutePath)
        assertEquals("shared file", copied.readText())
        assertTrue(copied.canonicalPath.startsWith(TerminalPrivateStorage.workspace(app.filesDir).canonicalPath + "/imports/"))
        assertEquals(imported, IncomingContentInbox.list(app).single())
        IncomingContentInbox.remove(app, imported.id)
        assertTrue(copied.isFile)
        assertTrue(IncomingContentInbox.list(app).isEmpty())
    }

    @Test fun corruptMetadataIsNotSilentlyOverwritten() = runBlocking {
        val metadata = app.filesDir.resolve("local-inbox/queue.json")
        metadata.parentFile!!.mkdirs()
        metadata.writeText("not json")
        try {
            IncomingContentInbox.import(app, IncomingPayload("new", emptyList()), "new")
            fail("expected corrupt metadata failure")
        } catch (_: IOException) {
            assertEquals("not json", metadata.readText())
        }
    }

    @Test fun incomingTextPreservesTheCurrentUnsentDraft() {
        assertEquals("typed draft\nshared", appendIncomingText("typed draft", "shared"))
        assertEquals("typed draft\nshared", appendIncomingText("typed draft\n", "shared"))
        assertEquals("shared", appendIncomingText("", "shared"))
        assertEquals("draft", appendIncomingText("draft", ""))
    }
}
