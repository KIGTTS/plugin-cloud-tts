package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*

internal data class TtsField(val key: String, val label: String, val value: String, val secret: Boolean = false)
internal data class CloudVoiceDraft(val id: String, val packId: String, val kind: CloudVoiceKind,
    val name: String = "", val language: String = "zh-CN", val remoteId: String = "", val description: String = "") {
    fun fields() = listOf(TtsField("name", "声音名称", name), TtsField("remoteId", "服务商声音 ID", remoteId),
        TtsField("language", "语言代码", language), TtsField("description", "备注（可选）", description))
    fun update(field: String, value: String) = when (field) {
        "name" -> copy(name = value); "remoteId" -> copy(remoteId = value); "language" -> copy(language = value)
        "description" -> copy(description = value); else -> error("此设置项已失效")
    }
}

internal fun ttsFields(c: CloudTtsConfig): List<TtsField> = buildList {
    add(TtsField("name", "账户名称", c.name)); add(TtsField("endpoint", "服务地址", c.endpoint))
    add(TtsField("apiKey", when (c.provider) { CloudTtsProvider.TENCENT -> "SecretId"; CloudTtsProvider.ALIYUN -> "AccessKey ID"; CloudTtsProvider.AZURE -> "Speech Key"; else -> "API Key" }, c.apiKey, true))
    if (c.provider != CloudTtsProvider.AZURE) add(TtsField("apiSecret", when (c.provider) { CloudTtsProvider.TENCENT -> "SecretKey"; CloudTtsProvider.ALIYUN -> "AccessKey Secret"; else -> "Secret Key" }, c.apiSecret, true))
    if (c.provider in listOf(CloudTtsProvider.ALIYUN, CloudTtsProvider.IFlytek)) add(TtsField("appId", if (c.provider == CloudTtsProvider.ALIYUN) "AppKey" else "App ID", c.appId))
    if (c.provider == CloudTtsProvider.AZURE) add(TtsField("model", "自定义声音部署 ID（可选）", c.model))
}

