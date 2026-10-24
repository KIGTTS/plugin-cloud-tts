package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import java.util.Base64

class CloudTtsPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("engine.cloud-tts", "云端语音合成", "配置云端合成接口并按需添加预设或克隆声音", version = "0.1.0", license = "GPL-3.0-only")
    private lateinit var settings: CloudTtsSettings
    private lateinit var engine: CloudTtsEngine
    override val tts: TtsEngine get() = engine
    override val voiceManager: VoiceManager get() = manager
    private val manager = object : VoiceManager {
        override suspend fun command(command: VoiceCommand): PluginOutcome = when (command.operation) {
            VoiceOperation.SELECT -> { command.voiceId?.let { settings.selectVoice(it) }; PluginOutcome.RefreshUi }
            VoiceOperation.DELETE -> { command.voiceId?.let { settings.removeVoice(it) }; PluginOutcome.RefreshUi }
            VoiceOperation.RENAME -> {
                val voiceId = command.voiceId
                val value = command.value
                if (voiceId != null && value != null) settings.renameVoice(voiceId, value)
                PluginOutcome.RefreshUi
            }
            VoiceOperation.REFRESH -> PluginOutcome.RefreshUi
            else -> PluginOutcome.Message("该云端声音由配置页面管理")
        }
    }
    override suspend fun attach(environment: PluginEnvironment) { settings = CloudTtsSettings(environment.storage); engine = CloudTtsEngine(settings) }
    override val contributions = PluginContributions(settingsEntries = listOf(
        SettingsEntry("cloud-tts", "云端语音合成", "settings", PluginIcon.SPEAKER, "audio", "音频", "按需添加预设或克隆声音", 40)))
    override fun page(pageId: String, context: PluginContext): PluginPage? {
        if (pageId != "settings" && !pageId.startsWith("provider/")) return null
        if (pageId.startsWith("provider/")) return providerPage(decode(pageId.substringAfter('/')))
        val blocks = mutableListOf<PageBlock>(PageBlock.Text("云端语音合成", true), PageBlock.Notice("只有你主动加入的声音会出现在语音包列表，预设声音与克隆声音分开标记。"), PageBlock.Actions(listOf(PageAction("add-provider", "添加合成服务", PluginIcon.ADD), PageAction("add-pack", "创建声音包", PluginIcon.ADD))))
        settings.providers().forEach { c -> blocks += PageBlock.Group("${c.name} · ${c.provider.label}", listOf(PageBlock.Text(if (c.configured) "已配置" else "待配置"), PageBlock.Toggle("启用此接口", c.enabled, "toggle/${encode(c.id)}", icon = PluginIcon.SPEAKER), PageBlock.Actions(listOf(PageAction("provider/${encode(c.id)}", "配置接口", PluginIcon.SETTINGS), PageAction("select-provider/${encode(c.id)}", "选用", PluginIcon.STAR), PageAction("remove-provider/${encode(c.id)}", "移除", PluginIcon.CLOSE))))) }
        settings.packList().forEach { pack ->
            val provider = settings.providers().firstOrNull { it.id == pack.providerId }
            val children = mutableListOf<PageBlock>(PageBlock.Text(if (pack.remark.isBlank()) "手动添加的声音包" else pack.remark), PageBlock.Actions(listOf(PageAction("add-voice/${encode(pack.id)}", "添加声音", PluginIcon.ADD))))
            settings.voiceList().filter { it.packId == pack.id }.forEach { voice ->
                val description = voice.language + if (voice.description.isBlank()) "" else " · ${voice.description}"
                val actions = listOf(PageAction("select-voice/${encode(voice.id)}", if (settings.currentVoice()?.id == voice.id) "当前声音" else "使用声音", PluginIcon.SPEAKER), PageAction("remove-voice/${encode(voice.id)}", "移除", PluginIcon.CLOSE))
                children += PageBlock.Group("${voice.name} · ${if (voice.kind == CloudVoiceKind.CLONE) "克隆" else "预设"}", listOf(PageBlock.Text(description), PageBlock.Actions(actions)))
            }
            blocks += PageBlock.Group("${pack.name} · ${provider?.provider?.label ?: "服务商"}", children)
        }
        return PluginPage(pageId, "云端语音合成", blocks)
    }
    private fun providerPage(id: String): PluginPage {
        val c = settings.providers().firstOrNull { it.id == id } ?: return PluginPage("provider/$id", "合成服务", listOf(PageBlock.Notice("服务不存在")))
        fun input(label: String, field: String, value: String, secret: Boolean = false) = PageBlock.Input(label, value, "save/${encode(c.id)}/$field", secret = secret, icon = PluginIcon.SETTINGS)
        return PluginPage("provider/$id", c.name, listOf(PageBlock.Text(c.provider.label, true), input("名称", "name", c.name), input("接口地址", "endpoint", c.endpoint), input("API Key", "apiKey", c.apiKey, true), input("API Secret", "apiSecret", c.apiSecret, true), input("应用 ID", "appId", c.appId), input("模型或接口版本", "model", c.model), PageBlock.Notice("接口地址必须使用 HTTPS；密钥保存在本机插件数据中。")))
    }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        val p = actionId.split('/')
        return when (p[0]) {
            "add-provider" -> if (p.size == 1) PluginOutcome.Menu("选择服务商", CloudTtsProvider.entries.map { PageAction("add-provider/${it.key}", it.label, PluginIcon.SPEAKER) })
                else { settings.addProvider(CloudTtsProvider.of(p[1])); PluginOutcome.RefreshUi }
            "toggle" -> { settings.toggleProvider(decode(p[1])); PluginOutcome.RefreshUi }
            "select-provider" -> { settings.selectProvider(decode(p[1])); PluginOutcome.RefreshUi }
            "remove-provider" -> { settings.removeProvider(decode(p[1])); PluginOutcome.RefreshUi }
            "save" -> { settings.updateProvider(decode(p[1]), p.getOrNull(2) ?: error("缺少设置项"), context.inputs["value"].orEmpty()); PluginOutcome.RefreshUi }
            "add-pack" -> {
                val provider = settings.currentProvider() ?: return PluginOutcome.Message("请先添加并配置一个合成服务")
                PluginOutcome.TextInput("声音包名称", "new-pack/${encode(provider.id)}")
            }
            "new-pack" -> { val provider = settings.providers().first { it.id == decode(p[1]) }; settings.createPack(provider.id, context.inputs["value"].orEmpty().trim().ifBlank { "我的声音包" }); PluginOutcome.RefreshUi }
            "add-voice" -> {
                val pack = settings.packList().firstOrNull { it.id == decode(p[1]) } ?: return PluginOutcome.Message("声音包不存在")
                PluginOutcome.TextInput("声音名称", "new-voice-name/${encode(pack.id)}")
            }
            "new-voice-name" -> PluginOutcome.TextInput("语言代码", "new-voice-language/${p[1]}/${encode(context.inputs["value"].orEmpty())}", "zh-CN")
            "new-voice-language" -> PluginOutcome.TextInput("服务商声音 ID", "new-voice-id/${p[1]}/${p[2]}/${encode(context.inputs["value"].orEmpty())}")
            "new-voice-id" -> PluginOutcome.Menu("声音类型", listOf(PageAction("new-voice-kind/${p[1]}/${p[2]}/${p[3]}/PRESET", "预设声音", PluginIcon.SPEAKER), PageAction("new-voice-kind/${p[1]}/${p[2]}/${p[3]}/CLONE", "克隆声音", PluginIcon.ROBOT2)))
            "new-voice-kind" -> { val pack = settings.packList().first { it.id == decode(p[1]) }; val provider = pack.providerId; val name = decode(p[2]); val language = decode(p[3]); val remoteId = decode(p[4]); settings.addVoice(pack.id, provider, name, language, CloudVoiceKind.valueOf(p[5]), remoteId); PluginOutcome.RefreshUi }
            "select-voice" -> { settings.selectVoice(decode(p[1])); PluginOutcome.RefreshUi }
            "remove-voice" -> { settings.removeVoice(decode(p[1])); PluginOutcome.RefreshUi }
            else -> PluginOutcome.Message("未知语音合成操作")
        }
    }
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value))
}
