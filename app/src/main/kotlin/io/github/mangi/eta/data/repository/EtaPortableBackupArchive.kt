package io.github.mangi.eta.data.repository

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class EtaPortablePart(val requiredPath: String? = null, val requiresPassword: Boolean = false) {
    DATA("data/legacy.json", true), SKILLS("skills/states.json"), SUBAGENTS,
    SETTINGS("settings/config.json", true), IMAGEGEN("imagegen/config.json"), MCP("mcp/servers.json", true),
}

@Serializable
internal data class EtaPortableEntry(val name: String, val part: EtaPortablePart, val bytes: Long, val sha256: String)

@Serializable
internal data class EtaPortableManifest(
    val format: String = "eta-portable-backup",
    val version: Int = 1,
    val exportedAt: Long,
    val parts: Set<EtaPortablePart>,
    val entries: List<EtaPortableEntry>,
)

internal object EtaPortableBackupArchive {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    const val SKILL_ZIP = "skills/user-skills.zip"
    private const val MANIFEST = "manifest.json"
    private const val MAX_ENTRIES = 4096
    private const val MAX_EXPANDED = 640L * 1024 * 1024
    // ETAPKG2 authenticates the entire ZIP in one GCM message. JCA can buffer it all.
    const val MAX_LOCKED_MIB = 16
    private const val MAX_LOCKED = MAX_LOCKED_MIB * 1024L * 1024
    private const val TAG_BYTES = 16
    private val plain = "ETAZIP2".toByteArray(Charsets.US_ASCII)
    private val locked = "ETAPKG2".toByteArray(Charsets.US_ASCII)

