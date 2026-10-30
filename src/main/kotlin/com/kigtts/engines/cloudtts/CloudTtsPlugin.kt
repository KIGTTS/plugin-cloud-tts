package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import java.util.UUID

class CloudTtsPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("engine.cloud-tts", "云端语音合成", "使用云端声音朗读文字", version = "0.1.2", license = "GPL-3.0-only")
    private lateinit var settings: CloudTtsSettings
    private lateinit var engine: CloudTtsEngine
    private val drafts = linkedMapOf<String, CloudVoiceDraft>()
    override val tts: TtsEngine get() = engine
    override val voiceManager: VoiceManager get() = manager
    private val manager = object : VoiceManager {
        override suspend fun command(command: VoiceCommand): PluginOutcome = outcome {
            when (command.operation) {
                VoiceOperation.SELECT -> { settings.selectVoice(requireNotNull(command.voiceId) { "请选择声音" }); PluginOutcome.RefreshUi }
                VoiceOperation.DELETE -> {
                    if (command.packId != null) settings.removePack(command.packId!!) else settings.removeVoice(requireNotNull(command.voiceId) { "请选择声音" })
                    PluginOutcome.RefreshUi
                }
                VoiceOperation.RENAME -> {
                    val name = requireNotNull(command.value) { "请输入名称" }
                    if (command.packId != null) {
                        val pack = pack(command.packId!!)
                        settings.updatePack(pack.id, VoicePackMetadata(name, pack.remark, pack.pinned, pack.order))
                    } else settings.renameVoice(requireNotNull(command.voiceId), name)
                    PluginOutcome.RefreshUi
                }
                VoiceOperation.UPDATE_METADATA -> { settings.updatePack(requireNotNull(command.packId), requireNotNull(command.metadata)); PluginOutcome.RefreshUi }
                VoiceOperation.SET_AVATAR -> { settings.setAvatar(requireNotNull(command.packId), requireNotNull(command.resourcePath)); PluginOutcome.RefreshUi }
                VoiceOperation.REORDER -> { settings.reorderPacks(command.order); PluginOutcome.RefreshUi }
                VoiceOperation.REFRESH -> PluginOutcome.RefreshUi
                else -> PluginOutcome.Message("请在云端语音合成中管理账户和声音")
            }
        }
    }
    override suspend fun attach(environment: PluginEnvironment) { settings = CloudTtsSettings(environment.storage); engine = CloudTtsEngine(settings) }
    override val contributions = PluginContributions(settingsEntries = listOf(
        SettingsEntry("cloud-tts", "云端语音合成", "settings", PluginIcon.SPEAKER, "audio", "音频", "管理云端账户和声音", 40)))
    override fun page(pageId: String, context: PluginContext) = ttsSettingsPage(settings, drafts, pageId)
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = outcome {
        val p = actionId.split('/')
        fun id() = ttsDecode(requireNotNull(p.getOrNull(1)) { "此操作已失效" })
        fun account() = settings.providers().firstOrNull { it.id == id() } ?: error("此账户已移除")
        fun value() = context.inputs["value"]?.trim() ?: error("请重新打开设置项并保存")
        fun draft() = drafts[id()] ?: error("声音草稿已失效，请重新添加")
        when (p[0]) {
            "provider", "pack", "voice" -> { requireNotNull(page(actionId, context)); PluginOutcome.OpenPluginPage(descriptor.id, actionId) }
            "add-provider" -> if (p.size == 1) PluginOutcome.Menu("选择服务商", CloudTtsProvider.entries.map { PageAction("add-provider/${it.key}", it.label, PluginIcon.SPEAKER) })
                else { val next = settings.addProvider(CloudTtsProvider.of(p[1])); PluginOutcome.OpenPluginPage(descriptor.id, "provider/${ttsEncode(next.id)}") }
            "toggle" -> { settings.toggleProvider(id()); PluginOutcome.RefreshUi }
            "select-provider" -> { settings.selectProvider(id()); PluginOutcome.RefreshUi }
            "remove-provider" -> { val c = account(); PluginOutcome.Confirm("移除此账户？", "${c.name}及其语音包和声音将被移除", PageAction("remove-provider-confirm/${ttsEncode(c.id)}", "移除", PluginIcon.CLOSE, symbol = "delete")) }
            "remove-provider-confirm" -> { settings.removeProvider(id()); PluginOutcome.OpenPluginPage(descriptor.id, "settings") }
            "edit" -> {
                val c = account(); val field = ttsFields(c).firstOrNull { it.key == p.getOrNull(2) } ?: error("此设置项已失效")
                PluginOutcome.TextInput(field.label, "save/${ttsEncode(c.id)}/${field.key}", field.value, secret = field.secret)
            }
            "save" -> { settings.updateProvider(id(), requireNotNull(p.getOrNull(2)), value()); PluginOutcome.RefreshUi }
            "add-pack" -> { val c = account(); PluginOutcome.TextInput("语音包名称", "new-pack/${ttsEncode(c.id)}") }
            "new-pack" -> { val c = account(); val pack = settings.createPack(c.id, value()); PluginOutcome.OpenPluginPage(descriptor.id, "pack/${ttsEncode(pack.id)}") }
            "remove-pack" -> { val pack = pack(id()); PluginOutcome.Confirm("移除此语音包？", pack.name, PageAction("remove-pack-confirm/${ttsEncode(pack.id)}", "移除", PluginIcon.CLOSE, symbol = "delete")) }
            "remove-pack-confirm" -> { val pack = pack(id()); settings.removePack(pack.id); PluginOutcome.OpenPluginPage(descriptor.id, "provider/${ttsEncode(pack.providerId)}") }
            "add-voice" -> {
                val pack = pack(id()); val provider = settings.providers().first { it.id == pack.providerId }
                val options = mutableListOf(PageAction("new-voice/${ttsEncode(pack.id)}/PRESET", "预设声音", PluginIcon.SPEAKER, symbol = "volume_up"))
                if (provider.provider in listOf(CloudTtsProvider.TENCENT, CloudTtsProvider.AZURE)) options += PageAction("new-voice/${ttsEncode(pack.id)}/CLONE", "克隆声音", PluginIcon.ROBOT2, symbol = "record_voice_over")
                PluginOutcome.Menu("添加声音", options)
            }
            "new-voice" -> {
                val pack = pack(id()); val kind = CloudVoiceKind.valueOf(requireNotNull(p.getOrNull(2)))
                val provider = settings.providers().first { it.id == pack.providerId }
                require(kind != CloudVoiceKind.CLONE || provider.provider in listOf(CloudTtsProvider.TENCENT, CloudTtsProvider.AZURE)) { "此接口暂不支持克隆声音" }
                if (drafts.size >= 8) drafts.remove(drafts.keys.first())
                val draft = CloudVoiceDraft(UUID.randomUUID().toString(), pack.id, kind); drafts[draft.id] = draft
                PluginOutcome.OpenPluginPage(descriptor.id, "draft/${ttsEncode(draft.id)}")
            }
            "edit-draft" -> {
                val draft = draft(); val field = draft.fields().firstOrNull { it.key == p.getOrNull(2) } ?: error("此设置项已失效")
                PluginOutcome.TextInput(field.label, "save-draft/${ttsEncode(draft.id)}/${field.key}", field.value)
            }
            "save-draft" -> { val draft = draft(); val text = value(); require(text.length <= 512) { "内容过长" }; drafts[draft.id] = draft.update(requireNotNull(p.getOrNull(2)), text); PluginOutcome.RefreshUi }
            "save-voice" -> {
                val draft = draft(); val pack = pack(draft.packId)
                settings.addVoice(pack.id, pack.providerId, draft.name, draft.language, draft.kind, draft.remoteId, draft.description)
                drafts.remove(draft.id); PluginOutcome.OpenPluginPage(descriptor.id, "pack/${ttsEncode(pack.id)}")
            }
            "cancel-voice" -> { val draft = draft(); drafts.remove(draft.id); PluginOutcome.OpenPluginPage(descriptor.id, "pack/${ttsEncode(draft.packId)}") }
            "select-voice" -> { settings.selectVoice(id()); PluginOutcome.RefreshUi }
            "rename-voice" -> { val voice = settings.voiceList().first { it.id == id() }; PluginOutcome.TextInput("声音名称", "save-voice-name/${ttsEncode(voice.id)}", voice.name) }
            "save-voice-name" -> { settings.renameVoice(id(), value()); PluginOutcome.RefreshUi }
            "remove-voice" -> { val voice = settings.voiceList().first { it.id == id() }; PluginOutcome.Confirm("移除此声音？", voice.name, PageAction("remove-voice-confirm/${ttsEncode(voice.id)}", "移除", PluginIcon.CLOSE, symbol = "delete")) }
            "remove-voice-confirm" -> { settings.removeVoice(id()); PluginOutcome.RefreshUi }
            else -> PluginOutcome.Message("此操作已失效，请重新打开页面")
        }
    }
    private fun pack(id: String) = settings.packList().firstOrNull { it.id == id } ?: error("此语音包已移除")
    private inline fun outcome(block: () -> PluginOutcome): PluginOutcome = try { block() }
        catch (error: IllegalArgumentException) { PluginOutcome.Message(error.message ?: "请检查账户设置") }
        catch (error: IllegalStateException) { PluginOutcome.Message(error.message ?: "此操作已失效，请重新打开页面") }
        catch (_: NoSuchElementException) { PluginOutcome.Message("此条目已移除，请返回重新选择") }
}
