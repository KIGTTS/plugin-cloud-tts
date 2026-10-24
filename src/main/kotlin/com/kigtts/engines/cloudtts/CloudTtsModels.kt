package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

enum class CloudTtsProvider(val key: String, val label: String, val defaultEndpoint: String) {
    TENCENT("tencent", "腾讯云", "https://tts.tencentcloudapi.com"),
    ALIYUN("aliyun", "阿里云", "https://nls-gateway.cn-shanghai.aliyuncs.com/stream/v1/tts"),
    ARZUE("arzue", "Arzue", "https://api.arzue.example/v1/tts"),
    BAIDU("baidu", "百度云", "https://tsn.baidu.com/text2audio"),
    IFlytek("iflytek", "讯飞", "https://tts-api.xfyun.cn/v2/tts");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: ARZUE }
}

enum class CloudVoiceKind { PRESET, CLONE }
internal val CloudVoiceKind.label: String get() = if (this == CloudVoiceKind.CLONE) "克隆声音" else "预设声音"

data class CloudTtsConfig(val id: String, val provider: CloudTtsProvider, val name: String,
    val endpoint: String, val apiKey: String = "", val apiSecret: String = "", val appId: String = "",
    val model: String = "", val enabled: Boolean = true) {
    val configured get() = endpoint.startsWith("https://") && apiKey.isNotBlank()
    fun validate() { require(URI(endpoint).scheme == "https") { "接口地址必须使用 HTTPS" } }
}

data class CloudVoice(val id: String, val packId: String, val providerId: String, val name: String,
    val language: String, val kind: CloudVoiceKind, val description: String = "")

data class CloudPack(val id: String, val providerId: String, val name: String, val remark: String = "",
    val voiceIds: List<String> = emptyList())