    suspend fun write(
        cache: File,
        output: OutputStream,
        parts: Set<EtaPortablePart>,
        password: String?,
        collect: suspend (suspend (String, suspend (OutputStream) -> Unit) -> Unit) -> Unit,
    ): EtaPortableManifest {
        require(parts.isNotEmpty() && (parts.none { it.requiresPassword } || !password.isNullOrBlank()))
        val cancel = currentCoroutineContext()
        val zipFile = File.createTempFile("eta-portable-", ".zip", cache)
        try {
            val entries = linkedMapOf<String, EtaPortableEntry>()
            var total = 0L
            val encrypted = !password.isNullOrBlank()
            val manifest = zipFile.outputStream().buffered().use { raw ->
                val bounded = BoundedSink(raw, if (encrypted) MAX_LOCKED else MAX_EXPANDED, cancel::ensureActive)
                ZipOutputStream(bounded).use { zip ->
                    collect { name, body ->
                        val part = partFor(name)
                        require(part in parts && name !in entries && entries.size < MAX_ENTRIES - 1)
                        val hash = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry(name))
                        val sink = BoundedSink(zip, minOf(limit(name), MAX_EXPANDED - total), cancel::ensureActive, hash)
                        body(sink)
                        zip.closeEntry()
                        total += sink.size
                        entries[name] = EtaPortableEntry(name, part, sink.size, hash.hex())
                    }
                    val result = EtaPortableManifest(exportedAt = System.currentTimeMillis(), parts = parts, entries = entries.values.toList())
                    validate(result, entries, encrypted)
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    val manifestBytes = json.encodeToString(EtaPortableManifest.serializer(), result).toByteArray()
                    require(manifestBytes.size <= limit(MANIFEST))
                    require(total + manifestBytes.size <= MAX_EXPANDED)
                    zip.write(manifestBytes)
                    zip.closeEntry()
                    result
                }
            }
            cancel.ensureActive()
            if (encrypted) {
                val random = SecureRandom()
                val salt = ByteArray(16).also(random::nextBytes)
                val nonce = ByteArray(12).also(random::nextBytes)
                val ciphertext = cipher(Cipher.ENCRYPT_MODE, requireNotNull(password), salt, nonce).doFinal(zipFile.readBytes())
                cancel.ensureActive()
                output.write(locked)
                output.write(salt)
                output.write(nonce)
                output.write(ciphertext)
            } else {
                output.write(plain)
                zipFile.inputStream().use { copy(it, output, MAX_EXPANDED, cancel::ensureActive) }
            }
            output.flush()
            return manifest
        } finally {
            zipFile.delete()
        }
    }

    /** Copies legacy JSON or authenticates and verifies the complete portable container. Only private staging is written. */
    suspend fun extract(root: File, input: InputStream, password: String?): EtaPortableManifest? {
        val cancel = currentCoroutineContext()
        val buffered = PushbackInputStream(input.buffered(), plain.size)
        val marker = buffered.readNBytes(plain.size)
        val encrypted = marker.contentEquals(locked)
        if (!encrypted && !marker.contentEquals(plain)) {
            buffered.unread(marker)
            val file = File(root, requireNotNull(EtaPortablePart.DATA.requiredPath))
            check(file.parentFile!!.mkdirs())
            file.outputStream().use { copy(buffered, it, EtaBackupJsonStreams.MAX_BACKUP_BYTES, cancel::ensureActive) }
            return null
        }
        if (encrypted && password.isNullOrBlank()) throw EtaPortablePasswordRequired()
        val zipFile = File(root, "payload.zip")
        try {
            if (encrypted) {
                val salt = buffered.readNBytes(16).also { require(it.size == 16) }
                val nonce = buffered.readNBytes(12).also { require(it.size == 12) }
                cancel.ensureActive()
                // Read through EOF before accepting any parsed ZIP entry. A wrong password, truncation,
                // or appended bytes cannot be missed by an early ZIP end marker or close().
                val ciphertext = buffered.readNBytes((MAX_LOCKED + TAG_BYTES + 1).toInt())
                if (ciphertext.size > MAX_LOCKED + TAG_BYTES) throw EtaPortableSizeLimit()
                require(ciphertext.size >= TAG_BYTES)
                cancel.ensureActive()
                val bytes = cipher(Cipher.DECRYPT_MODE, requireNotNull(password), salt, nonce).doFinal(ciphertext)
                zipFile.writeBytes(bytes)
            } else {
                zipFile.outputStream().use { copy(buffered, it, MAX_EXPANDED, cancel::ensureActive) }
            }
            return ZipFile(zipFile).use { zip ->
                require(zip.size() in 1..MAX_ENTRIES)
                val present = linkedMapOf<String, EtaPortableEntry>()
                var total = 0L
                var manifest: EtaPortableManifest? = null
                for (entry in zip.entries()) {
                    cancel.ensureActive()
                    val name = entry.name
                    require(!entry.isDirectory && name !in present)
                    val bound = minOf(limit(name), MAX_EXPANDED - total)
                    require(entry.size in 0..bound)
                    if (name == MANIFEST) {
                        require(manifest == null)
                        val bytes = zip.getInputStream(entry).use { it.readNBytes((bound + 1).toInt()) }
                        require(bytes.size.toLong() == entry.size)
                        manifest = json.decodeFromString<EtaPortableManifest>(bytes.toString(Charsets.UTF_8))
                    } else {
                        val part = partFor(name)
                        val file = File(root, name) // Only fixed paths and validated single-component profile names.
                        Files.createDirectories(file.toPath().parent)
                        val hash = MessageDigest.getInstance("SHA-256")
                        val size = file.outputStream().use { output ->
                            val sink = BoundedSink(output, bound, cancel::ensureActive, hash)
                            zip.getInputStream(entry).use { copy(it, sink, bound, cancel::ensureActive) }
                            sink.size
                        }
                        require(size == entry.size)
                        present[name] = EtaPortableEntry(name, part, size, hash.hex())
                    }
                    total += entry.size
                }
                requireNotNull(manifest).also { validate(it, present, encrypted) }
            }
        } finally {
            zipFile.delete()
        }
    }

    private fun validate(manifest: EtaPortableManifest, present: Map<String, EtaPortableEntry>, encrypted: Boolean) {
        require(manifest.format == "eta-portable-backup" && manifest.version == 1)
        require(manifest.parts.isNotEmpty() && (encrypted || manifest.parts.none { it.requiresPassword }))
        require(manifest.entries.size == present.size && manifest.entries.associateBy { it.name } == present)
        require(present.values.all { it.part in manifest.parts })
        require(manifest.parts.all { it.requiredPath == null || it.requiredPath in present })
    }

    private fun partFor(name: String): EtaPortablePart = when {
        name == SKILL_ZIP -> EtaPortablePart.SKILLS
        name.startsWith("subagents/") && name.endsWith(".md") &&
            io.github.mangi.eta.agent.subagent.SubagentProfileCatalog.isValidName(name.substring(10, name.length - 3)) ->
            EtaPortablePart.SUBAGENTS
        else -> requireNotNull(EtaPortablePart.entries.firstOrNull { it.requiredPath == name })
    }

    private fun limit(name: String): Long = when {
        name == MANIFEST -> 1_000_000
        name == EtaPortablePart.DATA.requiredPath -> EtaBackupJsonStreams.MAX_BACKUP_BYTES
        name == SKILL_ZIP -> 32L * 1024 * 1024
        name.startsWith("subagents/") -> 64 * 1024
        else -> 4L * 1024 * 1024
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(password.toCharArray(), salt, 180_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        } finally {
            key.fill(0)
        }
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, checkActive: () -> Unit) {
        val sink = BoundedSink(output, limit, checkActive)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            sink.write(buffer, 0, count)
        }
    }

    private class BoundedSink(
        private val output: OutputStream,
        private val limit: Long,
        private val checkActive: () -> Unit,
        private val hash: MessageDigest? = null,
    ) : OutputStream() {
        var size = 0L
            private set

        override fun write(value: Int) {
            count(1)
            output.write(value)
            hash?.update(value.toByte())
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count(length)
            output.write(bytes, offset, length)
            hash?.update(bytes, offset, length)
        }

        private fun count(length: Int) {
            checkActive()
            size += length
            if (size > limit) throw EtaPortableSizeLimit()
        }

        override fun flush() = output.flush()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
}

internal class EtaPortablePasswordRequired : IllegalArgumentException()
internal class EtaPortableSizeLimit : IllegalArgumentException()
internal class EtaPortableConflict : IllegalArgumentException()
internal class EtaPortableRuntimeBusy : IllegalStateException()
internal class EtaPortableIncompleteRestore(val committed: Boolean, cause: Throwable) : IllegalStateException(cause)
