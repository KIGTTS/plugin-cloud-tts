// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import java.util.Locale

/** Configuration and voice selection use the host's grouped settings renderer. */
internal class SpeechServicePages(private val settings: ServiceSettings, private val providers: () -> List<TtsProvider>) {
    fun page(id: String, active: Boolean): PluginPage? {
        if (id == "manager") {
            val catalog = providers()
            val source = catalog.firstOrNull { it.pluginId == settings.provider && it.enabled && it.info.availability == EngineAvailability.READY }
            val voice = source?.info?.voices?.firstOrNull { it.id == (settings.voice ?: source.info.selectedVoiceId) }
                ?: source?.info?.voices?.firstOrNull()
                ?: catalog.firstOrNull { it.pluginId == SYSTEM_PROVIDER }?.info?.voices
                    ?.firstOrNull { source == null && it.id == settings.systemVoice }
            val voiceSource = source ?: catalog.firstOrNull { it.pluginId == SYSTEM_PROVIDER }
            val actions = if (active) emptyList() else listOf(PageBlock.Actions(listOf(PageAction("activate", "用于朗读", PluginIcon.SPEAKER))))
            return PluginPage(id, "语音合成服务", actions, ManagementList("speech-settings", listOf(
                ManagementItem("voice", "声音", voice?.let { voiceName(voiceSource, it) } ?: "系统默认声音", PluginIcon.SPEAKER,
                    group = "朗读", primaryAction = "sounds"),
                ManagementItem("rate", "语速", "${settings.rate}×", PluginIcon.SETTINGS,
                    group = "朗读", primaryAction = "rate"),
                ManagementItem("packs", "语音包", "导入与整理声音", PluginIcon.SPEAKER,
                    group = "声音资源", primaryAction = "library"),
                ManagementItem("language", "系统声音语言", languageName(settings.language), PluginIcon.TEXT,
                    group = "声音资源", primaryAction = "language"),
                ManagementItem("resident", "保持加载", "减少下次朗读等待", PluginIcon.SPEAKER,
                    group = "加载方式", toggle = ManagementToggle(settings.resident, "resident")))))
        }
        if (id != "voices" && !id.startsWith("voices/")) return null
        val sources = providers().filter { it.enabled && it.info.availability == EngineAvailability.READY }
            .sortedBy { if (it.pluginId == settings.provider) 0 else 1 }
        val default = ManagementItem("system-default", "系统默认声音", icon = PluginIcon.SPEAKER,
            selected = settings.provider == null && settings.systemVoice == null, group = "系统声音", primaryAction = "system-default")
        val items = sources.flatMap { source ->
            (if (source.pluginId == SYSTEM_PROVIDER) listOf(default) else emptyList()) + source.info.voices.distinctBy { it.id }.map { voice ->
                ManagementItem("${source.pluginId}/${voice.id}", voiceName(source, voice),
                    if (source.pluginId == SYSTEM_PROVIDER) voice.description else languageName(voice.language), PluginIcon.SPEAKER,
                    selected = if (source.pluginId == SYSTEM_PROVIDER) settings.provider == null && settings.systemVoice == voice.id
                        else settings.provider == source.pluginId && settings.voice == voice.id,
                    group = if (source.pluginId == SYSTEM_PROVIDER) "系统声音" else source.name,
                    primaryAction = "voice/${encodeId(source.pluginId)}/${encodeId(voice.id)}")
            }
        }.ifEmpty { listOf(default) }
        val page = (id.substringAfter('/', "0").toIntOrNull() ?: 0).coerceIn(0, (items.size - 1) / 24)
        val navigation = buildList {
            if (page > 0) add(PageAction("sounds/${page - 1}", "上一页"))
            if ((page + 1) * 24 < items.size) add(PageAction("sounds/${page + 1}", "下一页"))
        }
        return PluginPage(id, "选择声音", if (navigation.isEmpty()) emptyList() else listOf(PageBlock.Actions(navigation)),
            ManagementList("voice-picker/$page", items.drop(page * 24).take(24)))
    }
}

internal fun languageName(tag: String): String = Locale.forLanguageTag(tag).let { locale ->
    if (locale.language.isBlank()) "" else locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)
}

private fun voiceName(source: TtsProvider?, voice: VoiceInfo): String {
    if (source?.pluginId != SYSTEM_PROVIDER) return voice.name
    val variants = source.info.voices.distinctBy { it.id }.filter { it.language == voice.language }
    val name = languageName(voice.language).ifBlank { "系统声音" }
    return name + if (variants.size > 1) " ${variants.indexOfFirst { it.id == voice.id } + 1}" else ""
}
