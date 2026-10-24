package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

internal class CloudTtsEngine(private val settings: CloudTtsSettings) : TtsEngine {
    override val info: TtsInfo get() = TtsInfo(availability = if (settings.currentProvider()?.configured == true && settings.currentVoice() != null) EngineAvailability.READY else EngineAvailability.NEEDS_RESOURCES,
        voices = settings.voiceList().filter { it.providerId == settings.currentProvider()?.id }.map { voice -> VoiceInfo(voice.id, voice.name, voice.language, if (voice.description.isBlank()) voice.kind.label else "${voice.kind.label} · ${voice.description}", deletable = true, packId = voice.packId) },
        selectedVoiceId = settings.currentVoice()?.id, role = TtsRole.PROVIDER, managesVoices = true,
        packs = settings.packList().filter { it.providerId == settings.currentProvider()?.id }.map { VoicePackInfo(it.id, VoicePackMetadata(it.name, it.remark), it.voiceIds, editable = true, deletable = true) }, supportsControl = false)
    override fun synthesize(request: TtsRequest): Flow<SynthesisEvent> = flow {
        val provider = settings.currentProvider() ?: throw EngineException(EngineError.NOT_CONFIGURED, "请先配置云端语音服务")
        val voice = settings.currentVoice() ?: throw EngineException(EngineError.NOT_CONFIGURED, "请先添加并选用声音")
        if (voice.providerId != provider.id || !provider.configured) throw EngineException(EngineError.NOT_CONFIGURED, "当前声音或接口不可用")
        emit(SynthesisEvent.Started)
        val json = CloudTtsHttp.post(provider, voice, request)
        val audio = json.optString("audio").ifBlank { json.optString("audioBase64") }.ifBlank { throw EngineException(EngineError.INFERENCE, "云端接口没有返回音频") }
        val bytes = runCatching { Base64.getDecoder().decode(audio) }.getOrElse { throw EngineException(EngineError.INFERENCE, "云端音频格式无效") }
        emit(SynthesisEvent.Audio(PcmAudio(bytes, json.optInt("sampleRate", 16_000), 1)))
        emit(SynthesisEvent.Finished())
    }.flowOn(Dispatchers.IO)
}

internal object CloudTtsHttp {
    fun post(config: CloudTtsConfig, voice: CloudVoice, request: TtsRequest): JSONObject {
        val body = JSONObject().put("text", request.text).put("voiceId", voice.id).put("voiceKind", voice.kind.name.lowercase()).put("language", request.language).put("rate", request.rate).put("model", config.model).put("appId", config.appId)
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "application/json"); setRequestProperty("X-KIGTTS-Provider", config.provider.key)
            if (config.apiKey.isNotBlank()) setRequestProperty("X-Api-Key", config.apiKey)
            if (config.apiSecret.isNotBlank()) setRequestProperty("X-Api-Secret", config.apiSecret)
            if (config.appId.isNotBlank()) setRequestProperty("X-App-Id", config.appId)
        }
        connection.outputStream.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(body.toString()) } }
        val code = connection.responseCode; val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = input?.let { BufferedInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) } }.orEmpty(); connection.disconnect()
        if (code !in 200..299) throw EngineException(if (code == 408) EngineError.TIMEOUT else EngineError.INFERENCE, "云端合成请求失败（HTTP $code）")
        return runCatching { JSONObject(response) }.getOrElse { throw EngineException(EngineError.INFERENCE, "云端合成返回格式无效") }
    }
}
