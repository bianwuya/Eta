package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderWithModels
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/** The official JSON schema/validation and asset handling, with a selective provider transaction.
 * The stock importer replaces all providers and forgets every old credential; don't call it for a portable restore.
 */
internal object EtaPortableBackupData {
    private fun isCodex(provider: ProviderWithModels) = provider.provider.authMode == ProviderAuthModes.OPENAI_CHATGPT

    suspend fun export(context: Context, output: OutputStream) {
        val settings = SettingsDataStore.settings()
        val revision = SettingsDataStore.officialModelCatalogRevision()
        val database = EtaDatabase.get(context)
        CharacterRepository.initialize(context)
        EtaBackupJsonStreams.export(context, output) { writer ->
            database.withTransaction {
                val providers = database.providerDao().providers().filterNot { isCodex(it) || reservedId(it.provider.id) }.map { (provider, models) ->
                    EtaBackupProvider(if (ProviderAuthModes.isOAuth(provider.authMode)) provider.copy(apiKey = "") else provider, models)
                }
                val selected = providers.firstOrNull { it.provider.id == settings.selectedProviderId }
                val memory = AgentMemoryRepository.snapshot().content
                val dao = database.conversationDao()
                writer.value("format", String.serializer(), EtaBackupDocument.FORMAT)
                writer.value("schemaVersion", Int.serializer(), EtaBackupDocument.SCHEMA_VERSION)
                writer.value("exportedAt", Long.serializer(), System.currentTimeMillis())
                writer.value("providers", ListSerializer(EtaBackupProvider.serializer()), providers)
                writer.value("catalogRevision", Int.serializer(), revision)
                writer.value("selectedProviderId", String.serializer().nullable, selected?.provider?.id)
                writer.value("selectedModelId", String.serializer().nullable, selected?.models?.firstOrNull { it.id == settings.selectedModelId }?.id)
                writer.value("conversationState", ConversationStateEntity.serializer().nullable, dao.state())
                writer.value("memoryMd", String.serializer(), memory)
                val avatars = linkedMapOf<String, String?>()
                writer.beginArray(EtaBackupJsonStreams.CONVERSATIONS)
                val conversations = pages({ dao.conversationEntityRowsPage(PAGE_SIZE, it) }) {
                    val row = dao.restoreConversation(it)
                    writer.record(ConversationEntity.serializer(), row)
                    CharacterBackupTransfer.avatarReference(row)?.let { (id, path) -> if (id !in avatars) avatars[id] = path }
                }
                writer.endArray()
                writer.beginArray(EtaBackupJsonStreams.MESSAGES)
                val messages = pages({ dao.allMessageRowsPage(PAGE_SIZE, it) }) {
                    writer.record(ConversationMessageEntity.serializer(), dao.restoreMessage(it))
                }
                writer.endArray()
                writer.beginArray(EtaBackupJsonStreams.CHECKPOINTS)
                pages({ dao.contextCheckpointRowsPage(PAGE_SIZE, it) }) {
                    writer.record(ConversationContextCheckpointEntity.serializer(), dao.restoreCheckpoint(it))
                }
                writer.endArray()
                val roleplay = CharacterBackupTransfer.snapshot(context, avatars)
                writer.value("roleplay", CharacterBackupData.serializer().nullable, roleplay)
                EtaBackupSummary(providers.size, providers.sumOf { it.models.size }, conversations, messages,
                    memory.toByteArray().size, roleplay.characters.size)
            }
        }
    }

    suspend fun stage(context: Context, file: File): EtaBackupJsonStreams.StagedDocument {
        var sawFormat = false
        val staged = file.inputStream().use { input ->
            EtaBackupJsonStreams.stage(context, input) { if (it == "read_format") sawFormat = true }
        }
        try {
            // An unrelated object with just exportedAt must not inherit the default format and wipe data.
            require(sawFormat)
            EtaBackupValidation.validate(staged)
            checkProtectedProviders(EtaDatabase.get(context).providerDao().providers(), incoming(staged.header))
            return staged
        } catch (failure: Throwable) {
            staged.close()
            throw failure
        }
    }

