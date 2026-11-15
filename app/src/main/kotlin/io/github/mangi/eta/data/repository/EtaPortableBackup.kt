package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.skill.BUILTIN_SKILL_SOURCE
import io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.USER_SKILL_SOURCE
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.local.imagegen.ImageGenConfig
import io.github.mangi.eta.local.imagegen.ImageGenConfigStore
import io.github.mangi.eta.local.imagegen.ImageGenSource
import io.github.mangi.eta.local.imagegen.ImageGenProfiles
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.CompactionSettings
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.AssistantOverlaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A portable container of independently verifiable parts. Codex login pools are NEVER exported. */
@Serializable
internal enum class EtaPortablePart { DATA, SKILLS, SUBAGENTS, SETTINGS, IMAGEGEN, MCP }

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

@Serializable
internal data class EtaPortableSkillState(val id: String, val enabled: Boolean, val source: String)

@Serializable
internal data class EtaPortableMcpServer(val setting: McpServerSetting, val bearer: String)

@Serializable
internal data class EtaPortablePreferences(
    val app: Settings,
    val speech: SpeechSettings,
    val webTools: WebToolsSettings,
    val subagents: SubagentSettings,
    val compaction: CompactionSettings,
    val conversationTitle: ConversationTitleSettings? = null,
    val assistantOverlay: AssistantOverlaySettings? = null,
)

internal object EtaPortableBackup {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val magicPlain = "ETAZIP2".toByteArray(Charsets.US_ASCII)
    private val magicLocked = "ETAPKG2".toByteArray(Charsets.US_ASCII)
    private const val MAX_ENTRIES = 4096
    private const val MAX_BYTES = 2_000_000_000L

    val allParts: Set<EtaPortablePart> = EtaPortablePart.entries.toSet()

