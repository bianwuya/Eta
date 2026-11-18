package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialStorage
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.local.imagegen.ImageGenConfigStore
import io.github.mangi.eta.local.imagegen.ImageGenEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class EtaPortableBackupTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var context: Context
    private val json = EtaPortableBackupArchive.json
    private val db get() = EtaDatabase.get(context)

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val cache = files.newFolder("cache")
        val privateFiles = files.newFolder("files")
        val noBackup = files.newFolder("no-backup")
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = cache
            override fun getFilesDir() = privateFiles
            override fun getNoBackupFilesDir() = noBackup
        }
        // Room uses this Robolectric sandbox's database path; no real app/database is opened.
        EtaDatabase.closeForTests()
        app.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        McpServerRepository.init(context)
    }

    @After fun closeDatabase() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test fun selectedSettingsLeaveDataUntouchedEvenWhenUnselectedDataIsInvalid() = runBlocking {
        seedData()
        val prefs = EtaPortableBackupPreferences.snapshot()
        val incoming = prefs.copy(app = prefs.app.copy(memoryEnabled = !prefs.app.memoryEnabled,
            selectedProviderId = "not-local", selectedModelId = "not-local"))
        val packageFile = packageOf(EtaPortablePart.SETTINGS to json.encodeToString(incoming), EtaPortablePart.DATA to "not-json")
        inspect(packageFile).use { staged ->
            val validation = runCatching { restore(staged, staged.manifest.parts) }
            assertTrue(validation.isFailure)
            assertEquals(prefs.app, SettingsDataStore.settings())
            assertOldData()
            restore(staged, setOf(EtaPortablePart.SETTINGS))
        }
        assertEquals(incoming.app.memoryEnabled, SettingsDataStore.settings().memoryEnabled)
        assertEquals(prefs.app.selectedProviderId, SettingsDataStore.settings().selectedProviderId)
        assertEquals(prefs.app.selectedModelId, SettingsDataStore.settings().selectedModelId)
        assertOldData()
        assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun validationFailureInAnotherSelectedCategoryPreventsDataReplacement() = runBlocking {
        seedData()
        val raw = json.encodeToString(EtaBackupDocument(exportedAt = 1, memoryMd = "incoming"))
        val invalidMcp = """[{"setting":{"id":"fixture","name":"fixture","url":"not-a-url"}}]"""
        val packageFile = packageOf(EtaPortablePart.DATA to raw, EtaPortablePart.MCP to invalidMcp)
        var applying = false
        inspect(packageFile).use { staged ->
            assertTrue(runCatching {
                EtaPortableBackup.restore(context, staged, staged.manifest.parts,
                    onApplying = { applying = true }, forgetCredentials = { error("must not touch credentials") })
            }.isFailure)
            assertFalse(applying)
            assertOldData()
            assertTrue(McpServerRepository.servers().isEmpty())
        }
    }

    @Test fun legacyJsonRoundTripsWithoutRestoringOrCleaningCodexSlots() = runBlocking {
        seedData()
        val local = OpenAiCompatibleProviderSetting(id = "fixture-codex-local", name = "local Codex", baseUrl = "https://codex.example",
            authMode = ProviderAuthModes.OPENAI_CHATGPT, models = listOf(Model(id = "fixture-codex-model",
                modelId = "fixture-legacy-model", displayName = "fixture", source = ModelSource.CATALOG)))
        val dao = db.providerDao()
        dao.replaceProvider(local.toEntity(), local.toModelEntities())
        val prior = dao.providerById(local.id)
        val remote = local.copy(id = "fixture-codex-incoming", name = "incoming Codex", models = emptyList())
        val legacy = EtaBackupDocument(schemaVersion = 1, exportedAt = 1,
            providers = listOf(EtaBackupProvider(remote.toEntity().copy(apiKey = "fixture-unused"))),
            conversations = listOf(conversation("fixture-incoming")), memoryMd = "incoming")
        val file = files.newFile("old.json").apply { writeText(json.encodeToString(legacy)) }
        val cleaned = mutableSetOf<String>()
        inspect(file).use { staged ->
            assertTrue(staged.isLegacyJson)
            assertEquals(setOf(EtaPortablePart.DATA), staged.manifest.parts)
            EtaPortableBackup.restore(context, staged, setOf(EtaPortablePart.DATA), forgetCredentials = { cleaned += it })
        }
        assertEquals(prior, dao.providerById(local.id))
        assertNull(dao.providerById(remote.id))
        assertFalse(local.id in cleaned || remote.id in cleaned)
        assertEquals(listOf("fixture-incoming"), db.conversationDao().conversations().map { it.id })
        assertEquals("incoming", AgentMemoryRepository.snapshot().content)
    }

    @Test fun databaseWriteFailureRollsBackDataAndPriorSettingsButNotAfterCommit() = runBlocking {
        seedData()
        val before = EtaPortableBackupPreferences.snapshot()
        val after = before.copy(app = before.app.copy(memoryEnabled = !before.app.memoryEnabled))
        val inputProvider = OpenAiCompatibleProviderSetting(id = "fixture-provider", name = "fixture", baseUrl = "https://provider.example").toEntity()
        val incoming = EtaBackupDocument(exportedAt = 1, providers = listOf(EtaBackupProvider(inputProvider)),
            conversations = listOf(conversation("fixture-incoming")), memoryMd = "incoming")
        val archive = packageOf(EtaPortablePart.SETTINGS to json.encodeToString(after), EtaPortablePart.DATA to json.encodeToString(incoming))
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fixture_interrupt BEFORE INSERT ON conversations
            WHEN NEW.id = 'fixture-incoming' BEGIN SELECT RAISE(ABORT, 'fixture interrupt'); END""")
        inspect(archive).use { staged ->
            assertTrue(runCatching { restore(staged, staged.manifest.parts) }.isFailure)
            assertEquals(before.app, SettingsDataStore.settings())
            assertOldData()
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_interrupt")
            var committed = false
            val error = runCatching {
                EtaPortableBackup.restore(context, staged, staged.manifest.parts, onDataCommitted = { committed = true },
                    forgetCredentials = { error("fixture cleanup failure") })
            }.exceptionOrNull()
            assertTrue(committed)
            assertTrue((error as EtaPortableIncompleteRestore).committed)
        }
        assertEquals(after.app.memoryEnabled, SettingsDataStore.settings().memoryEnabled)
        assertEquals("incoming", AgentMemoryRepository.snapshot().content)
        assertEquals(listOf("fixture-incoming"), db.conversationDao().conversations().map { it.id })
    }

    @Test fun imageArchiveNeverContainsCredentialsAndOnlyRestoresAtSameAuthorizedEndpoint() = runBlocking {
        val slots = mutableMapOf<String, ProviderCredential>()
        val store = ImageGenConfigStore(context, object : ProviderCredentialStorage {
            override fun read(providerId: String) = slots[providerId]
            override fun write(providerId: String, credential: ProviderCredential) { slots[providerId] = credential }
            override fun delete(providerId: String) { slots.remove(providerId) }
        })
        val endpoint = requireNotNull(ImageGenEndpoint.resolve("https://images.example/v1"))
        val saved = store.save(endpoint, "fixture-image", "fixture-image-secret")
        val file = files.newFile("images.etabackup")
        val onlyImage = setOf(EtaPortablePart.IMAGEGEN)
        EtaPortableBackup.export(context, onlyImage, null, file::outputStream, imageStore = store)
        inspect(file).use { staged ->
            val raw = staged.file(EtaPortablePart.IMAGEGEN).readText()
            assertTrue(raw.contains("fixture-image"))
            assertFalse(raw.contains("fixture-image-secret") || raw.contains(saved.credentialId) || raw.contains("credentialId"))
            store.save(endpoint, "other-model", "")
            assertFalse(EtaPortableBackup.restore(context, staged, onlyImage, imageStore = store).imageSkipped)
            assertEquals(saved, store.read())
            val other = store.save(requireNotNull(ImageGenEndpoint.resolve("https://other.example")), "other-model", "other-fixture-key")
            assertTrue(EtaPortableBackup.restore(context, staged, onlyImage, imageStore = store).imageSkipped)
            assertEquals(other, store.read())
        }
        val legacy = packageOf(EtaPortablePart.IMAGEGEN to """{"profiles":[{"source":"codex_subscription","profileId":"old",
            "model":"gpt-image-2","accountId":"unused"}],"selectedId":"old"}""")
        inspect(legacy).use { staged ->
            assertTrue(EtaPortableBackup.restore(context, staged, onlyImage, imageStore = store).imageSkipped)
        }
        assertEquals("other-model", store.read()?.model)
    }

    private suspend fun seedData() {
        db.conversationDao().insertConversations(listOf(conversation("fixture-old")))
        AgentMemoryRepository.replaceAll("old memory")
        SettingsDataStore.setSelection("local-provider", "local-model")
    }

    private suspend fun assertOldData() {
        assertEquals(listOf("fixture-old"), db.conversationDao().conversations().map { it.id })
        assertEquals("old memory", AgentMemoryRepository.snapshot().content)
    }

    private fun conversation(id: String) = ConversationEntity(id, id, thinkingEnabled = false, createdAt = 1, updatedAt = 1)

    private suspend fun packageOf(vararg payload: Pair<EtaPortablePart, String>): File = files.newFile().also { file ->
        val parts = payload.mapTo(linkedSetOf()) { it.first }
        file.outputStream().use { output ->
            EtaPortableBackupArchive.write(files.root, output, parts, PASSWORD.takeIf { parts.any { it.requiresPassword } }) { entry ->
                for ((part, content) in payload) entry(requireNotNull(part.requiredPath)) { it.write(content.toByteArray()) }
            }
        }
    }

    private suspend fun inspect(file: File) = EtaPortableBackup.inspect(context, PASSWORD, file::inputStream)
    private suspend fun restore(staged: EtaPortableBackup.Staged, parts: Set<EtaPortablePart>) =
        EtaPortableBackup.restore(context, staged, parts, forgetCredentials = { error("must not clean credentials") })

    private companion object { const val PASSWORD = "fixture-password" }
}
