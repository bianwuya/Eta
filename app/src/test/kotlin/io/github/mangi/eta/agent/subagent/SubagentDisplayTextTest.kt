package io.github.mangi.eta.agent.subagent

import org.junit.Assert.*
import org.junit.Test

class SubagentDisplayTextTest {
    @Test fun removesNestedCredentialsFlagsAndSignedQueriesFromDisplayOnly() {
        val original = """{"nested":{"api_key":"PRIVATE_KEY","authorization":"Bearer PRIVATE_BEARER"},"command":"curl --token PRIVATE_FLAG https://example.invalid/media?signature=PRIVATE_QUERY","ok":true}"""
        val safe = SubagentDisplayText.redact(original)
        listOf("PRIVATE_KEY", "PRIVATE_BEARER", "PRIVATE_FLAG", "PRIVATE_QUERY").forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("example.invalid/media"))
        assertTrue(safe.contains("ok"))
        assertTrue(original.contains("PRIVATE_KEY"))
    }
    @Test fun previewsAlwaysDeclareTruncationAndRetainDetailReference() {
        val snapshot = SubagentActivitySnapshot(subagentId = "child", detailId = "task-unique", prompt = "x".repeat(32_000))
        val preview = requireNotNull(SubagentActivitySnapshot.decode(snapshot.encodeBounded(2000)))
        assertTrue(preview.previewTruncated)
        assertEquals("task-unique", preview.detailId)
        assertTrue(preview.prompt.length < snapshot.prompt.length)
    }
}
