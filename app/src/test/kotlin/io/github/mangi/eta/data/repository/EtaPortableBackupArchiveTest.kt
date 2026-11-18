package io.github.mangi.eta.data.repository

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EtaPortableBackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun encryptedArchiveRequiresWholeAuthenticatedFile() {
        val file = temp.newFile()
        val part = EtaPortablePart.DATA
        val path = requireNotNull(part.requiredPath)
        runBlocking {
            file.outputStream().use { output ->
                EtaPortableBackupArchive.write(temp.root, output, setOf(part), PASSWORD) { entry ->
                    entry(path) { it.write("fixture-data".toByteArray()) }
                }
            }
        }
        val bytes = file.readBytes()
        fun extract(candidate: ByteArray, password: String?) = runBlocking {
            val root = temp.newFolder()
            val manifest = candidate.inputStream().use { EtaPortableBackupArchive.extract(root, it, password) }
            assertEquals(setOf(part), manifest?.parts)
            assertEquals("fixture-data", File(root, path).readText())
        }
        extract(bytes, PASSWORD)
        assertThrows(EtaPortablePasswordRequired::class.java) { extract(bytes, null) }
        assertThrows(AEADBadTagException::class.java) { extract(bytes, "wrong-password") }
        assertThrows(AEADBadTagException::class.java) { extract(bytes.copyOf(bytes.size - 1), PASSWORD) }
        assertThrows(AEADBadTagException::class.java) { extract(bytes + byteArrayOf(0), PASSWORD) }
    }

    @Test fun plainArchiveRejectsUnknownPathsAndIncorrectHashes() {
        val path = requireNotNull(EtaPortablePart.IMAGEGEN.requiredPath)
        val data = """{"connection":null}""".toByteArray()
        val recorded = EtaPortableEntry(path, EtaPortablePart.IMAGEGEN, data.size.toLong(), "0".repeat(64))
        val root = temp.newFolder()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            val entries = mapOf(path to data, "subagents/../../escaped.md" to "fixture".toByteArray())
            plain(entries, EtaPortableManifest(exportedAt = 0, parts = setOf(EtaPortablePart.IMAGEGEN), entries = listOf(recorded)))
                .inputStream().use { EtaPortableBackupArchive.extract(root, it, null) }
        } }
        assertFalse(File(root.parentFile, "escaped.md").exists())
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            plain(mapOf(path to data), EtaPortableManifest(exportedAt = 0, parts = setOf(EtaPortablePart.IMAGEGEN), entries = listOf(recorded)))
                .inputStream().use { EtaPortableBackupArchive.extract(temp.newFolder(), it, null) }
        } }
    }

    @Test fun sensitiveCategoryCannotWriteOrAcceptPlainContainer() {
        val part = EtaPortablePart.MCP
        val path = requireNotNull(part.requiredPath)
        val data = "[]".toByteArray()
        val file = temp.newFile()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            file.outputStream().use { EtaPortableBackupArchive.write(temp.root, it, setOf(part), null) { error("must reject before collecting") } }
        } }
        assertEquals(0L, file.length())
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val recorded = EtaPortableEntry(path, part, data.size.toLong(), digest)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            plain(mapOf(path to data), EtaPortableManifest(exportedAt = 0, parts = setOf(part), entries = listOf(recorded)))
                .inputStream().use { EtaPortableBackupArchive.extract(temp.newFolder(), it, null) }
        } }
    }

    private fun plain(entries: Map<String, ByteArray>, manifest: EtaPortableManifest): File = temp.newFile().apply {
        outputStream().use { output ->
            output.write("ETAZIP2".toByteArray())
            ZipOutputStream(output).use { zip ->
                for ((name, body) in entries + ("manifest.json" to EtaPortableBackupArchive.json.encodeToString(manifest).toByteArray())) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body)
                    zip.closeEntry()
                }
            }
        }
    }

    private companion object { const val PASSWORD = "fixture-password" }
}
