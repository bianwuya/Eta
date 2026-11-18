package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AssistantOverlaySettings
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.WebToolsSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Uses the current settings APIs. Old private/compaction fields are intentionally not applied. */
@Serializable
internal data class EtaPortableBackupPreferences(
    val app: Settings,
    val speech: SpeechSettings,
    val webTools: WebToolsSettings,
    val subagents: SubagentSettings,
    val conversationTitle: ConversationTitleSettings? = null,
    val assistantOverlay: AssistantOverlaySettings? = null,
) {
    fun forExport() = copy(
        app = app.copy(selectedProviderId = null, selectedModelId = null),
        speech = speech.withLocalDestinations(SpeechSettings()),
        webTools = WebToolsSettings(searchBackend = webTools.searchBackend, fetchBackend = webTools.fetchBackend),
    )

    suspend fun prepare(plan: PortableRestorePlan) {
        val previous = snapshot()
        plan.step {
            suspend fun applySettings(value: Settings) = SettingsDataStore.updateSettings { current ->
                value.copy(selectedProviderId = current.selectedProviderId, selectedModelId = current.selectedModelId)
            }
            applySettings(app)
            undo { applySettings(previous.app) }
            SettingsDataStore.setSpeechSettings(speech.withLocalDestinations(previous.speech))
            undo { SettingsDataStore.setSpeechSettings(previous.speech) }
            SettingsDataStore.setWebToolsSettings(previous.webTools.copy(
                searchBackend = webTools.searchBackend, fetchBackend = webTools.fetchBackend,
            ))
            undo { SettingsDataStore.setWebToolsSettings(previous.webTools) }
            SettingsDataStore.setSubagentSettings(subagents)
            undo { SettingsDataStore.setSubagentSettings(previous.subagents) }
            conversationTitle?.let {
                SettingsDataStore.setConversationTitleSettings(it)
                undo { SettingsDataStore.setConversationTitleSettings(requireNotNull(previous.conversationTitle)) }
            }
            assistantOverlay?.let {
                SettingsDataStore.setAssistantOverlaySettings(it)
                undo { SettingsDataStore.setAssistantOverlaySettings(requireNotNull(previous.assistantOverlay)) }
            }
        }
    }

    companion object {
        suspend fun snapshot() = EtaPortableBackupPreferences(
            SettingsDataStore.settings(), SettingsDataStore.speechSettings(), SettingsDataStore.webToolsSettings(),
            SettingsDataStore.subagentSettings(), SettingsDataStore.conversationTitleSettings(), SettingsDataStore.assistantOverlaySettings(),
        )

        fun decode(raw: String): EtaPortableBackupPreferences {
            val json = EtaPortableBackupArchive.json
            val prefs = json.decodeFromString<EtaPortableBackupPreferences>(raw)
            if (prefs.conversationTitle != null) return prefs
            val oldTitle = json.parseToJsonElement(raw).jsonObject["app"]?.jsonObject
                ?.get("autoConversationTitle")?.jsonPrimitive?.booleanOrNull
            return if (oldTitle == null) prefs else prefs.copy(conversationTitle = ConversationTitleSettings(enabled = oldTitle))
        }
    }
}

/** An imported setting cannot retarget a speech or OSS key already stored on this device. */
private fun SpeechSettings.withLocalDestinations(local: SpeechSettings) = copy(
    qwenAsr = local.qwenAsr, qwenTts = local.qwenTts,
    doubaoAsr = local.doubaoAsr, doubaoTts = local.doubaoTts,
    mimoAsr = mimoAsr.copy(baseUrl = local.mimoAsr.baseUrl),
    mimoTts = mimoTts.copy(baseUrl = local.mimoTts.baseUrl),
    deepseekAsr = local.deepseekAsr,
    deepseekTts = deepseekTts.copy(baseUrl = local.deepseekTts.baseUrl),
    geminiTts = geminiTts.copy(baseUrl = local.geminiTts.baseUrl),
    minimaxTts = minimaxTts.copy(baseUrl = local.minimaxTts.baseUrl),
    oss = local.oss,
)
