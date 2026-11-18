package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IncomingDraftTest {
    @Test fun appendsToLiveTextWithoutTrimmingOrAddingAnExtraLine() {
        assertEquals("已有  \n共享", appendSharedDraft("已有  ", "共享"))
        assertEquals("已有\n共享", appendSharedDraft("已有\n", "共享"))
        assertEquals("已有", appendSharedDraft("已有", ""))
        assertEquals("共享", appendSharedDraft("", "共享"))
    }

    @Test fun disposingAnOldComposerDoesNotUnbindTheNewOne() {
        val reader = IncomingDraftReader()
        val closeOld = reader.bind("old") { it }
        var text = "原草稿"
        val closeNew = reader.bind("new") { incoming -> appendSharedDraft(text, incoming).also { text = it } }
        val new = checkNotNull(reader.source)
        closeOld()
        assertSame(new, reader.source)
        assertEquals("new", new.conversationId)
        new.append("第一份")
        new.append("第二份")
        assertEquals("原草稿\n第一份\n第二份", text)
        closeNew()
        assertNull(reader.source)
    }
}