internal class CloudTtsSettings(private val storage: PluginStorage) {
    private val file = storage.file("config/cloud-tts.json").also { storage.configuration }
    private val configs = mutableListOf<CloudTtsConfig>()
    private val voices = mutableListOf<CloudVoice>()
    private val packs = mutableListOf<CloudPack>()
    var selectedProviderId: String? = null
    var selectedVoiceId: String? = null
    init { runCatching { load(JSONObject(file.readText())) } }
    private fun load(root: JSONObject) {
        selectedProviderId = root.optString("selectedProvider").takeIf { it.isNotBlank() }
        selectedVoiceId = root.optString("selectedVoice").takeIf { it.isNotBlank() }
        root.optJSONArray("configs")?.forEachObject { item ->
            val p = CloudTtsProvider.of(item.optString("provider")); configs += CloudTtsConfig(item.optString("id"), p,
                item.optString("name", p.label), item.optString("endpoint", p.defaultEndpoint), item.optString("apiKey"), item.optString("apiSecret"), item.optString("appId"), item.optString("model"), item.optBoolean("enabled", true))
        }
        root.optJSONArray("packs")?.forEachObject { item -> packs += CloudPack(item.optString("id"), item.optString("providerId"), item.optString("name"), item.optString("remark"), item.optJSONArray("voices")?.strings() ?: emptyList()) }
        root.optJSONArray("voices")?.forEachObject { item -> voices += CloudVoice(item.optString("id"), item.optString("packId"), item.optString("providerId"), item.optString("name"), item.optString("language", "zh-CN"), runCatching { CloudVoiceKind.valueOf(item.optString("kind", "PRESET")) }.getOrDefault(CloudVoiceKind.PRESET), item.optString("description")) }
    }
    fun providers() = configs.toList()
    fun voiceList() = voices.toList()
    fun packList() = packs.toList()
    /** Selected configuration stays visible before credentials are filled so packs can be prepared first. */
    fun currentProvider() = configs.firstOrNull { it.id == selectedProviderId && it.enabled }
        ?: configs.firstOrNull { it.enabled && it.configured }
    fun currentVoice() = voices.firstOrNull { it.id == selectedVoiceId }
    fun addProvider(provider: CloudTtsProvider): CloudTtsConfig = CloudTtsConfig("${provider.key}-${UUID.randomUUID().toString().take(8)}", provider, provider.label, provider.defaultEndpoint).also { configs += it; selectedProviderId = it.id; save() }
    fun updateProvider(id: String, field: String, value: String) {
        val i = configs.indexOfFirst { it.id == id }; require(i >= 0) { "找不到语音服务" }; val old = configs[i]
        configs[i] = when (field) { "name" -> old.copy(name = value); "endpoint" -> old.copy(endpoint = value); "apiKey" -> old.copy(apiKey = value); "apiSecret" -> old.copy(apiSecret = value); "appId" -> old.copy(appId = value); "model" -> old.copy(model = value); else -> error("不支持的语音服务设置") }; configs[i].validate(); save()
    }
    fun toggleProvider(id: String) { val i = configs.indexOfFirst { it.id == id }; require(i >= 0); configs[i] = configs[i].copy(enabled = !configs[i].enabled); save() }
    fun selectProvider(id: String) { require(configs.any { it.id == id }); selectedProviderId = id; save() }
    fun removeProvider(id: String) { configs.removeIf { it.id == id }; packs.removeIf { it.providerId == id }; voices.removeIf { it.providerId == id }; if (selectedProviderId == id) selectedProviderId = configs.firstOrNull()?.id; save() }
    fun createPack(providerId: String, name: String, remark: String = ""): CloudPack = CloudPack("pack-${UUID.randomUUID().toString().take(10)}", providerId, name, remark).also { packs += it; save() }
    fun addVoice(packId: String, providerId: String, name: String, language: String, kind: CloudVoiceKind, remoteId: String, description: String = ""): CloudVoice {
        require(packs.any { it.id == packId && it.providerId == providerId }) { "请先选择有效的声音包" }
        val voice = CloudVoice(remoteId.ifBlank { "voice-${UUID.randomUUID().toString().take(10)}" }, packId, providerId, name, language, kind, description)
        voices.removeIf { it.id == voice.id }; voices += voice; val i = packs.indexOfFirst { it.id == packId }; packs[i] = packs[i].copy(voiceIds = (packs[i].voiceIds + voice.id).distinct()); if (selectedVoiceId == null) selectedVoiceId = voice.id; save(); return voice
    }
    fun selectVoice(id: String) { require(voices.any { it.id == id }); selectedVoiceId = id; save() }
    fun removeVoice(id: String) { voices.removeIf { it.id == id }; packs.indices.forEach { i -> packs[i] = packs[i].copy(voiceIds = packs[i].voiceIds - id) }; if (selectedVoiceId == id) selectedVoiceId = voices.firstOrNull()?.id; save() }
    fun renameVoice(id: String, name: String) { val i = voices.indexOfFirst { it.id == id }; require(i >= 0); voices[i] = voices[i].copy(name = name); save() }
    fun toProvider(): TtsProvider? = currentProvider()?.let { provider -> TtsProvider(provider.id, provider.name, TtsInfo(availability = if (provider.configured) EngineAvailability.READY else EngineAvailability.NEEDS_RESOURCES, voices = voices.filter { it.providerId == provider.id }.map { it.toInfo(packs) }, maxTextLength = 4_000, role = TtsRole.PROVIDER, selectedVoiceId = currentVoice()?.id, managesVoices = true, packs = packs.filter { it.providerId == provider.id }.map { it.toInfo() })) }
    private fun CloudVoice.toInfo(packs: List<CloudPack>) = VoiceInfo(id, name, language, if (description.isBlank()) kind.label else "${kind.label} · $description", deletable = true, packName = packs.firstOrNull { it.id == packId }?.name, packId = packId)
    private fun CloudPack.toInfo() = VoicePackInfo(id, VoicePackMetadata(name, remark), voiceIds, editable = true, deletable = true)
    fun save() { val c = JSONArray(); configs.forEach { x -> c.put(JSONObject().put("id", x.id).put("provider", x.provider.key).put("name", x.name).put("endpoint", x.endpoint).put("apiKey", x.apiKey).put("apiSecret", x.apiSecret).put("appId", x.appId).put("model", x.model).put("enabled", x.enabled)) }; val p = JSONArray(); packs.forEach { x -> p.put(JSONObject().put("id", x.id).put("providerId", x.providerId).put("name", x.name).put("remark", x.remark).put("voices", JSONArray(x.voiceIds))) }; val v = JSONArray(); voices.forEach { x -> v.put(JSONObject().put("id", x.id).put("packId", x.packId).put("providerId", x.providerId).put("name", x.name).put("language", x.language).put("kind", x.kind.name).put("description", x.description)) }; file.writeText(JSONObject().put("selectedProvider", selectedProviderId).put("selectedVoice", selectedVoiceId).put("configs", c).put("packs", p).put("voices", v).toString(), Charsets.UTF_8) }
}

private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) { for (i in 0 until length()) optJSONObject(i)?.let(action) }
private fun JSONArray.strings() = (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
