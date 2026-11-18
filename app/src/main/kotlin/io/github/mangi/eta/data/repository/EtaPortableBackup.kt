package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.local.imagegen.ImageGenConfigStore
import io.github.mangi.eta.local.imagegen.ImageGenEndpoint
import io.github.mangi.eta.local.imagegen.isImageGenModelId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

internal object EtaPortableBackup {
    private val json = EtaPortableBackupArchive.json
    val allParts: Set<EtaPortablePart> = EtaPortablePart.entries.toSet()

    class Staged internal constructor(
        val manifest: EtaPortableManifest,
        private val root: File,
        val isLegacyJson: Boolean,
    ) : AutoCloseable {
        internal fun file(name: String): File = File(root, name)
        internal fun file(part: EtaPortablePart): File = file(requireNotNull(part.requiredPath))
        override fun close() { root.deleteRecursively() }
    }

    data class RestoreResult(val imageSkipped: Boolean = false)

    suspend fun export(
        context: Context,
        parts: Set<EtaPortablePart>,
        password: String?,
        open: () -> OutputStream,
        imageStore: ImageGenConfigStore? = null,
    ): EtaPortableManifest = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val work = Files.createTempDirectory(app.cacheDir.toPath(), "eta-portable-export-").toFile()
        try {
            // Finish and authenticate the package in private cache before touching the document destination.
            val packageFile = File(work, "package")
            val manifest = packageFile.outputStream().buffered().use { output ->
                EtaPortableBackupArchive.write(work, output, parts, password) { entry ->
                    if (EtaPortablePart.DATA in parts) {
                        entry(requireNotNull(EtaPortablePart.DATA.requiredPath)) { EtaPortableBackupData.export(app, it) }
                    }
                    if (EtaPortablePart.SETTINGS in parts) {
                        entry(requireNotNull(EtaPortablePart.SETTINGS.requiredPath)) {
                            it.write(json.encodeToString(EtaPortableBackupPreferences.snapshot().forExport()).toByteArray())
                        }
                    }
                    if (EtaPortablePart.IMAGEGEN in parts) {
                        entry(requireNotNull(EtaPortablePart.IMAGEGEN.requiredPath)) {
                            val config = (imageStore ?: ImageGenConfigStore(app)).read()
                            val metadata = config?.let { saved -> ImageMetadata(saved.endpoint, saved.model) }
                            it.write(json.encodeToString(ImagePayload(metadata)).toByteArray())
                        }
                    }
                    if (EtaPortablePart.MCP in parts) {
                        entry(requireNotNull(EtaPortablePart.MCP.requiredPath)) {
                            val servers = McpServerRepository.servers().map { server ->
                                val bearer = if (server.authorizationType == McpAuthorizationType.BEARER) {
                                    McpServerRepository.bearerToken(server.id).orEmpty()
                                } else ""
                                PortableMcpServer(server, bearer)
                            }
                            it.write(json.encodeToString(servers).toByteArray())
                        }
                    }
                    if (EtaPortablePart.SKILLS in parts) EtaPortableBackupFiles.exportSkills(app, work, entry)
                    if (EtaPortablePart.SUBAGENTS in parts) EtaPortableBackupFiles.exportSubagents(entry)
                }
            }
            val cancel = currentCoroutineContext()
            cancel.ensureActive()
            open().use { output ->
                packageFile.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        cancel.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            manifest
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun inspect(context: Context, password: String?, open: () -> InputStream): Staged {
        var root: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val work = Files.createTempDirectory(app.cacheDir.toPath(), "eta-portable-import-").toFile()
                root = work
                val manifest = open().use { EtaPortableBackupArchive.extract(work, it, password) }
                if (manifest != null) return@withContext Staged(manifest, work, isLegacyJson = false)
                val stages = Staged(EtaPortableManifest(exportedAt = 0, parts = setOf(EtaPortablePart.DATA), entries = emptyList()),
                    work, isLegacyJson = true)
                EtaPortableBackupData.stage(app, stages.file(EtaPortablePart.DATA)).close()
                stages
            }
        } catch (failure: Throwable) {
            // Also handles prompt cancellation when returning from the IO dispatcher.
            withContext(Dispatchers.IO + NonCancellable) { root?.deleteRecursively() }
            throw failure
        }
    }

