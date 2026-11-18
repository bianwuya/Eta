package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.skill.BUILTIN_SKILL_SOURCE
import io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.USER_SKILL_SOURCE
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal object EtaPortableBackupFiles {
    private val json = EtaPortableBackupArchive.json
    private val skillId = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private fun validId(id: String) = id.length in 1..64 && skillId.matches(id)

    @Serializable
    private data class SkillState(val id: String, val enabled: Boolean, val source: String)

    suspend fun exportSkills(
        context: Context,
        work: File,
        entry: suspend (String, suspend (OutputStream) -> Unit) -> Unit,
    ) {
        val skills = SkillRuntime.createIndexService(context).listSkillsForManagement().filter { it.installed }
        entry(requireNotNull(EtaPortablePart.SKILLS.requiredPath)) {
            it.write(json.encodeToString(skills.map { skill -> SkillState(skill.id, skill.enabled, skill.source) }).toByteArray())
        }
        val userSkills = skills.filter { it.source == USER_SKILL_SOURCE }
        if (userSkills.isEmpty()) return
        val cancel = currentCoroutineContext()
        val file = File(work, "skills.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for (skill in userSkills) {
                cancel.ensureActive()
                require(validId(skill.id))
                val base = File(skill.rootPath)
                require(Files.isDirectory(base.toPath(), NOFOLLOW_LINKS))
                val root = base.canonicalFile.toPath()
                for (source in base.walkTopDown().onEnter { it.name != ".git" && !Files.isSymbolicLink(it.toPath()) }) {
                    cancel.ensureActive()
                    if (!Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS)) continue
                    val path = source.canonicalFile.toPath()
                    require(path.startsWith(root))
                    val relative = root.relativize(path).toString()
                    zip.putNextEntry(ZipEntry("skills/${skill.id}/$relative"))
                    if (relative == "SKILL.md") zip.write(portableSkillMarkdown(skill.id, source))
                    else source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        // Verify the nested archive with the same parser/limits used by installation.
        require(SkillRuntime.createPackageInstaller(context).inspectRepositoryZip(
            file::inputStream, isCancelled = { cancel[kotlinx.coroutines.Job]?.isActive == false },
        ) is SkillArchiveInspectionResult.Success)
        entry(EtaPortableBackupArchive.SKILL_ZIP) { sink -> file.inputStream().use { it.copyTo(sink) } }
    }

    private fun portableSkillMarkdown(id: String, file: File): ByteArray {
        require(file.length() <= 512 * 1024)
        val parsed = requireNotNull(SkillParser.parseSkillFile(file))
        val raw = file.readText()
        val description = parsed.frontmatter["description"].orEmpty()
        val strict = raw.startsWith("---\n") || raw.startsWith("---\r\n")
        if (strict && parsed.frontmatter["name"] == id && description.isNotBlank() && description.length <= 1024) {
            return raw.toByteArray()
        }
        // The runtime accepts legacy markdown that the ZIP installer does not. Normalize the portable copy only.
        val header = Regex("\\A---\\r?\\n([\\s\\S]*?)\\r?\\n---(?=\\r?\\n|$)").find(raw)
        val retained = mutableListOf<String>()
        var replacing = false
        header?.groupValues?.get(1)?.lines()?.forEach { line ->
            if (line.startsWith("name:") || line.startsWith("description:")) replacing = true
            else if (!replacing || (line.isNotBlank() && !line.first().isWhitespace())) {
                replacing = false
                retained += line
            }
        }
        val body = if (header == null) "\n\n$raw" else raw.substring(header.range.last + 1)
        val label = json.encodeToString(description.ifBlank { id }.take(1024))
        return (listOf("---", "name: $id", "description: $label") + retained + "---").joinToString("\n").plus(body).toByteArray()
    }

    suspend fun prepareSkills(context: Context, staged: EtaPortableBackup.Staged, plan: PortableRestorePlan) {
        val states = json.decodeFromString<List<SkillState>>(staged.file(EtaPortablePart.SKILLS).readText())
        require(states.map { it.id }.distinct().size == states.size)
        require(states.all { validId(it.id) && it.source in setOf(USER_SKILL_SOURCE, BUILTIN_SKILL_SOURCE) })
        val index = SkillRuntime.createIndexService(context)
        val archive = staged.file(EtaPortableBackupArchive.SKILL_ZIP)
        val cancel = currentCoroutineContext()
        if (archive.exists()) {
            val installer = SkillRuntime.createPackageInstaller(context)
            val inspection = installer.inspectRepositoryZip(archive::inputStream, isCancelled = { cancel[kotlinx.coroutines.Job]?.isActive == false })
            cancel.ensureActive()
            require(inspection is SkillArchiveInspectionResult.Success)
            val candidates = inspection.candidates
            require(candidates.map { it.id }.distinct().size == candidates.size)
            val installed = index.listSkillsForManagement().filter { it.installed }.mapTo(hashSetOf()) { it.id }
            if (candidates.any { it.id in installed }) throw EtaPortableConflict()
            plan.step {
                when (val result = installer.installRepositoryZip(archive::inputStream, candidates.map { it.relativePath })) {
                    is SkillInstallResult.Success -> result.installed.forEach { skill -> undo { check(index.deleteSkill(skill.id)) } }
                    is SkillInstallResult.Conflict -> throw EtaPortableConflict()
                    is SkillInstallResult.Failure -> {
                        val failure = IllegalStateException("Skill installation failed")
                        if (result.recoveryRequired) throw EtaPortableIncompleteRestore(false, failure)
                        throw failure
                    }
                }
            }
        }
        plan.step {
            val byId = index.listSkillsForManagement().filter { it.installed }.associateBy { it.id }
            for (state in states) {
                val previous = byId[state.id] ?: continue
                if (previous.source != state.source || previous.enabled == state.enabled) continue
                index.setSkillEnabled(state.id, state.enabled)
                undo { index.setSkillEnabled(state.id, previous.enabled) }
            }
        }
    }

    suspend fun exportSubagents(entry: suspend (String, suspend (OutputStream) -> Unit) -> Unit) {
        for (profile in SubagentProfileCatalog.listCustom()) {
            val source = File(requireNotNull(profile.filePath))
            require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS))
            entry("subagents/${profile.name}.md") { sink -> source.inputStream().use { it.copyTo(sink) } }
        }
    }

    fun prepareSubagents(staged: EtaPortableBackup.Staged, plan: PortableRestorePlan) {
        val root = SubagentProfileCatalog.defaultAgentsDir().toPath()
        require(!Files.isSymbolicLink(root))
        val names = SubagentProfileCatalog.listCustom().mapTo(hashSetOf()) { it.name }
        for (item in staged.manifest.entries.filter { it.part == EtaPortablePart.SUBAGENTS }) {
            val source = staged.file(item.name)
            val name = source.nameWithoutExtension
            val profile = requireNotNull(SubagentProfileCatalog.parseProfileFile(source))
            require(profile.name == name && (name !in SubagentProfile.BUILTIN_NAMES || profile.override))
            val target = root.resolve(source.name)
            if (!names.add(name) || Files.exists(target, NOFOLLOW_LINKS)) throw EtaPortableConflict()
            plan.step {
                require(!Files.isSymbolicLink(root))
                Files.createDirectories(root)
                // CREATE_NEW refuses both a late conflict and a dangling link; register compensation only after creation.
                Files.newOutputStream(target, CREATE_NEW, WRITE).use { out ->
                    undo { Files.delete(target) }
                    source.inputStream().use { it.copyTo(out) }
                }
            }
        }
    }
}
