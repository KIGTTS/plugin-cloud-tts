// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.kigtts.plugin.api.*
import com.kigtts.engines.system.SystemSpeechEngine
import java.util.Base64

class SpeechServicePlugin : KigPlugin {
    override val descriptor = PluginDescriptor("service.speech-synthesis", "语音合成服务",
        "选择朗读声音，管理语音包", version = "0.8.2", license = "GPL-3.0-only")
    private lateinit var environment: AndroidPluginEnvironment
    private lateinit var settings: ServiceSettings
    private lateinit var broker: SpeechBroker
    private lateinit var facade: ServiceSynthesis
    private lateinit var system: SystemSpeechEngine
    private lateinit var libraryPages: VoiceLibraryPages
    private lateinit var libraryActions: VoiceLibraryActions
    private lateinit var settingsPages: SpeechServicePages
    override val tts: TtsEngine get() = facade
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment as AndroidPluginEnvironment
        broker = requireNotNull(environment.speechBroker) { "宿主未授予语音合成服务能力" }
        settings = ServiceSettings(environment.storage)
        system = SystemSpeechEngine(this.environment)
        facade = ServiceSynthesis(settings, broker, system)
        val library = VoiceLibrary(environment.storage)
        libraryPages = VoiceLibraryPages(settings, library, ::providers)
        libraryActions = VoiceLibraryActions(environment, settings, library, libraryPages, broker)
        settingsPages = SpeechServicePages(settings, ::providers)
    }
    override val contributions = PluginContributions(settingsEntries = listOf(
        SettingsEntry("manager", "语音合成", "manager", PluginIcon.SPEAKER, "audio", "音频", "选择朗读声音", 10),
        SettingsEntry("packs", "语音包", "packs", PluginIcon.SPEAKER, "audio", "音频", "管理已添加的声音", 20)))
    override fun contributions(context: PluginContext): PluginContributions {
        facade.settingsChanged(context.ttsPluginId?.let { it == descriptor.id && context.automaticRead })
        return contributions
    }
    override fun page(pageId: String, context: PluginContext): PluginPage? {
        libraryPages.page(pageId)?.let { return it }
        settingsPages.page(pageId, context.ttsPluginId == descriptor.id)?.let { return it }
        if (pageId.startsWith("provider/")) {
            val parts = pageId.split('/')
            val id = decode(parts[1]); val page = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val provider = providers().firstOrNull { it.pluginId == id }
                ?: return PluginPage(pageId, "引擎不可用", listOf(PageBlock.Notice("该插件已停用或卸载，系统声音仍可用于朗读。")))
            val controls = mutableListOf(PageAction("select/${encode(id)}", "使用此引擎", PluginIcon.SPEAKER), PageAction("refresh/${encode(id)}", "刷新声音列表", PluginIcon.INFO))
            if (provider.info.importsVoicePacks) controls += PageAction("import/${encode(id)}", "导入语音包", PluginIcon.ADD)
            val voices = provider.info.voices.drop(page * 20).take(20)
            val navigation = mutableListOf<PageAction>()
            if (page > 0) navigation += PageAction("engine/${encode(id)}/${page - 1}", "上一页")
            if ((page + 1) * 20 < provider.info.voices.size) navigation += PageAction("engine/${encode(id)}/${page + 1}", "下一页")
            return PluginPage(pageId, provider.name, listOf(PageBlock.Text(provider.name, true), PageBlock.Actions(controls)) +
                voices.map { voice ->
                    val current = if (id == SYSTEM_PROVIDER) settings.provider == null && settings.systemVoice == voice.id
                        else settings.provider == id && settings.voice == voice.id
                    val actions = mutableListOf(PageAction("voice/${encode(id)}/${encode(voice.id)}", if (current) "当前声音" else "使用声音", PluginIcon.SPEAKER))
                    if (voice.deletable) {
                        actions += PageAction("rename/${encode(id)}/${encode(voice.id)}", "重命名", PluginIcon.TEXT)
                        actions += PageAction("delete/${encode(id)}/${encode(voice.id)}", "删除语音包", PluginIcon.CLOSE)
                    }
                    PageBlock.Group(voice.name, listOf(PageBlock.Text(voice.language + if (voice.description.isBlank()) "" else " · ${voice.description}"), PageBlock.Actions(actions)))
                } + if (navigation.isEmpty()) emptyList() else listOf(PageBlock.Actions(navigation)))
        }
        return null
    }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        if (actionId == "library" || actionId.startsWith("pack")) return libraryActions.execute(actionId, context)
        val pieces = actionId.split('/')
        val id = pieces.getOrNull(1)?.takeIf { pieces[0] != "sounds" }?.let(::decode)
        val voice = pieces.getOrNull(2)?.takeIf { pieces[0] !in setOf("engine") }?.let(::decode)
        return when (pieces[0]) {
            "resident" -> { settings.resident = context.inputs["checked"]?.toBooleanStrictOrNull() ?: !settings.resident; settings.save(); facade.settingsChanged(); PluginOutcome.RefreshUi }
            "activate" -> PluginOutcome.SelectTtsService(descriptor.id)
            "sounds" -> PluginOutcome.OpenPage("packs")
            "system-default" -> { settings.provider = null; settings.voice = null; settings.systemVoice = null; settings.save(); PluginOutcome.RefreshUi }
            "system" -> { settings.provider = null; settings.voice = null; settings.save(); PluginOutcome.RefreshUi }
            "engine" -> PluginOutcome.OpenPage("provider/${pieces[1]}/${pieces.getOrNull(2) ?: "0"}")
            "select" -> {
                val provider = providers().first { it.pluginId == id }
                if (id == SYSTEM_PROVIDER) { settings.provider = null; settings.voice = null; settings.save(); return PluginOutcome.RefreshUi }
                settings.provider = id; settings.voice = provider.info.selectedVoiceId ?: provider.info.voices.firstOrNull()?.id
                settings.save(); PluginOutcome.RefreshUi
            }
            "voice" -> {
                if (id == SYSTEM_PROVIDER) {
                    settings.systemVoice = null; settings.provider = null; settings.voice = null; settings.save()
                    return PluginOutcome.RefreshUi
                }
                if (providers().first { it.pluginId == id }.info.managesVoices)
                    broker.command(requireNotNull(id), VoiceCommand(VoiceOperation.SELECT, voiceId = voice))
                settings.provider = id; settings.voice = voice; settings.save(); PluginOutcome.RefreshUi
            }
            "refresh" -> { if (id == SYSTEM_PROVIDER) system.refreshVoices() else broker.command(requireNotNull(id), VoiceCommand(VoiceOperation.REFRESH)); PluginOutcome.RefreshUi }
            "import" -> PluginOutcome.ImportResource("导入语音包", "resources/inbox", "imported/${pieces[1]}")
            "imported" -> {
                val path = requireNotNull(context.inputs["resourcePath"])
                try { broker.command(requireNotNull(id), VoiceCommand(VoiceOperation.IMPORT, resourcePath = path)) }
                finally { environment.storage.file(path).delete() }
                PluginOutcome.RefreshUi
            }
            "rename" -> PluginOutcome.TextInput("语音包名称", "renamed/${pieces[1]}/${pieces[2]}",
                providers().first { it.pluginId == id }.info.voices.first { it.id == voice }.let { it.packName ?: it.name })
            "renamed" -> { broker.command(requireNotNull(id), VoiceCommand(VoiceOperation.RENAME, voiceId = voice, value = context.inputs["value"])); PluginOutcome.RefreshUi }
            "delete" -> PluginOutcome.Menu("确认删除这个语音包及其所有声音？", listOf(PageAction("deleted/${pieces[1]}/${pieces[2]}", "确认删除", PluginIcon.CLOSE)))
            "deleted" -> {
                broker.command(requireNotNull(id), VoiceCommand(VoiceOperation.DELETE, voiceId = voice))
                if (settings.provider == id) { settings.voice = providers().firstOrNull { it.pluginId == id }?.info?.selectedVoiceId; settings.save() }
                PluginOutcome.RefreshUi
            }
            "rate" -> PluginOutcome.Controls("语速", listOf(PluginControl.Slider("语速", "save-rate", settings.rate, .25f, 4f, suffix = "×")))
            "save-rate" -> {
                val value = context.inputs["value"]?.toFloatOrNull(); require(value != null && value.isFinite() && value in .25f..4f)
                settings.rate = value; settings.save(); PluginOutcome.NoAction
            }
            else -> PluginOutcome.Message("未知语音合成服务操作")
        }
    }
    private fun providers() = listOf(TtsProvider(SYSTEM_PROVIDER, "系统声音", system.info)) + broker.providers().filter { it.pluginId != LEGACY_SYSTEM_PROVIDER && it.pluginId != SYSTEM_PROVIDER }
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value))
}
