package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.skill.BUILTIN_SKILL_SOURCE
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.USER_SKILL_SOURCE
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.local.imagegen.*
import io.github.mangi.eta.testing.CodexUsageFixture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaPortableBackupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val portableJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Before fun setup() {
        TerminalRuntime.initialize(context)
        Prefs.initLocal(context)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        EtaDatabase.get(context).openHelper.writableDatabase
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        ProviderRepository.resetObservationForTests()
        AgentMemoryRepository.init(context)
        McpServerRepository.init(context)
        CharacterRepository.initialize(context)
        resetSharedState()
        SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
    }

    @After fun tearDown() {
        resetSharedState()
        ProviderRepository.resetObservationForTests()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    private fun resetSharedState() {
        runBlocking {
            SettingsDataStore.updateSettings { Settings() }
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
            SettingsDataStore.setSpeechSettings(SpeechSettings())
            SettingsDataStore.setWebToolsSettings(WebToolsSettings())
            SettingsDataStore.setSubagentSettings(SubagentSettings())
            SettingsDataStore.setCompactionSettings(CompactionSettings())
            SettingsDataStore.setConversationTitleSettings(ConversationTitleSettings())
            SettingsDataStore.setAssistantOverlaySettings(AssistantOverlaySettings())
            listOf("backup-openai", "local-codex-target", "local-codex-exported", "backup-prov", "keep-provider").forEach {
                SettingsDataStore.clearSelectedModelIdForProvider(it)
            }
        }
        AgentMemoryRepository.replaceAll("")
        SubagentProfileCatalog.defaultAgentsDir().deleteRecursively()
        File(context.filesDir, "roleplay").deleteRecursively()
        SkillRuntime.skillsRoot(context).deleteRecursively()
        File(context.filesDir, ".eta-skill-installer").deleteRecursively()
        SkillRuntime::class.java.getDeclaredField("sharedIndexService").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences(Prefs.LOCAL_AGENT_GROUP, 0).edit().clear().commit()
        context.getSharedPreferences("eta_roleplay", 0).edit().clear().commit()
        ImageGenAvailability.reload(context)
    }
    @Test fun allImageProfilesSurvivePortableRoundTripWithoutCredentials() = runBlocking {
        ImageGenConfigStore.write(context, ImageGenConfig(profileId = "one", source = ImageGenSource.OPENAI_API,
            name = "first", endpoint = "https://one.example", model = "first-image", keyConfigured = true, credentialId = "private-slot"))
        ImageGenConfigStore.write(context, ImageGenConfig(profileId = "two", source = ImageGenSource.OPENAI_API,
            name = "second", endpoint = "https://two.example", model = "second-image"))
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.IMAGEGEN))
        ImageGenConfigStore.remove(context, "one"); ImageGenConfigStore.remove(context, "two")
        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray())).use { staged ->
            EtaPortableBackup.restore(context, staged, setOf(EtaPortablePart.IMAGEGEN))
        }
        val profiles = ImageGenConfigStore.profiles(context)
        assertEquals(2, profiles.profiles.size)
        assertEquals(setOf("first-image", "second-image"), profiles.profiles.map { it.model }.toSet())
        assertTrue(profiles.profiles.none { it.keyConfigured || it.credentialId.isNotBlank() || it.accountId.isNotBlank() })
        assertFalse(Json.encodeToString(profiles).contains("private-slot"))
    }
    @Test fun settingsRoundTripIncludesChildPriorityCompactionAndOfficialSettings() = runBlocking {
        SettingsDataStore.setSubagentSettings(SubagentSettings(modelPriority = listOf("fast", "backup")))
        SettingsDataStore.setCompactionSettings(CompactionSettings(modelId = "summary", reasoningEffort = "high", prompt = "keep all constraints"))
        SettingsDataStore.setConversationTitleSettings(ConversationTitleSettings(enabled = false))
        SettingsDataStore.setAssistantOverlaySettings(AssistantOverlaySettings(modelId = "voice"))
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.SETTINGS), "fixture-password")
        SettingsDataStore.setSubagentSettings(SubagentSettings())
        SettingsDataStore.setCompactionSettings(CompactionSettings())
        SettingsDataStore.setConversationTitleSettings(ConversationTitleSettings())
        SettingsDataStore.setAssistantOverlaySettings(AssistantOverlaySettings())
        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password").use { staged ->
            EtaPortableBackup.restore(context, staged, setOf(EtaPortablePart.SETTINGS))
        }
        assertEquals(listOf("fast", "backup"), SettingsDataStore.subagentSettings().modelPriority)
        assertEquals("summary", SettingsDataStore.compactionSettings().modelId)
        assertEquals("keep all constraints", SettingsDataStore.compactionSettings().prompt)
        assertFalse(SettingsDataStore.conversationTitleSettings().enabled)
        assertEquals("voice", SettingsDataStore.assistantOverlaySettings().modelId)
    }

    @Test fun malformedLaterCategoryCannotPartiallyReplaceEarlierSettings() = runBlocking {
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "from-backup"))
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.SETTINGS, EtaPortablePart.IMAGEGEN), "fixture-password")
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "keep-current"))
        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password").use { staged ->
            staged.file("imagegen/config.json").writeText("{invalid")
            assertTrue(runCatching { EtaPortableBackup.restore(context, staged,
                setOf(EtaPortablePart.SETTINGS, EtaPortablePart.IMAGEGEN)) }.isFailure)
        }
        assertEquals("keep-current", SettingsDataStore.compactionSettings().prompt)
    }

    @Test fun encryptedPackageRejectsWrongPasswordTruncationAndTailTampering() = runBlocking {
        ImageGenConfigStore.write(context, ImageGenConfig(profileId = "encryption", source = ImageGenSource.OPENAI_API,
            endpoint = "https://fixture.invalid", model = "image"))
        val output = ByteArrayOutputStream()
        EtaPortableBackup.export(context, output, setOf(EtaPortablePart.IMAGEGEN), "fixture-password")
        val bytes = output.toByteArray()
        assertTrue(runCatching { EtaPortableBackup.inspect(context,
            ByteArrayInputStream(bytes), "wrong-password").close() }.isFailure)
        for (removed in listOf(1, 16, 32)) {
            assertTrue("truncated by $removed", runCatching { EtaPortableBackup.inspect(context,
                ByteArrayInputStream(bytes.copyOf(bytes.size - removed)), "fixture-password").close() }.isFailure)
        }
        for (offset in listOf(1, 16, 24)) {
            val corrupt = bytes.copyOf().apply { this[size - offset] = (this[size - offset].toInt() xor 1).toByte() }
            assertTrue("tail offset $offset", runCatching { EtaPortableBackup.inspect(context,
                ByteArrayInputStream(corrupt), "fixture-password").close() }.isFailure)
        }
    }

    @Test fun allPartsEmptyRoundTripSucceedsAcrossAllCategories() = runBlocking {
        val bytes = ByteArrayOutputStream()
        val manifest = EtaPortableBackup.export(context, bytes, EtaPortableBackup.allParts, "fixture-password")
        assertEquals(EtaPortableBackup.allParts, manifest.parts)

        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password").use { staged ->
            EtaPortableBackup.restore(context, staged, EtaPortableBackup.allParts)
        }

        assertTrue(ImageGenConfigStore.profiles(context).profiles.isEmpty())
        assertTrue(McpServerRepository.servers().isEmpty())
        assertTrue(SubagentProfileCatalog.listCustom().isEmpty())
        assertEquals("", AgentMemoryRepository.snapshot().content)
        assertTrue(SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
            .none { it.source == USER_SKILL_SOURCE })
    }

    @Test fun allPartsRoundTripRestoresEveryCategoryAndPreservesLocalCodexAccounts() = runBlocking {
        val exportedProvider = OpenAiCompatibleProviderSetting(
            id = "backup-openai",
            name = "备份网关",
            baseUrl = "https://gateway.example/v1",
            apiKey = "sk-portable-secret",
            models = listOf(Model(id = "backup-model-1", modelId = "gpt-6.1-sol", displayName = "Sol")),
        )
        ProviderRepository.addProvider(exportedProvider)
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "local-codex-exported",
            name = "导出前本地订阅",
            baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "codex-exported-m", modelId = "gpt-5.4", displayName = "Codex")),
        ))
        SettingsDataStore.setSelection("backup-openai", "backup-model-1")
        AgentMemoryRepository.replaceAll("# 导出记忆\n保留条目")
        val dao = EtaDatabase.get(context).conversationDao()
        dao.replaceAll(
            conversations = listOf(ConversationEntity("conv-portable", "分类备份对话", true, createdAt = 1L, updatedAt = 2L)),
            messages = listOf(ConversationMessageEntity("msg-portable", "conv-portable", 0, "user", "你好，备份")),
            contextCheckpoints = listOf(ConversationContextCheckpointEntity("conv-portable", "[]")),
            state = ConversationStateEntity(selectedConversationId = "conv-portable"),
        )
        SettingsDataStore.setSubagentSettings(SubagentSettings(modelPriority = listOf("m-fast", "m-deep")))
        SettingsDataStore.setCompactionSettings(CompactionSettings(modelId = "compact-m", reasoningEffort = "medium", prompt = "compact all"))
        SettingsDataStore.setConversationTitleSettings(ConversationTitleSettings(enabled = false, modelId = "title-m", reasoningEffort = "low"))
        SettingsDataStore.setAssistantOverlaySettings(AssistantOverlaySettings(modelId = "overlay-m", reasoningEffort = "high"))
        SettingsDataStore.setSpeechSettings(SpeechSettings(asr = AsrProvider.MIMO, tts = TtsProvider.DEEPSEEK, qwenVoice = "Aria"))
        SettingsDataStore.setWebToolsSettings(WebToolsSettings(searchBackend = WebToolBackend.DEEPSEEK))
        ImageGenConfigStore.write(context, ImageGenConfig(
            profileId = "img-orig", source = ImageGenSource.OPENAI_API, name = "生图连接",
            endpoint = "https://img.example/v1", model = "gpt-image-2", keyConfigured = true, credentialId = "img-secret-slot",
        ))
        McpServerRepository.add(McpServerSetting(
            id = "mcp-portable-1", name = "Local Docs", url = "https://mcp.example/sse",
            enabled = true, authorizationType = McpAuthorizationType.NONE,
        ), "")
        SubagentProfileCatalog.saveCustomProfile(
            name = "bill-analyzer", description = "分析账单", prompt = "你是账单分析助手", whenToUse = "统计账单时",
        )
        installUserSkill("custom-helper", "Custom helper skill.")
        SkillRuntime.createIndexService(context).setSkillEnabled("skill-creator", false)

        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, EtaPortableBackup.allParts, "fixture-password")

        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        EtaDatabase.get(context).openHelper.writableDatabase
        ProviderRepository.resetObservationForTests()
        resetSharedState()
        SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)

        CodexUsageFixture(context, "local-codex-target").use { fixture ->
            ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
                id = "local-codex-target",
                name = "目标端 Codex 订阅",
                baseUrl = "https://api.openai.com/v1",
                authMode = ProviderAuthModes.OPENAI_CHATGPT,
                models = listOf(Model(id = "local-codex-model", modelId = "gpt-5.4", displayName = "Target Codex")),
            ))
            SettingsDataStore.setSelection("local-codex-target", "local-codex-model")
            AgentMemoryRepository.replaceAll("target-memory")

            EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password").use { staged ->
                assertFalse(staged.file("data/legacy.json").readText().contains("local-codex-exported"))
                EtaPortableBackup.restore(context, staged, EtaPortableBackup.allParts)
            }

            assertEquals("sk-portable-secret", ProviderRepository.providerById("backup-openai")?.apiKey)
            assertEquals("你好，备份", EtaDatabase.get(context).conversationDao().messages().single().content)
            assertEquals("# 导出记忆\n保留条目", AgentMemoryRepository.snapshot().content)

            val retainedCodex = ProviderRepository.providerById("local-codex-target")
            assertNotNull(retainedCodex)
            assertEquals(ProviderAuthModes.OPENAI_CHATGPT, retainedCodex!!.authMode)
            assertEquals(listOf("local-codex-model"), retainedCodex.models.map { it.id })
            assertEquals(
                setOf(fixture.first, fixture.second),
                ProviderCredentialRepository.accounts("local-codex-target", ProviderAuthModes.OPENAI_CHATGPT)
                    .map { it.accountId }.toSet(),
            )

            assertEquals(listOf("m-fast", "m-deep"), SettingsDataStore.subagentSettings().modelPriority)
            assertEquals("compact all", SettingsDataStore.compactionSettings().prompt)
            assertFalse(SettingsDataStore.conversationTitleSettings().enabled)
            assertEquals("overlay-m", SettingsDataStore.assistantOverlaySettings().modelId)
            assertEquals(AsrProvider.MIMO, SettingsDataStore.speechSettings().asr)
            assertEquals(WebToolBackend.DEEPSEEK, SettingsDataStore.webToolsSettings().searchBackend)

            val restoredImages = ImageGenConfigStore.profiles(context).profiles
            assertEquals(1, restoredImages.size)
            assertEquals("gpt-image-2", restoredImages.single().model)
            assertFalse(restoredImages.single().keyConfigured)
            assertTrue(restoredImages.single().credentialId.isBlank())
            assertTrue(restoredImages.single().accountId.isBlank())
            assertTrue(restoredImages.single().providerId.isBlank())

            assertEquals(listOf("https://mcp.example/sse"), McpServerRepository.servers().map { it.url })
            assertEquals(listOf("bill-analyzer"), SubagentProfileCatalog.listCustom().map { it.name })

            val skills = SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
            val userSkill = skills.single { it.id == "custom-helper" }
            assertEquals(USER_SKILL_SOURCE, userSkill.source)
            assertTrue(userSkill.installed && userSkill.enabled)
            val builtin = skills.single { it.id == "skill-creator" }
            assertEquals(BUILTIN_SKILL_SOURCE, builtin.source)
            assertFalse(builtin.enabled)
        }
    }

    @Test fun settingsOnlyRestorePreservesCurrentProviderAndModelSelection() = runBlocking {
        SettingsDataStore.setSelection("backup-prov", "backup-m")
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "imported-compaction"))
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.SETTINGS), "fixture-password")

        SettingsDataStore.setSelection("keep-provider", "keep-model")
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "old-compaction"))
        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password").use { staged ->
            EtaPortableBackup.restore(context, staged, setOf(EtaPortablePart.SETTINGS))
        }

        val settings = SettingsDataStore.settings()
        assertEquals("keep-provider", settings.selectedProviderId)
        assertEquals("keep-model", settings.selectedModelId)
        assertEquals("imported-compaction", SettingsDataStore.compactionSettings().prompt)
    }

    @Test fun legacySingleImageConfigParsesNoneAndRemapsActiveSourcesWithoutCredentials() = runBlocking {
        val noneJson = portableJson.encodeToString(ImageGenConfig(source = ImageGenSource.NONE))
        assertFalse(noneJson.contains("\"profiles\""))
        val noneArchive = buildPortableArchive(setOf(EtaPortablePart.IMAGEGEN), mapOf("imagegen/config.json" to noneJson.toByteArray()))
        EtaPortableBackup.inspect(context, ByteArrayInputStream(noneArchive)).use { staged ->
            EtaPortableBackup.restore(context, staged, setOf(EtaPortablePart.IMAGEGEN))
        }
        assertEquals(ImageGenProfiles(), ImageGenConfigStore.profiles(context))

        val openAiLegacyJson = portableJson.encodeToString(ImageGenConfig(
            profileId = "legacy-openai", source = ImageGenSource.OPENAI_API, providerId = "old-provider",
            accountId = "old-account", name = "旧生图", endpoint = "https://legacy-img.example/v1",
            model = "gpt-image-2", keyConfigured = true, credentialId = "old-cred", retiredCredentialIds = listOf("old-ret"),
        ))
        val codexLegacyJson = portableJson.encodeToString(ImageGenConfig(
            profileId = "", source = ImageGenSource.CODEX_SUBSCRIPTION, providerId = "old-codex-provider",
            accountId = "old-codex-account", name = "旧订阅生图", model = "gpt-image-2",
            keyConfigured = true, credentialId = "old-codex-cred", retiredCredentialIds = listOf("ret-2"),
        ))

        for ((rawJson, oldId, expectedSource) in listOf(
            Triple(openAiLegacyJson, "legacy-openai", ImageGenSource.OPENAI_API),
            Triple(codexLegacyJson, "legacy", ImageGenSource.CODEX_SUBSCRIPTION),
        )) {
            assertFalse(rawJson.contains("\"profiles\""))
            ImageGenConfigStore.writeProfiles(context, ImageGenProfiles())
            val archive = buildPortableArchive(setOf(EtaPortablePart.IMAGEGEN), mapOf("imagegen/config.json" to rawJson.toByteArray()))
            EtaPortableBackup.inspect(context, ByteArrayInputStream(archive)).use { staged ->
                EtaPortableBackup.restore(context, staged, setOf(EtaPortablePart.IMAGEGEN))
            }
            val restored = ImageGenConfigStore.profiles(context)
            assertEquals(1, restored.profiles.size)
            val profile = restored.profiles.single()
            assertEquals(expectedSource, profile.source)
            assertTrue(profile.profileId.isNotBlank() && profile.profileId != oldId)
            assertEquals(profile.profileId, restored.selectedId)
            assertEquals("", profile.accountId)
            assertEquals("", profile.providerId)
            assertFalse(profile.keyConfigured)
            assertEquals("", profile.credentialId)
            assertTrue(profile.retiredCredentialIds.isEmpty())
        }
    }

    @Test fun preflightConflictsRejectBeforeMutatingAnyLiveState() = runBlocking {
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "keep-preflight"))
        ImageGenConfigStore.write(context, ImageGenConfig(
            profileId = "keep-img", source = ImageGenSource.OPENAI_API,
            endpoint = "https://keep-img.example/v1", model = "gpt-image-2",
        ))
        McpServerRepository.add(McpServerSetting(
            id = "mcp-existing", name = "Existing", url = "https://existing.example/mcp",
            authorizationType = McpAuthorizationType.NONE,
        ), "")
        SubagentProfileCatalog.saveCustomProfile("existing-agent", "已有子代理", "已有角色提示词")

        val sentinelPrefs = portableJson.encodeToString(EtaPortablePreferences(
            app = Settings(), speech = SpeechSettings(), webTools = WebToolsSettings(),
            subagents = SubagentSettings(), compaction = CompactionSettings(prompt = "must-not-apply"),
        )).toByteArray()
        val sentinelImage = portableJson.encodeToString(ImageGenProfiles(
            profiles = listOf(ImageGenConfig(profileId = "extra-img", source = ImageGenSource.OPENAI_API,
                endpoint = "https://extra.example/v1", model = "gpt-image-2")),
            selectedId = "extra-img",
        )).toByteArray()

        fun mcpEntry(id: String, url: String) = EtaPortableMcpServer(
            McpServerSetting(id = id, name = id, url = url, authorizationType = McpAuthorizationType.NONE), "",
        )
        val subagentValid = subagentMarkdown("valid-extra", "extra", "body").toByteArray()
        val badSubagentCases = listOf(
            "subagents/existing-agent.md" to subagentMarkdown("existing-agent", "dup", "body").toByteArray(),
            "subagents/Invalid_Name.md" to subagentMarkdown("invalid-name", "bad", "body").toByteArray(),
            "subagents/alpha-agent.md" to subagentMarkdown("beta-agent", "mismatch", "body").toByteArray(),
        )
        val mcpConflictCases = listOf(
            listOf(mcpEntry("dup-id", "https://a.example/mcp"), mcpEntry("dup-id", "https://b.example/mcp")),
            listOf(mcpEntry("id-1", " https://dup.example/mcp "), mcpEntry("id-2", "https://dup.example/mcp")),
            listOf(mcpEntry("mcp-existing", "https://other.example/mcp")),
            listOf(mcpEntry("mcp-new", " https://existing.example/mcp ")),
        )

        val conflictArchives = mcpConflictCases.map { servers ->
            val parts = setOf(EtaPortablePart.SETTINGS, EtaPortablePart.IMAGEGEN, EtaPortablePart.MCP)
            buildPortableArchive(parts, mapOf(
                "settings/config.json" to sentinelPrefs,
                "imagegen/config.json" to sentinelImage,
                "mcp/servers.json" to portableJson.encodeToString(servers).toByteArray(),
            )) to parts
        } + badSubagentCases.map { (path, content) ->
            val parts = setOf(EtaPortablePart.SETTINGS, EtaPortablePart.IMAGEGEN, EtaPortablePart.SUBAGENTS)
            buildPortableArchive(parts, mapOf(
                "settings/config.json" to sentinelPrefs,
                "imagegen/config.json" to sentinelImage,
                "subagents/valid-extra.md" to subagentValid,
                path to content,
            )) to parts
        }

        for ((archive, parts) in conflictArchives) {
            EtaPortableBackup.inspect(context, ByteArrayInputStream(archive)).use { staged ->
                assertTrue(runCatching { EtaPortableBackup.restore(context, staged, parts) }.isFailure)
            }
            assertEquals("keep-preflight", SettingsDataStore.compactionSettings().prompt)
            assertEquals(listOf("keep-img"), ImageGenConfigStore.profiles(context).profiles.map { it.profileId })
            assertEquals(listOf("mcp-existing"), McpServerRepository.servers().map { it.id })
            assertEquals(listOf("existing-agent"), SubagentProfileCatalog.listCustom().map { it.name })
        }
    }

    @Test fun dataImportFailureRollsBackSettingsImageGenSubagentsMcpAndSkillsInReverse() = runBlocking {
        val blocked = CharacterRepository.create(CharacterCardCodec.create("阻塞角色"))
        CharacterMemoryRepository.replaceAll(context, blocked.id, "backup-role-memory")

        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "backup-rollback"))
        SettingsDataStore.setSubagentSettings(SubagentSettings(modelPriority = listOf("backup-prio")))
        ImageGenConfigStore.write(context, ImageGenConfig(
            profileId = "backup-img", source = ImageGenSource.OPENAI_API,
            endpoint = "https://backup-img.example/v1", model = "gpt-image-2",
        ))
        SubagentProfileCatalog.saveCustomProfile("imported-agent", "导入子代理", "导入提示词")
        McpServerRepository.add(McpServerSetting(
            id = "mcp-imported", name = "Imported", url = "https://imported.example/mcp",
            authorizationType = McpAuthorizationType.NONE,
        ), "")
        installUserSkill("imported-user-skill", "Imported user skill.")
        SkillRuntime.createIndexService(context).setSkillEnabled("skill-creator", true)
        AgentMemoryRepository.replaceAll("backup-memory")

        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, EtaPortableBackup.allParts, "fixture-password")

        assertTrue(SkillRuntime.createIndexService(context).deleteSkill("imported-user-skill"))
        SkillRuntime.createIndexService(context).setSkillEnabled("skill-creator", false)
        McpServerRepository.delete("mcp-imported")
        McpServerRepository.add(McpServerSetting(
            id = "mcp-orig", name = "Original", url = "https://orig.example/mcp",
            authorizationType = McpAuthorizationType.NONE,
        ), "")
        SubagentProfileCatalog.deleteCustomProfile("imported-agent")
        SubagentProfileCatalog.saveCustomProfile("orig-agent", "原代理", "原提示词")
        ImageGenConfigStore.writeProfiles(context, ImageGenProfiles(
            profiles = listOf(ImageGenConfig(
                profileId = "orig-img", source = ImageGenSource.OPENAI_API,
                endpoint = "https://orig-img.example/v1", model = "gpt-image-2",
            )),
            selectedId = "orig-img",
        ))
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "before-rollback"))
        SettingsDataStore.setSubagentSettings(SubagentSettings(modelPriority = listOf("orig-prio")))
        AgentMemoryRepository.replaceAll("orig-memory")
        CharacterMemoryRepository.discard(context, blocked.id)
        val blocker = File(context.filesDir, "roleplay/${blocked.id}/memory")
        check(blocker.parentFile!!.mkdirs() || blocker.parentFile!!.isDirectory)
        blocker.writeText("阻止创建角色记忆目录")

        var committed = false
        try {
            val failure = EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password").use { staged ->
                runCatching {
                    EtaPortableBackup.restore(context, staged, EtaPortableBackup.allParts, onDataCommitted = { committed = true })
                }.exceptionOrNull()
            }
            assertTrue("expected IllegalStateException, got $failure", failure is IllegalStateException)
            assertEquals("恢复失败且部分回滚未完成，请保留备份并核对当前状态。", failure?.message)
            assertNotNull("restore must retain the originating write failure", failure?.cause)
            val details = generateSequence(failure) { it.cause }.joinToString("\n") { node ->
                node.toString() + node.suppressed.joinToString { it.toString() }
            }
            assertTrue("write failure must remain diagnosable: $details", details.contains("记忆") || details.contains("memory"))
            assertFalse(committed)
            assertEquals("before-rollback", SettingsDataStore.compactionSettings().prompt)
            assertEquals(listOf("orig-prio"), SettingsDataStore.subagentSettings().modelPriority)
            assertEquals(listOf("orig-img"), ImageGenConfigStore.profiles(context).profiles.map { it.profileId })
            assertEquals(listOf("orig-agent"), SubagentProfileCatalog.listCustom().map { it.name })
            assertEquals(listOf("mcp-orig"), McpServerRepository.servers().map { it.id })
            val skills = SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
            assertTrue(skills.none { it.id == "imported-user-skill" })
            assertFalse(skills.single { it.id == "skill-creator" }.enabled)
            assertEquals("orig-memory", AgentMemoryRepository.snapshot().content)
            assertNotNull(CharacterRepository.get(blocked.id))
            assertTrue(EtaDatabase.get(context).conversationDao().conversations().isEmpty())
        } finally {
            blocker.delete()
        }
    }

    @Test fun cancellingBeforeCommitLeavesLiveStateUntouchedAndCleansStagedDir() = runBlocking {
        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "from-backup"))
        ImageGenConfigStore.write(context, ImageGenConfig(
            profileId = "backup-img", source = ImageGenSource.OPENAI_API,
            endpoint = "https://img.example/v1", model = "gpt-image-2",
        ))
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.SETTINGS, EtaPortablePart.IMAGEGEN), "fixture-password")

        SettingsDataStore.setCompactionSettings(CompactionSettings(prompt = "keep-current"))
        ImageGenConfigStore.writeProfiles(context, ImageGenProfiles())

        val staged = EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray()), "fixture-password")
        assertTrue(staged.root.isDirectory)
        var failure: Throwable? = null
        val job = launch {
            coroutineContext[Job]!!.cancel()
            failure = runCatching { EtaPortableBackup.restore(context, staged, staged.manifest.parts) }.exceptionOrNull()
        }
        job.join()
        staged.close()

        assertTrue("expected CancellationException, got $failure", failure is CancellationException)
        assertFalse(staged.root.exists())
        assertEquals("keep-current", SettingsDataStore.compactionSettings().prompt)
        assertTrue(ImageGenConfigStore.profiles(context).profiles.isEmpty())
    }

    @Test fun legacySkillWithoutFrontmatterCanRoundTripWithoutChangingTheOriginal() = runBlocking {
        val root = File(SkillRuntime.skillsRoot(context), "legacy-entry").also { check(it.mkdirs()) }
        val markdown = "# Legacy instruction\n\nKeep the user workflow.\n"
        File(root, "SKILL.md").writeText(markdown)
        File(root, "references").mkdirs()
        File(root, "references/example.txt").writeText("legacy reference")
        val index = SkillRuntime.createIndexService(context)
        assertTrue(index.listSkillsForManagement(forceRefresh = true).any { it.id == "legacy-entry" && it.installed })
        index.setSkillEnabled("legacy-entry", false)
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.SKILLS))
        assertEquals(markdown, File(root, "SKILL.md").readText())
        assertTrue(index.deleteSkill("legacy-entry"))
        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray())).use { staged ->
            EtaPortableBackup.restore(context, staged, staged.manifest.parts)
        }
        val restored = index.listSkillsForManagement(forceRefresh = true).single { it.id == "legacy-entry" }
        assertFalse(restored.enabled)
        assertTrue(File(restored.skillFilePath).readText().contains(markdown.trim()))
        assertEquals("legacy reference", File(restored.rootPath, "references/example.txt").readText())
    }

    @Test fun legacySkillNameNormalizationPreservesMetadataAndBody() = runBlocking {
        val root = File(SkillRuntime.skillsRoot(context), "legacy-named").also { check(it.mkdirs()) }
        val markdown = "---\nname: Legacy_Name\ndescription: Legacy fixture.\ncompatibility: network required\nmetadata:\n  model: fixture-model\n  reasoning: high\n---\n\n# Keep body\n"
        File(root, "SKILL.md").writeText(markdown)
        val index = SkillRuntime.createIndexService(context)
        val original = index.listSkillsForManagement(forceRefresh = true).single { it.id == "legacy-name" }
        val bytes = ByteArrayOutputStream()
        EtaPortableBackup.export(context, bytes, setOf(EtaPortablePart.SKILLS))
        assertEquals(markdown, File(root, "SKILL.md").readText())
        assertTrue(index.deleteSkill(original.id))
        EtaPortableBackup.inspect(context, ByteArrayInputStream(bytes.toByteArray())).use { staged ->
            EtaPortableBackup.restore(context, staged, staged.manifest.parts)
        }
        val restored = index.listSkillsForManagement(forceRefresh = true).single { it.id == original.id }
        assertEquals(original.compatibility, restored.compatibility)
        assertEquals(original.metadata, restored.metadata)
        assertTrue(File(restored.skillFilePath).readText().endsWith("# Keep body\n"))
    }

    private fun subagentMarkdown(name: String, description: String, body: String): String =
        listOf("---", "name: $name", "description: $description", "---", "", body).joinToString("\n")

    private fun installUserSkill(id: String, description: String) {
        val markdown = listOf("---", "name: $id", "description: $description", "---", "", "# $id").joinToString("\n")
        val zipBytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("skills/$id/SKILL.md"))
                zip.write(markdown.toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val result = SkillRuntime.createPackageInstaller(context)
            .installRepositoryZip({ ByteArrayInputStream(zipBytes) }, listOf(id))
        assertTrue("unexpected skill install result: $result", result is SkillInstallResult.Success)
    }

    private fun buildPortableArchive(parts: Set<EtaPortablePart>, entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ETAZIP2".toByteArray(Charsets.US_ASCII))
        ZipOutputStream(out).use { zip ->
            val manifestEntries = entries.map { (name, data) ->
                val part = when {
                    name.startsWith("data/") -> EtaPortablePart.DATA
                    name.startsWith("settings/") -> EtaPortablePart.SETTINGS
                    name.startsWith("skills/") -> EtaPortablePart.SKILLS
                    name.startsWith("subagents/") -> EtaPortablePart.SUBAGENTS
                    name.startsWith("imagegen/") -> EtaPortablePart.IMAGEGEN
                    name.startsWith("mcp/") -> EtaPortablePart.MCP
                    else -> error("unexpected entry: $name")
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
                val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                EtaPortableEntry(name, part, data.size.toLong(), sha)
            }
            val manifest = EtaPortableManifest(exportedAt = 1L, parts = parts, entries = manifestEntries)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(portableJson.encodeToString(manifest).toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
