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
            val pack = source?.info?.packs?.firstOrNull { voice?.id in it.voiceIds }
            val actions = if (active) emptyList() else listOf(PageBlock.Actions(listOf(PageAction("activate", "用于朗读", PluginIcon.SPEAKER))))
            return PluginPage(id, "语音合成服务", actions + buildList<PageBlock> {
                add(PageBlock.Group("朗读", listOf(
                    PageBlock.Input("声音", voice?.name ?: "系统默认声音", "library", icon = PluginIcon.SPEAKER),
                    PageBlock.Slider(PluginControl.Slider("语速", "save-rate", settings.rate, .25f, 4f, suffix = "×")))))
                if (pack != null && pack.parameters.isNotEmpty()) {
                    val controls = packSettingsControls(PackEntry(requireNotNull(source), pack)).controls.filterIsInstance<PluginControl.Slider>()
                    add(PageBlock.Group(pack.metadata.name, controls.map { PageBlock.Slider(it) }))
                }
                add(PageBlock.Group("加载方式", listOf(PageBlock.Toggle("保持加载", settings.resident, "resident", "减少下次朗读等待"))))
            })
        }
        if (id != "voices" && !id.startsWith("voices/")) return null
        val sources = providers().filter { it.enabled && it.info.availability == EngineAvailability.READY }
            .sortedBy { if (it.pluginId == settings.provider) 0 else 1 }
        val default = ManagementItem("system-default", "系统默认声音", "系统语音合成", icon = PluginIcon.SPEAKER,
            selected = settings.provider == null, group = "系统声音", primaryAction = "system-default")
        val items = sources.flatMap { source ->
            if (source.pluginId == SYSTEM_PROVIDER) listOf(default) else source.info.voices.distinctBy { it.id }.map { voice ->
                ManagementItem("${source.pluginId}/${voice.id}", voice.name, languageName(voice.language), PluginIcon.SPEAKER,
                    selected = settings.provider == source.pluginId && settings.voice == voice.id,
                    group = source.name,
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