    suspend fun restore(
        context: Context,
        staged: Staged,
        selected: Set<EtaPortablePart>,
        onApplying: suspend () -> Unit = {},
        onDataCommitted: () -> Unit = {},
        forgetCredentials: (String) -> Unit = ProviderCredentialRepository::forget,
        imageStore: ImageGenConfigStore? = null,
    ): RestoreResult = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() && staged.manifest.parts.containsAll(selected))
        val app = context.applicationContext
        val data = if (EtaPortablePart.DATA in selected) {
            EtaPortableBackupData.stage(app, staged.file(EtaPortablePart.DATA))
        } else null
        try {
            val plan = PortableRestorePlan()
            if (EtaPortablePart.SETTINGS in selected) {
                val prefs = EtaPortableBackupPreferences.decode(staged.file(EtaPortablePart.SETTINGS).readText())
                prefs.prepare(plan)
            }
            val imageSkipped = EtaPortablePart.IMAGEGEN in selected && !prepareImage(
                staged.file(EtaPortablePart.IMAGEGEN).readText(), imageStore ?: ImageGenConfigStore(app), plan,
            )
            if (EtaPortablePart.MCP in selected) prepareMcp(staged.file(EtaPortablePart.MCP).readText(), plan)
            if (EtaPortablePart.SUBAGENTS in selected) EtaPortableBackupFiles.prepareSubagents(staged, plan)
            if (EtaPortablePart.SKILLS in selected) EtaPortableBackupFiles.prepareSkills(app, staged, plan)
            currentCoroutineContext().ensureActive()
            onApplying()
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                var dataCommitted = false
                try {
                    plan.apply()
                    // The official-schema data transaction is last, after all reversible categories.
                    if (data != null) EtaPortableBackupData.apply(app, data, onCommitted = {
                        dataCommitted = true
                        onDataCommitted()
                    }, forgetCredentials = forgetCredentials)
                    RestoreResult(imageSkipped)
                } catch (failure: Throwable) {
                    if (!dataCommitted) plan.rollback(failure)
                    if (dataCommitted || failure.suppressed.isNotEmpty()) {
                        throw EtaPortableIncompleteRestore(dataCommitted, failure)
                    }
                    throw failure
                }
            }
        } finally {
            data?.close()
        }
    }

    @Serializable
    private data class PortableMcpServer(val setting: McpServerSetting, val bearer: String = "")

    private suspend fun prepareMcp(raw: String, plan: PortableRestorePlan) {
        val servers = json.decodeFromString<List<PortableMcpServer>>(raw)
        val current = McpServerRepository.servers()
        val ids = current.mapTo(hashSetOf()) { it.id }
        val urls = current.mapTo(hashSetOf()) { it.url.trim() }
        for ((server, bearer) in servers) {
            val url = validateMcpEndpoint(server.url)
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            require(server.id.isNotBlank() && server.name.isNotBlank())
            require(server.authorizationType in setOf(McpAuthorizationType.NONE, McpAuthorizationType.BEARER))
            require(server.protocolMode in setOf(McpProtocolMode.AUTO, McpProtocolMode.LEGACY, McpProtocolMode.LATEST,
                "2025-06-18", "2025-03-26"))
            require(bearer.length <= 16_384 && bearer.none(Char::isISOControl))
            if (!ids.add(server.id) || !urls.add(url.toString()) || current.any {
                    runCatching { validateMcpEndpoint(it.url) == url }.getOrDefault(false)
                }) throw EtaPortableConflict()
            plan.step {
                if (McpServerRepository.serverById(server.id) != null) throw EtaPortableConflict()
                // Imported servers do not start connecting. Refresh the remote tool list and enable explicitly.
                val added = McpServerRepository.add(server.copy(url = url.toString(), enabled = false, tools = emptyList(),
                    enabledToolNames = emptySet(), lastRefreshedAt = null, lastProtocolVersion = null,
                    toolsExpireAt = null, toolsTruncationReason = null), bearer)
                undo { McpServerRepository.delete(added.id) }
            }
        }
    }

    @Serializable
    private data class ImagePayload(val connection: ImageMetadata?)

    @Serializable
    private data class ImageMetadata(val endpoint: String, val model: String)

    @Serializable
    private data class LegacyImages(val profiles: List<LegacyImage> = emptyList(), val selectedId: String? = null)

    @Serializable
    private data class LegacyImage(
        val source: String = "none", val profileId: String = "", val endpoint: String = "",
        val endpointOverride: String = "", val model: String = "", val protocol: String = "openai_images",
    ) {
        fun metadata(): ImageMetadata? = if (source == "openai_api" && endpointOverride.isBlank() && protocol == "openai_images" &&
            ImageGenEndpoint.resolve(endpoint) != null && isImageGenModelId(model)) ImageMetadata(endpoint, model) else null
    }

    private fun prepareImage(raw: String, store: ImageGenConfigStore, plan: PortableRestorePlan): Boolean {
        val root = json.parseToJsonElement(raw).jsonObject
        val metadata = when {
            "connection" in root -> json.decodeFromString<ImagePayload>(raw).connection
            "profiles" in root -> json.decodeFromString<LegacyImages>(raw).let { old ->
                require(old.profiles.map { it.profileId }.distinct().size == old.profiles.size)
                old.profiles.singleOrNull { it.profileId == old.selectedId }?.metadata()
            }
            "source" in root -> json.decodeFromString<LegacyImage>(raw).metadata()
            else -> error("Unknown image backup shape")
        } ?: return false
        val endpoint = requireNotNull(ImageGenEndpoint.resolve(metadata.endpoint))
        require(isImageGenModelId(metadata.model))
        val previous = store.read() ?: return false
        if (ImageGenEndpoint.resolve(previous.endpoint) != endpoint || store.savedKeyFor(endpoint) == null) return false
        if (previous.model != metadata.model) plan.step {
            require(store.read() == previous)
            val restored = previous.copy(endpoint = endpoint.toString(), model = metadata.model)
            undo { if (store.read() == restored) store.save(endpoint, previous.model, "") }
            store.save(endpoint, metadata.model, "")
        }
        return true
    }
}

/** Register only changes this invocation actually made; never delete a conflicting asset in rollback. */
internal class PortableRestorePlan {
    private val steps = mutableListOf<suspend PortableRestorePlan.() -> Unit>()
    private val undos = mutableListOf<suspend () -> Unit>()
    fun step(apply: suspend PortableRestorePlan.() -> Unit) { steps += apply }
    fun undo(rollback: suspend () -> Unit) { undos += rollback }
    suspend fun apply() { for (step in steps) step(this) }
    suspend fun rollback(failure: Throwable) {
        for (undo in undos.asReversed()) compensate(failure) { undo() }
    }
}