    /** Called inside the restore's non-cancellable apply phase. onCommitted is before all post-commit actions. */
    suspend fun apply(
        context: Context,
        staged: EtaBackupJsonStreams.StagedDocument,
        onCommitted: () -> Unit,
        forgetCredentials: (String) -> Unit,
    ) {
        val document = staged.header
        val incoming = incoming(document)
        val db = EtaDatabase.get(context)
        val providerDao = db.providerDao()
        val credentials = incoming.mapTo(linkedSetOf()) { it.provider.id }
        var retainedCodex = false
        val previousMemory = AgentMemoryRepository.snapshot().content
        val previousRoleMemories = document.roleplay?.assets.orEmpty().associate {
            it.characterId to CharacterMemoryRepository.snapshot(context, it.characterId).content
        }
        ProviderRepository.mutationMutex.withLock {
            var memoryWriteStarted = false
            val avatars = document.roleplay?.let { CharacterBackupTransfer.restoreAssets(context, it) }.orEmpty()
            try {
                db.withTransaction {
                    val local = providerDao.providers()
                    checkProtectedProviders(local, incoming)
                    retainedCodex = local.any(::isCodex)
                    for (row in local.filterNot(::isCodex)) {
                        if (!reservedId(row.provider.id)) credentials += row.provider.id
                        providerDao.deleteProvider(row.provider.id)
                    }
                    providerDao.insertProvidersWithModels(incoming.map { (provider, models) ->
                        // Do not bind imported OAuth records to an unpurged local slot, even across a crash.
                        val safe = if (ProviderAuthModes.isOAuth(provider.authMode)) {
                            provider.copy(authMode = ProviderAuthModes.API_KEY, apiKey = "", isEnabled = false)
                        } else provider
                        ProviderWithModelsSeed(safe, models)
                    })
                    val dao = db.conversationDao()
                    dao.deleteMessages()
                    dao.deleteContextCheckpoints()
                    dao.deleteConversations()
                    dao.deleteState()
                    staged.conversations { dao.insertConversations(listOf(CharacterBackupTransfer.remapConversation(it, avatars))) }
                    staged.messages { dao.insertMessages(listOf(it)) }
                    staged.checkpoints { dao.insertContextCheckpoints(listOf(it)) }
                    document.conversationState?.let { dao.insertState(it) }
                    document.roleplay?.let { roleplay ->
                        db.characterDao().replaceAll(roleplay.characters.map { it.copy(avatarPath = avatars[it.id]) }, roleplay.persona)
                    }
                    memoryWriteStarted = true
                    AgentMemoryRepository.replaceAll(document.memoryMd)
                    document.roleplay?.let { CharacterBackupTransfer.restoreMemories(context, it) }
                }
            } catch (failure: Throwable) {
                if (memoryWriteStarted) {
                    compensate(failure) { AgentMemoryRepository.replaceAll(previousMemory) }
                    for ((id, memory) in previousRoleMemories) {
                        compensate(failure) { CharacterMemoryRepository.replaceAll(context, id, memory) }
                    }
                }
                CharacterBackupTransfer.discardAssets(avatars, failure)
                throw failure
            }
            onCommitted()
            credentials.forEach(forgetCredentials)
            db.withTransaction {
                for ((provider) in incoming) {
                    if (ProviderAuthModes.isOAuth(provider.authMode)) {
                        check(providerDao.activateRestoredOAuthProvider(provider.id, provider.authMode, provider.isEnabled) == 1)
                    }
                }
            }
        }
        val selected = document.selectedProviderId?.takeIf { id -> incoming.any { it.provider.id == id } }
        val current = SettingsDataStore.settings()
        if (selected != null || current.selectedProviderId?.let { providerDao.providerById(it)?.let(::isCodex) } != true) {
            SettingsDataStore.setSelection(selected, document.selectedModelId.takeIf { selected != null })
        }
        // The legacy catalog refresh also rewrites Codex models via the OpenAI source type.
        val revision = if (retainedCodex) maxOf(document.catalogRevision, OfficialModelCatalog.CURRENT_REVISION)
            else document.catalogRevision
        SettingsDataStore.setOfficialModelCatalogRevision(revision)
        ProviderRepository.ensureBuiltInsMerged() // Also repairs the selection against the current catalog.
    }

    private fun incoming(document: EtaBackupDocument) =
        document.providers.filter { it.provider.authMode != ProviderAuthModes.OPENAI_CHATGPT }

    private fun reservedId(id: String) = id.startsWith("imagegen:")

    private fun checkProtectedProviders(local: List<ProviderWithModels>, incoming: List<EtaBackupProvider>) {
        val retained = local.filter(::isCodex)
        val ids = retained.mapTo(hashSetOf()) { it.provider.id }
        val modelIds = retained.flatMap { it.models }.mapTo(hashSetOf()) { it.id }
        if (incoming.any { reservedId(it.provider.id) || it.provider.id in ids || it.models.any { model -> model.id in modelIds } }) throw EtaPortableConflict()
    }

    private suspend fun <T> pages(load: suspend (Int) -> List<T>, consume: suspend (T) -> Unit): Int {
        var offset = 0
        while (true) {
            val rows = load(offset)
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                consume(row)
            }
            offset += rows.size
            if (rows.size < PAGE_SIZE) return offset
        }
    }

    private const val PAGE_SIZE = 64
}

internal suspend inline fun compensate(failure: Throwable, action: () -> Unit) {
    try { action() } catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
}