internal fun ttsSettingsPage(settings: CloudTtsSettings, drafts: Map<String, CloudVoiceDraft>, pageId: String): PluginPage? {
    if (pageId == "settings") return PluginPage(pageId, "云端语音合成", listOf(
        PageBlock.Group("服务账户", listOf(PageBlock.Actions(listOf(PageAction("add-provider", "添加账户", PluginIcon.ADD)))))
    ), header = ManagementItem("cloud-tts-header", "云端语音合成", "用喜欢的声音朗读文字", PluginIcon.SPEAKER, symbol = "cloud"),
        management = ManagementList("cloud-tts-accounts", settings.providers().map { c -> ManagementItem(
            id = c.id, title = c.name, subtitle = if (c.configured) "已配置" else "待配置", icon = PluginIcon.SPEAKER,
            group = "已添加的账户", primaryAction = "provider/${ttsEncode(c.id)}", symbol = "cloud", badges = listOf(c.provider.label),
            actions = listOf(PageAction("remove-provider/${ttsEncode(c.id)}", "移除", PluginIcon.CLOSE, symbol = "delete")),
            toggle = ManagementToggle(c.enabled, "toggle/${ttsEncode(c.id)}")
        ) }, emptyText = "添加一个云端服务账户"))
    val p = pageId.split('/'); val id = runCatching { ttsDecode(p.getOrElse(1) { "" }) }.getOrNull()
    if (p[0] == "provider") {
        val c = settings.providers().firstOrNull { it.id == id } ?: return missingPage(pageId, "账户")
        return PluginPage(pageId, c.name, listOf(
            PageBlock.Group("连接信息", ttsFields(c).map { field -> PageBlock.Input(field.label, field.value, "edit/${ttsEncode(c.id)}/${field.key}", secret = field.secret) }),
            PageBlock.Group("语音包", listOf(PageBlock.Actions(listOf(PageAction("add-pack/${ttsEncode(c.id)}", "创建语音包", PluginIcon.ADD))))),
            PageBlock.Group("账户", listOf(PageBlock.Toggle("启用账户", c.enabled, "toggle/${ttsEncode(c.id)}", icon = PluginIcon.SPEAKER)))
        ), header = ManagementItem("cloud-tts-account", c.name, c.provider.label, PluginIcon.SPEAKER, symbol = "cloud"),
            management = ManagementList("cloud-tts-packs-${c.id}", settings.packList().filter { it.providerId == c.id }.map { pack -> ManagementItem(
                id = pack.id, title = pack.name, subtitle = pack.remark, icon = PluginIcon.SPEAKER, primaryAction = "pack/${ttsEncode(pack.id)}",
                group = "语音包", badges = listOf(c.provider.label), symbol = "library_music",
                actions = listOf(PageAction("remove-pack/${ttsEncode(pack.id)}", "移除", PluginIcon.CLOSE, symbol = "delete"))
            ) }, emptyText = "创建语音包，再添加需要的声音"))
    }
    if (p[0] == "pack") {
        val pack = settings.packList().firstOrNull { it.id == id } ?: return missingPage(pageId, "语音包")
        val c = settings.providers().firstOrNull { it.id == pack.providerId } ?: return missingPage(pageId, "账户")
        return PluginPage(pageId, pack.name, listOf(PageBlock.Group("声音", listOf(PageBlock.Actions(listOf(PageAction("add-voice/${ttsEncode(pack.id)}", "添加声音", PluginIcon.ADD)))))),
            header = ManagementItem("cloud-tts-pack", pack.name, c.name, PluginIcon.SPEAKER, badges = listOf(c.provider.label), symbol = "library_music"),
            management = ManagementList("cloud-tts-voices-${pack.id}", settings.voiceList().filter { it.packId == pack.id }.map { voice -> ManagementItem(
                id = voice.id, title = voice.name, subtitle = voice.language + voice.description.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                icon = PluginIcon.SPEAKER, symbol = if (voice.kind == CloudVoiceKind.CLONE) "record_voice_over" else "volume_up",
                group = voice.kind.label, badges = listOf(c.provider.label), selected = settings.currentVoice()?.id == voice.id,
                primaryAction = "voice/${ttsEncode(voice.id)}", selection = ManagementSelection("select-voice/${ttsEncode(voice.id)}", enabled = c.enabled && c.configured),
                actions = listOf(PageAction("remove-voice/${ttsEncode(voice.id)}", "移除", PluginIcon.CLOSE, symbol = "delete"))
            ) }, emptyText = "添加服务商提供的声音 ID"))
    }
    if (p[0] == "voice") {
        val voice = settings.voiceList().firstOrNull { it.id == id } ?: return missingPage(pageId, "声音")
        return PluginPage(pageId, voice.name, listOf(PageBlock.Group("声音信息", listOf(PageBlock.Text(voice.kind.label), PageBlock.Text(voice.remoteId), PageBlock.Text(voice.language))),
            PageBlock.Group("使用声音", listOf(PageBlock.Actions(listOf(PageAction("select-voice/${ttsEncode(voice.id)}", "使用此声音", PluginIcon.SPEAKER),
                PageAction("rename-voice/${ttsEncode(voice.id)}", "重命名", PluginIcon.EDIT)))))))
    }
    if (p[0] == "draft") {
        val draft = drafts[id] ?: return missingPage(pageId, "声音草稿")
        return PluginPage(pageId, "添加${draft.kind.label}", listOf(PageBlock.Group("声音信息", draft.fields().map { field ->
            PageBlock.Input(field.label, field.value, "edit-draft/${ttsEncode(draft.id)}/${field.key}")
        }), PageBlock.Group("添加", listOf(PageBlock.Actions(listOf(PageAction("save-voice/${ttsEncode(draft.id)}", "添加声音", PluginIcon.ADD),
            PageAction("cancel-voice/${ttsEncode(draft.id)}", "取消", PluginIcon.CLOSE)))))))
    }
    return null
}
private fun missingPage(id: String, kind: String) = PluginPage(id, kind, listOf(PageBlock.Notice("此${kind}已移除，请返回重新选择")))
internal fun ttsEncode(value: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
internal fun ttsDecode(value: String) = String(java.util.Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
