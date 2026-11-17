// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import java.util.Locale

/** Configuration and voice selection use the host's grouped settings renderer. */
internal class SpeechServicePages(private val settings: ServiceSettings, private val providers: () -> List<TtsProvider>) {
    fun page(id: String, active: Boolean, copy: (String, String) -> String = { _, fallback -> fallback }): PluginPage? {
        if (id == "manager") {
            val catalog = providers()
            val source = catalog.firstOrNull { it.pluginId == settings.provider && it.enabled && it.info.availability == EngineAvailability.READY }
            val voice = source?.info?.voices?.firstOrNull { it.id == (settings.voice ?: source.info.selectedVoiceId) }
                ?: source?.info?.voices?.firstOrNull()
            val pack = source?.info?.packs?.firstOrNull { voice?.id in it.voiceIds }
            val actions = if (active) emptyList() else listOf(PageBlock.Actions(listOf(PageAction("activate", copy("action.activate", "用于朗读"), PluginIcon.SPEAKER))))
            return PluginPage(id, copy("page.title", "语音合成服务"), actions + buildList<PageBlock> {
                add(PageBlock.Group(copy("group.reading", "朗读"), listOf(
                    PageBlock.Input(copy("field.voice", "声音"), voice?.name ?: copy("voice.system_default", "系统默认声音"), "library", icon = PluginIcon.SPEAKER),
                    PageBlock.Slider(PluginControl.Slider(copy("field.rate", "语速"), "save-rate", settings.rate, .25f, 4f, suffix = "×")))))
                if (pack != null && pack.parameters.isNotEmpty()) {
                    val controls = packSettingsControls(PackEntry(requireNotNull(source), pack)).controls.filterIsInstance<PluginControl.Slider>()
                    add(PageBlock.Group(pack.metadata.name, controls.map { PageBlock.Slider(it) }))
                }
                add(PageBlock.Group(copy("group.loading", "加载方式"), listOf(PageBlock.Toggle(copy("resident.title", "保持加载"), settings.resident, "resident", copy("resident.summary", "减少下次朗读等待")))))
            })
        }
        if (id != "voices" && !id.startsWith("voices/")) return null
        val sources = providers().filter { it.enabled && it.info.availability == EngineAvailability.READY }
            .sortedBy { if (it.pluginId == settings.provider) 0 else 1 }
        val default = ManagementItem("system-default", copy("voice.system_default", "系统默认声音"), copy("voice.system_summary", "系统语音合成"), icon = PluginIcon.SPEAKER,
            selected = settings.provider == null, group = copy("group.system", "系统声音"), primaryAction = "system-default", selection = ManagementSelection("system-default"))
        val items = sources.flatMap { source ->
            if (source.pluginId == SYSTEM_PROVIDER) listOf(default) else source.info.voices.distinctBy { it.id }.map { voice ->
                ManagementItem("${source.pluginId}/${voice.id}", voice.name, languageName(voice.language), PluginIcon.SPEAKER,
                    selected = settings.provider == source.pluginId && settings.voice == voice.id,
                    group = engineName(source.pluginId, source.name, copy),
                    primaryAction = "voice/${encodeId(source.pluginId)}/${encodeId(voice.id)}",
                    selection = ManagementSelection("voice/${encodeId(source.pluginId)}/${encodeId(voice.id)}"))
            }
        }.ifEmpty { listOf(default) }
        val page = (id.substringAfter('/', "0").toIntOrNull() ?: 0).coerceIn(0, (items.size - 1) / 24)
        val navigation = buildList {
            if (page > 0) add(PageAction("sounds/${page - 1}", copy("page.previous", "上一页")))
            if ((page + 1) * 24 < items.size) add(PageAction("sounds/${page + 1}", copy("page.next", "下一页")))
        }
        return PluginPage(id, copy("page.choose_voice", "选择声音"), if (navigation.isEmpty()) emptyList() else listOf(PageBlock.Actions(navigation)),
            ManagementList("voice-picker/$page", items.drop(page * 24).take(24)))
    }
}

private fun engineName(id: String, fallback: String, copy: (String, String) -> String): String = when (id) {
    "engine.piper" -> copy("engine.piper", "Piper 语音引擎")
    "engine.zipvoice-clone" -> copy("engine.zipvoice", "语音克隆引擎")
    "engine.qwen3-clone" -> copy("engine.qwen", "Qwen3-TTS 离线克隆")
    "engine.cloud-tts" -> copy("engine.cloud", "云端语音合成")
    else -> fallback
}

internal fun languageName(tag: String): String = Locale.forLanguageTag(tag).let { locale ->
    if (locale.language.isBlank()) "" else locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)
}