    /** Data contains API keys, so packages with DATA must use user-supplied portable encryption. */
    suspend fun export(
        context: Context,
        output: OutputStream,
        parts: Set<EtaPortablePart>,
        password: String? = null,
    ): EtaPortableManifest = withContext(Dispatchers.IO) {
        require(parts.isNotEmpty()) { "请选择至少一项备份内容" }
        require(parts.none { it in setOf(EtaPortablePart.DATA, EtaPortablePart.SETTINGS, EtaPortablePart.MCP) } || !password.isNullOrBlank()) {
            "包含对话、设置或 MCP 的备份必须设置加密密码"
        }
        val app = context.applicationContext
        val manifestEntries = mutableListOf<EtaPortableEntry>()
        val stream = encryptedOutput(output, password)
        val tempSkillZip = File(app.cacheDir, "eta-skill-pack-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(stream).use { zip ->
                suspend fun dataEntry(name: String, part: EtaPortablePart, write: suspend (OutputStream) -> Unit) {
                    val md = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.putNextEntry(ZipEntry(name))
                    val sink = object : FilterOutputStream(zip) {
                        override fun write(b: Int) {
                            out.write(b); md.update(b.toByte()); size++
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            out.write(b, off, len); md.update(b, off, len); size += len
                        }
                        override fun close() { flush() } // never close the containing ZIP entry
                    }
                    write(sink)
                    sink.flush()
                    zip.closeEntry()
                    manifestEntries += EtaPortableEntry(name, part, size, md.digest().hex())
                }
                suspend fun bytesEntry(name: String, part: EtaPortablePart, data: ByteArray) =
                    dataEntry(name, part) { it.write(data) }

                if (EtaPortablePart.DATA in parts) {
                    dataEntry("data/legacy.json", EtaPortablePart.DATA) { EtaBackupRepository.export(app, it, excludeCodexSubscription = true) }
                }
                if (EtaPortablePart.SETTINGS in parts) {
                    val prefs = EtaPortablePreferences(
                        app = SettingsDataStore.settings(),
                        speech = SettingsDataStore.speechSettings(),
                        webTools = SettingsDataStore.webToolsSettings(),
                        subagents = SettingsDataStore.subagentSettings(),
                        compaction = SettingsDataStore.compactionSettings(),
                        conversationTitle = SettingsDataStore.conversationTitleSettings(),
                        assistantOverlay = SettingsDataStore.assistantOverlaySettings(),
                    )
                    bytesEntry("settings/config.json", EtaPortablePart.SETTINGS, json.encodeToString(prefs).toByteArray())
                }
                if (EtaPortablePart.IMAGEGEN in parts) {
                    val configs = ImageGenConfigStore.profiles(app)
                    val portableConfig = configs.copy(profiles = configs.profiles.map(::portableImageConfig))
                    bytesEntry("imagegen/config.json", EtaPortablePart.IMAGEGEN,
                        json.encodeToString(portableConfig).toByteArray())
                }
                if (EtaPortablePart.MCP in parts) {
                    val servers = McpServerRepository.servers().map { server ->
                        EtaPortableMcpServer(server, McpServerRepository.bearerToken(server.id).orEmpty())
                    }
                    bytesEntry("mcp/servers.json", EtaPortablePart.MCP,
                        json.encodeToString(servers).toByteArray())
                }
                if (EtaPortablePart.SKILLS in parts) {
                    val entries = SkillRuntime.createIndexService(app).listSkillsForManagement(forceRefresh = true)
                    val states = entries.map { EtaPortableSkillState(it.id, it.enabled, it.source) }
                    bytesEntry("skills/states.json", EtaPortablePart.SKILLS, json.encodeToString(states).toByteArray())
                    val userSkills = entries.filter { it.installed && it.source == USER_SKILL_SOURCE }
                    if (userSkills.isNotEmpty()) {
                        ZipOutputStream(tempSkillZip.outputStream().buffered()).use { inner ->
                            userSkills.forEach { entry ->
                                require(entry.id.length in 1..64 && entry.id.matches(Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$"))) {
                                    "旧 Skill ID 不符合当前安装规范，无法生成可恢复备份：${entry.id}"
                                }
                                val root = File(entry.rootPath).canonicalFile.toPath()
                                require(Files.isDirectory(root) && !Files.isSymbolicLink(root)) { "Skill 路径不可读" }
                                File(entry.rootPath).walkTopDown()
                                    .onEnter { dir -> dir.name != ".git" && !Files.isSymbolicLink(dir.toPath()) }
                                    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                                    .forEach { file ->
                                        val rel = root.relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
                                        require(!rel.startsWith("../") && rel.isNotEmpty()) { "Skill 文件越界" }
                                        inner.putNextEntry(ZipEntry("skills/${entry.id}/$rel"))
                                        if (rel == "SKILL.md") inner.write(portableSkillMarkdown(entry, file))
                                        else file.inputStream().use { it.copyTo(inner) }
                                        inner.closeEntry()
                                    }
                            }
                        }
                        dataEntry("skills/user-skills.zip", EtaPortablePart.SKILLS) {
                            tempSkillZip.inputStream().use { input -> input.copyTo(it) }
                        }
                    }
                }
                if (EtaPortablePart.SUBAGENTS in parts) {
                    val profiles = SubagentProfileCatalog.listCustom()
                    for (profile in profiles) {
                        require(SubagentProfileCatalog.isValidName(profile.name)) { "无效的子代理名字" }
                        dataEntry("subagents/${profile.name}.md", EtaPortablePart.SUBAGENTS) {
                            File(requireNotNull(profile.filePath)).inputStream().use { file -> file.copyTo(it) }
                        }
                    }
                }
                val manifest = EtaPortableManifest(
                    exportedAt = System.currentTimeMillis(), parts = parts, entries = manifestEntries,
                )
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(manifest).toByteArray())
                zip.closeEntry()
                manifest
            }
        } finally {
            tempSkillZip.delete()
        }
    }

    private fun portableSkillMarkdown(entry: SkillIndexEntry, file: File): ByteArray {
        val raw = file.readText()
        val parsed = requireNotNull(SkillParser.parseSkillFile(file)) { "Skill 正文不可读" }
        val description = parsed.frontmatter["description"].orEmpty()
        val hasStrictHeader = raw.startsWith("---\n") || raw.startsWith("---\r\n")
        if (hasStrictHeader && parsed.frontmatter["name"] == entry.id && description.isNotBlank() && description.length <= 1024) {
            return file.readBytes()
        }
        // Runtime accepts legacy markdown that the official ZIP installer cannot import.
        // Canonicalize only identity/description in the portable copy; preserve all other metadata.
        val portableDescription = description.ifBlank { "旧版 Skill：${entry.id}" }.take(1024)
        val header = Regex("\\A---\\r?\\n([\\s\\S]*?)\\r?\\n---(?=\\r?\\n|$)").find(raw)
        val retainedLines = mutableListOf<String>()
        var replacingField = false
        header?.groupValues?.get(1)?.lines()?.forEach { line ->
            if (line.startsWith("name:") || line.startsWith("description:")) replacingField = true
            else if (!replacingField || line.isNotBlank() && !line.first().isWhitespace()) {
                replacingField = false
                retainedLines += line
            }
        }
        val remainder = if (header == null) "\n\n$raw" else raw.substring(header.range.last + 1)
        return buildString {
            append("---\nname: ${entry.id}\ndescription: ${json.encodeToString(portableDescription)}\n")
            retainedLines.forEach { append(it).append('\n') }
            append("---").append(remainder)
        }.toByteArray()
    }

    internal class Staged internal constructor(val manifest: EtaPortableManifest, val root: File) : AutoCloseable {
        fun file(path: String): File = File(root, path)
        override fun close() { root.deleteRecursively() }
    }

    /** Fully read and verify every entry before ANY restore can begin. */
    fun inspect(context: Context, input: InputStream, password: String? = null): Staged {
        val root = File(context.cacheDir, "eta-import-${UUID.randomUUID()}")
        check(root.mkdir()) { "无法创建私有校验目录" }
        try {
            val present = linkedMapOf<String, EtaPortableEntry>()
            val names = mutableSetOf<String>()
            var total = 0L
            var count = 0
            var manifest: EtaPortableManifest? = null
            ZipInputStream(encryptedInput(input, password)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "备份包含过多文件" }
                    require(!entry.isDirectory) { "不允许目录条目" }
                    val name = entry.name
                    val segments = name.split('/')
                    require(segments.all { it.isNotBlank() && it != "." && it != ".." && !it.contains('\\') && !it.contains(':') }) {
                        "备份中存在不安全路径"
                    }
                    require(names.add(name)) { "备份中存在重复条目" }
                    val destination = File(root, name)
                    require(destination.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) && destination != root) { "备份路径越界" }
                    checkNotNull(destination.parentFile).mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n
                            total += n
                            require(total <= MAX_BYTES) { "解压数据超过安全上限" }
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                        }
                    }
                    if (name == "manifest.json") {
                        require(size <= 1_000_000L) { "备份目录清单过大" }
                        manifest = json.decodeFromString<EtaPortableManifest>(destination.readText())
                    } else {
                        present[name] = EtaPortableEntry(name, partFromPath(name), size, digest.digest().hex())
                    }
                    zip.closeEntry()
                }
            }
            val checked = requireNotNull(manifest) { "备份缺少校验清单" }
            require(checked.format == "eta-portable-backup" && checked.version == 1) { "备份版本不受支持" }
            require(checked.entries.map { it.name }.distinct().size == checked.entries.size) { "备份目录清单重复" }
            require(checked.entries.associateBy { it.name } == present) { "备份完整性校验失败" }
            require(present.values.all { it.part in checked.parts }) { "备份分类与内容不匹配" }
            val required = mapOf(
                EtaPortablePart.DATA to "data/legacy.json",
                EtaPortablePart.SETTINGS to "settings/config.json",
                EtaPortablePart.SKILLS to "skills/states.json",
                EtaPortablePart.IMAGEGEN to "imagegen/config.json",
                EtaPortablePart.MCP to "mcp/servers.json",
            )
            checked.parts.forEach { part ->
                required[part]?.let { requiredPath ->
                    require(requiredPath in present) { "备份缺少必要分类文件：$requiredPath" }
                }
            }
            return Staged(checked, root)
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    /** Existing imported Skills are NEVER overwritten. Other categories are restored only when selected. */
    suspend fun restore(
        context: Context,
        staged: Staged,
        selected: Set<EtaPortablePart>,
        onDataCommitted: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() && staged.manifest.parts.containsAll(selected)) { "备份不包含所选分类" }
        val app = context.applicationContext
        if (EtaPortablePart.DATA in selected) {
            staged.file("data/legacy.json").inputStream().use { EtaBackupRepository.inspect(app, it) }
        }
        // Parse all selected categories and check conflicts before mutating any live state.
        val preferences = if (EtaPortablePart.SETTINGS in selected)
            json.decodeFromString<EtaPortablePreferences>(staged.file("settings/config.json").readText()) else null
        val imageProfiles = if (EtaPortablePart.IMAGEGEN in selected)
            readImageProfiles(staged.file("imagegen/config.json").readText()) else null
        val servers = if (EtaPortablePart.MCP in selected)
            json.decodeFromString<List<EtaPortableMcpServer>>(staged.file("mcp/servers.json").readText()) else emptyList()
        val currentServers = if (servers.isNotEmpty()) McpServerRepository.servers() else emptyList()
        require(servers.map { it.setting.id }.distinct().size == servers.size &&
            servers.map { it.setting.url.trim() }.distinct().size == servers.size) { "MCP 备份含重复服务" }
        require(servers.none { candidate -> currentServers.any {
            it.id == candidate.setting.id || it.url.trim() == candidate.setting.url.trim()
        } }) { "已有同名或同地址的 MCP 服务，未覆盖原配置" }
        val profiles = if (EtaPortablePart.SUBAGENTS in selected) {
            staged.manifest.entries.filter { it.part == EtaPortablePart.SUBAGENTS }.map { item ->
                val name = item.name.removePrefix("subagents/").removeSuffix(".md")
                require(SubagentProfileCatalog.isValidName(name)) { "非法子代理文件名" }
                require(SubagentProfileCatalog.listCustom().none { it.name == name }) { "已有同名自定义子代理：$name" }
                val parsed = requireNotNull(SubagentProfileCatalog.parseProfileFile(staged.file(item.name))) { "子代理文件无效：$name" }
                require(parsed.name == name) { "子代理文件名与内容不一致" }
                parsed
            }
        } else emptyList()
        val index = if (EtaPortablePart.SKILLS in selected) SkillRuntime.createIndexService(app) else null
        val skillStates = if (index != null)
            json.decodeFromString<List<EtaPortableSkillState>>(staged.file("skills/states.json").readText()) else emptyList()
        val previousSkills = index?.listSkillsForManagement().orEmpty()
        val packageFile = staged.file("skills/user-skills.zip")
        val skillCandidates = if (index != null && packageFile.exists()) {
            when (val inspection = SkillRuntime.createPackageInstaller(app).inspectRepositoryZip({ packageFile.inputStream() })) {
                is SkillArchiveInspectionResult.Success -> inspection.candidates
                is SkillArchiveInspectionResult.Failure -> error(inspection.error.message)
            }.also { candidates ->
                require(candidates.none { candidate -> previousSkills.any { it.id == candidate.id && it.installed } }) {
                    "存在已安装的同名 Skill，未覆盖原内容"
                }
            }
        } else emptyList()
        val previousPreferences = if (preferences != null) currentPreferences() else null
        val previousImages = if (imageProfiles != null) ImageGenConfigStore.profiles(app) else null
        val addedProfiles = mutableListOf<String>()
        val addedServers = mutableListOf<String>()
        val addedSkills = mutableListOf<String>()
        var dataCommitted = false
        try {
            if (preferences != null) applyPreferences(preferences)
            if (imageProfiles != null) {
                // Fresh IDs avoid coupling imported connections to existing device-bound key slots.
                val old = requireNotNull(previousImages)
                ImageGenConfigStore.writeProfiles(app, ImageGenProfiles(old.profiles + imageProfiles.profiles,
                    imageProfiles.selectedId ?: old.selectedId))
            }
            for (profile in profiles) {
                addedProfiles += profile.name
                SubagentProfileCatalog.saveCustomProfile(profile.name, profile.summary, profile.roleInstruction,
                    profile.whenToUse, profile.explicitTools.orEmpty().toList(), profile.disallowedTools.toList(), profile.override)
            }
            for (server in servers) {
                addedServers += server.setting.id
                McpServerRepository.add(server.setting, server.bearer)
            }
            if (skillCandidates.isNotEmpty()) {
                when (val result = SkillRuntime.createPackageInstaller(app).installRepositoryZip(
                    { packageFile.inputStream() }, skillCandidates.map { it.relativePath })) {
                    is SkillInstallResult.Success -> addedSkills += result.installed.map { it.id }
                    is SkillInstallResult.Conflict -> error("存在已安装的同名 Skill，未覆盖原内容")
                    is SkillInstallResult.Failure -> error(result.error.message +
                        if (result.recoveryRequired) "；Skill 恢复记录已保留，请先在 Skills 页面完成恢复。" else "")
                }
            }
            skillStates.forEach { state ->
                val current = index?.listSkillsForManagement()?.firstOrNull { it.id == state.id }
                if (current?.installed == true && current.enabled != state.enabled) index?.setSkillEnabled(state.id, state.enabled)
            }
            currentCoroutineContext().ensureActive()
            // Existing data importer owns its transaction/memory rollback. Run it last,
            // preserving local Codex pools, and distinguish post-commit cleanup failures.
            if (EtaPortablePart.DATA in selected) {
                staged.file("data/legacy.json").inputStream().use {
                    EtaBackupRepository.import(app, it, onCommitted = {
                        dataCommitted = true
                        onDataCommitted()
                    }, preserveCodexAccounts = true)
                }
            }
        } catch (failure: Throwable) {
            if (dataCommitted) throw IllegalStateException("数据已导入，但后续清理未完成，请按错误提示核对当前状态。", failure)
            withContext(NonCancellable) {
                suspend fun rollback(action: suspend () -> Unit) {
                    try { action() } catch (error: Throwable) { failure.addSuppressed(error) }
                }
                for (id in addedSkills.asReversed()) rollback { check(index?.deleteSkill(id) == true) { "Skill 回滚失败" } }
                previousSkills.filter { it.installed }.forEach { entry -> rollback { index?.setSkillEnabled(entry.id, entry.enabled) } }
                for (id in addedServers.asReversed()) rollback {
                    if (McpServerRepository.serverById(id) != null) McpServerRepository.delete(id)
                }
                for (name in addedProfiles.asReversed()) rollback { SubagentProfileCatalog.deleteCustomProfile(name) }
                previousImages?.let { rollback { ImageGenConfigStore.writeProfiles(app, it) } }
                previousPreferences?.let { rollback { applyPreferences(it) } }
            }
            if (failure.suppressed.isNotEmpty()) throw IllegalStateException("恢复失败且部分回滚未完成，请保留备份并核对当前状态。", failure)
            throw failure
        }
    }

    private suspend fun currentPreferences() = EtaPortablePreferences(
        SettingsDataStore.settings(), SettingsDataStore.speechSettings(), SettingsDataStore.webToolsSettings(),
        SettingsDataStore.subagentSettings(), SettingsDataStore.compactionSettings(),
        SettingsDataStore.conversationTitleSettings(), SettingsDataStore.assistantOverlaySettings(),
    )

    private suspend fun applyPreferences(config: EtaPortablePreferences) {
        SettingsDataStore.updateSettings { old -> config.app.copy(
            selectedProviderId = old.selectedProviderId, selectedModelId = old.selectedModelId,
        ) }
        SettingsDataStore.setSpeechSettings(config.speech)
        SettingsDataStore.setWebToolsSettings(config.webTools)
        SettingsDataStore.setSubagentSettings(config.subagents)
        SettingsDataStore.setCompactionSettings(config.compaction)
        SettingsDataStore.setConversationTitleSettings(config.conversationTitle ?:
            SettingsDataStore.conversationTitleSettings().copy(enabled = config.app.autoConversationTitle))
        config.assistantOverlay?.let { SettingsDataStore.setAssistantOverlaySettings(it) }
    }

    private fun portableImageConfig(config: ImageGenConfig): ImageGenConfig = config.copy(
        accountId = "", providerId = "", keyConfigured = false, credentialId = "", retiredCredentialIds = emptyList(),
    )

    private fun readImageProfiles(raw: String): ImageGenProfiles {
        val payload = json.parseToJsonElement(raw).jsonObject
        val original = if ("profiles" in payload) json.decodeFromString<ImageGenProfiles>(raw)
            else json.decodeFromString<ImageGenConfig>(raw).let { config ->
                if (config.source == ImageGenSource.NONE) ImageGenProfiles() else
                    ImageGenProfiles(listOf(config.copy(profileId = config.profileId.ifBlank { "legacy" })), config.profileId.ifBlank { "legacy" })
            }
        require(original.profiles.map { it.profileId }.distinct().size == original.profiles.size) { "生图备份含重复连接" }
        val ids = original.profiles.associate { it.profileId to UUID.randomUUID().toString() }
        return ImageGenProfiles(original.profiles.map { portableImageConfig(it).copy(profileId = ids.getValue(it.profileId)) },
            ids[original.selectedId])
    }

    private fun partFromPath(name: String): EtaPortablePart = when {
        name.startsWith("data/") -> EtaPortablePart.DATA
        name.startsWith("settings/") -> EtaPortablePart.SETTINGS
        name.startsWith("skills/") -> EtaPortablePart.SKILLS
        name.startsWith("subagents/") -> EtaPortablePart.SUBAGENTS
        name.startsWith("imagegen/") -> EtaPortablePart.IMAGEGEN
        name.startsWith("mcp/") -> EtaPortablePart.MCP
        else -> error("未知备份分类：$name")
    }

    private fun encryptedOutput(output: OutputStream, password: String?): OutputStream {
        if (password.isNullOrBlank()) {
            output.write(magicPlain)
            return output
        }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        output.write(magicLocked)
        output.write(salt)
        output.write(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        return CipherOutputStream(output, cipher)
    }

    private fun encryptedInput(input: InputStream, password: String?): InputStream {
        val buffered = BufferedInputStream(input)
        val marker = ByteArray(7)
        require(buffered.readNBytes(marker, 0, marker.size) == marker.size) { "备份文件不完整" }
        return when {
            marker.contentEquals(magicPlain) -> buffered
            marker.contentEquals(magicLocked) -> {
                require(!password.isNullOrBlank()) { "这是加密备份，请输入备份密码" }
                val salt = ByteArray(16)
                val nonce = ByteArray(12)
                require(buffered.readNBytes(salt, 0, salt.size) == salt.size && buffered.readNBytes(nonce, 0, nonce.size) == nonce.size) { "备份加密头不完整" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
                CipherInputStream(buffered, cipher)
            }
            else -> error("不是受支持的 Eta 分类备份文件")
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 180_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
