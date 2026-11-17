package io.github.mangi.eta.local.richcontent

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RichContentTest {
    @Test fun onlyExplicitLocalOutputReferencesBecomeCards() {
        listOf("/sdcard/Download/页面/示例.html", "/storage/emulated/0/Download/a b.png", "file:///sdcard/Download/a.htm", "content://test/preview.jpg").forEach {
            assertTrue(it, LocalPreviewFiles.isReference(it))
        }
        listOf("https://example.com/a.html", "javascript:alert(1)", "/data/data/app/secret.html", "/sdcard/DCIM/a.png", "/sdcard/Download/file.txt", "outputs/a.html").forEach {
            assertFalse(it, LocalPreviewFiles.isReference(it))
        }
    }
    @Test fun imageDetectionHandlesQueriesAndMixedCase() {
        assertTrue(LocalPreviewFiles.isImage("/sdcard/Download/a.PNG?view=1"))
        assertFalse(LocalPreviewFiles.isImage("/sdcard/Download/a.html"))
    }
    @Test fun resourcePolicyRejectsTraversalHiddenFilesAndUnsupportedTypes() {
        val root = Files.createTempDirectory("rich-output").toFile()
        try {
            File(root, "a.css").writeText("body{}")
            File(root, "secret.txt").writeText("fixture")
            File(root, ".private.js").writeText("fixture")
            assertNotNull(LocalPreviewFiles.resource(root, "a.css"))
            listOf("../a.css", "x/../../a.css", ".private.js", "secret.txt", "a.html/../../a.css", "sub\\a.css", "missing.css").forEach {
                assertNull(it, LocalPreviewFiles.resource(root, it))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun resourcePolicyRejectsSymlinkOutsideRoot() {
        val root = Files.createTempDirectory("rich-output").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            val secret = File(outside, "outside.js").apply { writeText("fixture") }
            Files.createSymbolicLink(File(root, "escape.js").toPath(), secret.toPath())
            assertNull(LocalPreviewFiles.resource(root, "escape.js"))
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun bareDownloadsDoesNotExposeAllOtherFiles() {
        val parent = Files.createTempDirectory("rich-parent").toFile()
        try {
            val root = File(parent, "Download").apply { mkdirs() }
            File(root, "a.js").writeText("fixture")
            assertNull(LocalPreviewFiles.resource(root, "a.js"))
        } finally { parent.deleteRecursively() }
    }
    @Test fun boundedReaderAcceptsLimitAndRejectsOverflow() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBytesBounded(2))
        assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesBounded(2) }
    }
}
