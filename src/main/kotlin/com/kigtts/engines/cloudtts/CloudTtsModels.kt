package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

enum class CloudTtsProvider(val key: String, val label: String, val defaultEndpoint: String) {
    TENCENT("tencent", "腾讯云", "https://tts.tencentcloudapi.com"),
    ALIYUN("aliyun", "阿里云", "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts"),
    AZURE("azure", "Azure", "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1"),
    BAIDU("baidu", "百度云", "https://tsn.baidu.com/text2audio"),
    IFlytek("iflytek", "讯飞", "https://tts-api.xfyun.cn/v2/tts");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == if (key == "arzue") "azure" else key } ?: AZURE }
}

enum class CloudVoiceKind { PRESET, CLONE }
internal val CloudVoiceKind.label: String get() = if (this == CloudVoiceKind.CLONE) "克隆声音" else "预设声音"

data class CloudTtsConfig(val id: String, val provider: CloudTtsProvider, val name: String,
    val endpoint: String, val apiKey: String = "", val apiSecret: String = "", val appId: String = "",
    val model: String = "", val enabled: Boolean = true) {
    val configured: Boolean get() = runCatching { validate() }.isSuccess && apiKey.isNotBlank() &&
        (provider == CloudTtsProvider.AZURE || apiSecret.isNotBlank()) &&
        (provider !in listOf(CloudTtsProvider.ALIYUN, CloudTtsProvider.IFlytek) || appId.isNotBlank())
    fun validate() {
        val uri = URI(endpoint)
        require(uri.scheme == "https" || provider == CloudTtsProvider.IFlytek && uri.scheme == "wss") { "接口地址必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) { "请输入有效的服务接口地址" }
        require(name.length <= 100 && apiKey.length <= 4096 && apiSecret.length <= 4096 && appId.length <= 512 && model.length <= 256) { "设置内容过长" }
    }
}

data class CloudVoice(val id: String, val packId: String, val providerId: String, val name: String,
    val language: String, val kind: CloudVoiceKind, val description: String = "", val remoteId: String = id)

data class CloudPack(val id: String, val providerId: String, val name: String, val remark: String = "",
    val voiceIds: List<String> = emptyList(), val pinned: Boolean = false, val order: Long = 0,
    val avatarPath: String? = null)

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
                item.optString("name", p.label), item.optString("endpoint", p.defaultEndpoint).let { if (it.contains("api.arzue.example")) p.defaultEndpoint else it }, item.optString("apiKey"), item.optString("apiSecret"), item.optString("appId"), item.optString("model"), item.optBoolean("enabled", true))
        }
        root.optJSONArray("packs")?.forEachObject { item -> packs += CloudPack(item.optString("id"), item.optString("providerId"), item.optString("name"), item.optString("remark"), item.optJSONArray("voices")?.strings() ?: emptyList(), item.optBoolean("pinned"), item.optLong("order"), item.optString("avatarPath").takeIf { it.isNotBlank() }) }
        root.optJSONArray("voices")?.forEachObject { item -> voices += CloudVoice(item.optString("id"), item.optString("packId"), item.optString("providerId"), item.optString("name"), item.optString("language", "zh-CN"), runCatching { CloudVoiceKind.valueOf(item.optString("kind", "PRESET")) }.getOrDefault(CloudVoiceKind.PRESET), item.optString("description"), item.optString("remoteId", item.optString("id"))) }
    }
    fun providers() = configs.toList()
    fun voiceList() = voices.toList()
    fun packList() = packs.toList()
    /** Selected configuration stays visible before credentials are filled so packs can be prepared first. */
    fun currentProvider() = configs.firstOrNull { it.id == selectedProviderId && it.enabled }
        ?: configs.firstOrNull { it.enabled && it.configured }
    fun currentVoice() = voices.firstOrNull { it.id == selectedVoiceId && configs.any { c -> c.id == it.providerId && c.enabled } }
    fun addProvider(provider: CloudTtsProvider): CloudTtsConfig = CloudTtsConfig("${provider.key}-${UUID.randomUUID().toString().take(8)}", provider, provider.label, provider.defaultEndpoint).also { configs += it; selectedProviderId = it.id; save() }
    fun updateProvider(id: String, field: String, value: String) {
        val i = configs.indexOfFirst { it.id == id }; require(i >= 0) { "找不到语音服务" }; val old = configs[i]
        val next = when (field) { "name" -> old.copy(name = value); "endpoint" -> old.copy(endpoint = value); "apiKey" -> old.copy(apiKey = value); "apiSecret" -> old.copy(apiSecret = value); "appId" -> old.copy(appId = value); "model" -> old.copy(model = value); else -> error("不支持的语音服务设置") }; next.validate(); configs[i] = next; save()
    }
    fun toggleProvider(id: String) { val i = configs.indexOfFirst { it.id == id }; require(i >= 0); configs[i] = configs[i].copy(enabled = !configs[i].enabled); save() }
    fun selectProvider(id: String) { require(configs.any { it.id == id }); selectedProviderId = id; if (currentVoice()?.providerId != id) selectedVoiceId = voices.firstOrNull { it.providerId == id }?.id; save() }
    fun removeProvider(id: String) { configs.removeIf { it.id == id }; packs.removeIf { it.providerId == id }; voices.removeIf { it.providerId == id }; if (selectedProviderId == id) selectedProviderId = configs.firstOrNull()?.id; save() }
    fun createPack(providerId: String, name: String, remark: String = ""): CloudPack {
        require(configs.any { it.id == providerId }) { "此服务账户已移除" }
        require(name.isNotBlank() && name.length <= 100 && remark.length <= 500) { "请填写语音包名称（最多 100 字）" }
        return CloudPack("pack-${UUID.randomUUID().toString().take(10)}", providerId, name.trim(), remark).also { packs += it; save() }
    }
    fun addVoice(packId: String, providerId: String, name: String, language: String, kind: CloudVoiceKind, remoteId: String, description: String = ""): CloudVoice {
        require(packs.any { it.id == packId && it.providerId == providerId }) { "请先选择有效的声音包" }
        require(name.isNotBlank() && name.length <= 100 && language.isNotBlank() && language.length <= 64 && remoteId.isNotBlank() && remoteId.length <= 512 && description.length <= 500) { "请填写声音名称、语言和有效的声音 ID" }
        require(voices.none { it.packId == packId && it.remoteId == remoteId && it.kind == kind }) { "此声音已在语音包中" }
        val voice = CloudVoice("voice-${UUID.randomUUID()}", packId, providerId, name.trim(), language.trim(), kind, description, remoteId.trim())
        voices += voice; val i = packs.indexOfFirst { it.id == packId }; packs[i] = packs[i].copy(voiceIds = (packs[i].voiceIds + voice.id).distinct()); if (selectedVoiceId == null) selectedVoiceId = voice.id; save(); return voice
    }
    fun selectVoice(id: String) { val voice = voices.firstOrNull { it.id == id } ?: error("此声音已移除"); require(configs.any { it.id == voice.providerId && it.enabled && it.configured }) { "请先完成服务配置并启用" }; selectedProviderId = voice.providerId; selectedVoiceId = id; save() }
    fun removePack(id: String) { val removed = voices.filter { it.packId == id }.map { it.id }.toSet(); voices.removeIf { it.id in removed }; packs.removeIf { it.id == id }; if (selectedVoiceId in removed) selectedVoiceId = null; save() }
    fun removeVoice(id: String) { voices.removeIf { it.id == id }; packs.indices.forEach { i -> packs[i] = packs[i].copy(voiceIds = packs[i].voiceIds - id) }; if (selectedVoiceId == id) selectedVoiceId = voices.firstOrNull()?.id; save() }
    fun renameVoice(id: String, name: String) { require(name.isNotBlank() && name.length <= 100) { "请填写声音名称（最多 100 字）" }; val i = voices.indexOfFirst { it.id == id }; require(i >= 0) { "此声音已移除" }; voices[i] = voices[i].copy(name = name.trim()); save() }
    fun updatePack(id: String, metadata: VoicePackMetadata) {
        require(metadata.name.isNotBlank() && metadata.name.length <= 100 && metadata.remark.length <= 500) { "请填写有效的语音包名称和备注" }
        val index = packs.indexOfFirst { it.id == id }; require(index >= 0) { "此语音包已移除" }
        packs[index] = packs[index].copy(name = metadata.name.trim(), remark = metadata.remark, pinned = metadata.pinned, order = metadata.order); save()
    }
    fun reorderPacks(ids: List<String>) {
        require(ids.distinct().size == ids.size && ids.all { id -> packs.any { it.id == id } }) { "语音包列表已变更，请重试" }
        val order = ids + packs.map { it.id }.filter { it !in ids }
        order.forEachIndexed { index, id -> val i = packs.indexOfFirst { it.id == id }; packs[i] = packs[i].copy(order = index.toLong()) }; save()
    }
    fun setAvatar(id: String, sourcePath: String) {
        val index = packs.indexOfFirst { it.id == id }; require(index >= 0) { "此语音包已移除" }
        val next = CloudVoiceAvatar.copy(storage, sourcePath)
        val previous = packs[index].avatarPath
        packs[index] = packs[index].copy(avatarPath = next); save()
        previous?.let { runCatching { storage.file(it).delete() } }
    }
    fun engineInfo(): TtsInfo {
        val availableIds = configs.filter { it.enabled && it.configured }.map { it.id }.toSet()
        val availableVoices = voices.filter { it.providerId in availableIds }
        return TtsInfo(availability = if (availableVoices.isNotEmpty()) EngineAvailability.READY else EngineAvailability.NEEDS_RESOURCES,
            voices = availableVoices.map { it.toInfo(packs) }, selectedVoiceId = currentVoice()?.id?.takeIf { id -> availableVoices.any { it.id == id } },
            role = TtsRole.PROVIDER, managesVoices = true, packs = packs.filter { it.providerId in availableIds }.map { it.toInfo() })
    }
    fun toProvider(): TtsProvider? = if (configs.isEmpty()) null else TtsProvider("engine.cloud-tts", "云端语音合成", engineInfo())
    private fun CloudVoice.toInfo(packs: List<CloudPack>) = VoiceInfo(id, name, language, if (description.isBlank()) kind.label else "${kind.label} · $description", deletable = true, packName = packs.firstOrNull { it.id == packId }?.name, packId = packId)
    private fun CloudPack.toInfo() = VoicePackInfo(id, VoicePackMetadata(name, remark, pinned, order), voiceIds, avatarPath = avatarPath, editable = true, deletable = true,
        providerLabel = configs.firstOrNull { it.id == providerId }?.provider?.label.orEmpty())
    fun save() { val c = JSONArray(); configs.forEach { x -> c.put(JSONObject().put("id", x.id).put("provider", x.provider.key).put("name", x.name).put("endpoint", x.endpoint).put("apiKey", x.apiKey).put("apiSecret", x.apiSecret).put("appId", x.appId).put("model", x.model).put("enabled", x.enabled)) }; val p = JSONArray(); packs.forEach { x -> p.put(JSONObject().put("id", x.id).put("providerId", x.providerId).put("name", x.name).put("remark", x.remark).put("voices", JSONArray(x.voiceIds)).put("pinned", x.pinned).put("order", x.order).put("avatarPath", x.avatarPath)) }; val v = JSONArray(); voices.forEach { x -> v.put(JSONObject().put("id", x.id).put("packId", x.packId).put("providerId", x.providerId).put("name", x.name).put("language", x.language).put("kind", x.kind.name).put("description", x.description).put("remoteId", x.remoteId)) }; file.writeText(JSONObject().put("selectedProvider", selectedProviderId).put("selectedVoice", selectedVoiceId).put("configs", c).put("packs", p).put("voices", v).toString(), Charsets.UTF_8) }
}

private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) { for (i in 0 until length()) optJSONObject(i)?.let(action) }
private fun JSONArray.strings() = (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
