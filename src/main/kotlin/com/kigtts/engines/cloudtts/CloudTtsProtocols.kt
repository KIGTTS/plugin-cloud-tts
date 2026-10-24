package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.roundToInt

internal class CloudTtsProtocols(private val transport: SpeechTransport = OkSpeechTransport(),
    private val clock: () -> Instant = Instant::now) {
    private val tokens = SpeechTokens(transport, clock)
    suspend fun synthesize(c: CloudTtsConfig, voice: CloudVoice, request: TtsRequest): PcmAudio {
        if (!c.configured || voice.remoteId.isBlank()) throw EngineException(EngineError.NOT_CONFIGURED, "请填写完整的服务配置和声音 ID")
        if (voice.kind == CloudVoiceKind.CLONE && c.provider !in listOf(CloudTtsProvider.TENCENT, CloudTtsProvider.AZURE))
            throw EngineException(EngineError.UNAVAILABLE, "此接口暂不支持克隆声音，请添加预设声音或使用腾讯云、Azure 自定义声音接口")
        return when (c.provider) {
            CloudTtsProvider.TENCENT -> tencent(c, voice, request)
            CloudTtsProvider.ALIYUN -> aliyun(c, voice, request)
            CloudTtsProvider.AZURE -> azure(c, voice, request)
            CloudTtsProvider.BAIDU -> baidu(c, voice, request)
            CloudTtsProvider.IFlytek -> xfyun(c, voice, request)
        }
    }
    private suspend fun tencent(c: CloudTtsConfig, v: CloudVoice, r: TtsRequest): PcmAudio {
        requireSecret(c)
        val body = JSONObject().put("Text", r.text).put("SessionId", UUID.randomUUID().toString())
            .put("ModelType", c.model.toIntOrNull() ?: 1).put("SampleRate", 16000).put("Codec", "pcm")
            .put("Speed", SpeechText.tencentSpeed(r.rate)).put("PrimaryLanguage", if (v.language.startsWith("en")) 2 else 1)
        val numericVoice = v.remoteId.toLongOrNull()
        if (v.kind == CloudVoiceKind.CLONE && numericVoice == null) body.put("VoiceType", 200000000).put("FastVoiceType", v.remoteId)
        else body.put("VoiceType", numericVoice ?: throw EngineException(EngineError.NOT_CONFIGURED, "腾讯云预设声音 ID 应为数字"))
        val bytes = body.toString().toByteArray()
        val headers = SpeechSigning.tencent(c.endpoint, "tts", "TextToVoice", "2019-08-23", c.apiKey, c.apiSecret, bytes, clock())
        val root = speechJson(transport.http(SpeechHttpRequest(c.endpoint, bytes, headers = headers)), secrets(c)).optJSONObject("Response")
            ?: throw EngineException(EngineError.INFERENCE, "腾讯云未返回合成结果")
        root.optJSONObject("Error")?.let { throw speechFailure(it.optString("Code"), it.optString("Message"), secrets(c)) }
        return SpeechPcm.output(decode(root.optString("Audio")))
    }
    private suspend fun aliyun(c: CloudTtsConfig, v: CloudVoice, r: TtsRequest): PcmAudio {
        if (c.appId.isBlank()) throw EngineException(EngineError.NOT_CONFIGURED, "请填写阿里云 AppKey")
        val token = tokens.aliyun(c.apiKey, c.apiSecret, c.endpoint)
        val speed = (if (r.rate < 1) (1 - 1 / r.rate) / 0.002 else (r.rate - 1) / 0.002).roundToInt().coerceIn(-500, 500)
        val body = JSONObject().put("appkey", c.appId).put("text", r.text).put("voice", v.remoteId)
            .put("format", "pcm").put("sample_rate", 16000).put("speech_rate", speed)
        return audio(transport.http(SpeechHttpRequest(c.endpoint, body.toString().toByteArray(), headers = mapOf("X-NLS-Token" to token))), secrets(c) + token)
    }
    private suspend fun azure(c: CloudTtsConfig, v: CloudVoice, r: TtsRequest): PcmAudio {
        var endpoint = c.endpoint
        if (v.kind == CloudVoiceKind.CLONE && !endpoint.contains("deploymentId=", ignoreCase = true)) {
            if (c.model.isBlank()) throw EngineException(EngineError.NOT_CONFIGURED, "Azure 自定义声音需要部署 ID 或包含 deploymentId 的接口地址")
            endpoint += (if ('?' in endpoint) "&" else "?") + SpeechSigning.query(mapOf("deploymentId" to c.model))
        }
        val body = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"${SpeechText.xml(v.language)}\"><voice name=\"${SpeechText.xml(v.remoteId)}\"><prosody rate=\"${((r.rate - 1) * 100).roundToInt()}%\">${SpeechText.xml(r.text)}</prosody></voice></speak>"
        return audio(transport.http(SpeechHttpRequest(endpoint, body.toByteArray(), "application/ssml+xml", mapOf(
            "Ocp-Apim-Subscription-Key" to c.apiKey, "X-Microsoft-OutputFormat" to "raw-16khz-16bit-mono-pcm", "User-Agent" to "KIGTTS"))), secrets(c))
    }
    private suspend fun baidu(c: CloudTtsConfig, v: CloudVoice, r: TtsRequest): PcmAudio {
        val token = tokens.baidu(c.apiKey, c.apiSecret)
        if (v.remoteId.toLongOrNull() == null) throw EngineException(EngineError.NOT_CONFIGURED, "百度云预设声音 ID 应为数字")
        val fields = mapOf("tex" to r.text, "tok" to token, "cuid" to "kigtts-${c.id.take(48)}", "ctp" to "1", "lan" to "zh",
            "per" to v.remoteId, "spd" to (5 + (r.rate - 1) * 4).roundToInt().coerceIn(0, 15).toString(), "aue" to "4",
            "audio_ctrl" to JSONObject().put("sampling_rate", 16000).toString())
        return audio(transport.http(SpeechHttpRequest(c.endpoint, SpeechSigning.query(fields).toByteArray(), "application/x-www-form-urlencoded")), secrets(c) + token)
    }
    private suspend fun xfyun(c: CloudTtsConfig, v: CloudVoice, r: TtsRequest): PcmAudio {
        requireSecret(c)
        if (c.appId.isBlank()) throw EngineException(EngineError.NOT_CONFIGURED, "请填写讯飞 App ID")
        val frame = JSONObject().put("common", JSONObject().put("app_id", c.appId)).put("business", JSONObject()
            .put("aue", "raw").put("auf", "audio/L16;rate=16000").put("vcn", v.remoteId).put("tte", "UTF8")
            .put("speed", (r.rate * 50).roundToInt().coerceIn(0, 100)))
            .put("data", JSONObject().put("status", 2).put("text", Base64.getEncoder().encodeToString(r.text.toByteArray())))
        val output = ByteArrayOutputStream()
        transport.socket(SpeechSigning.xfyun(c.endpoint, c.apiKey, c.apiSecret, clock()), listOf(SpeechFrame(frame.toString()))) { text ->
            val root = runCatching { JSONObject(text) }.getOrElse { throw EngineException(EngineError.INFERENCE, "讯飞返回了无效结果") }
            if (root.optInt("code", -1) != 0) throw speechFailure(root.optString("code"), root.optString("message"), secrets(c))
            val data = root.optJSONObject("data")
            val encoded = data?.optString("audio").orEmpty()
            if (encoded.isNotEmpty()) {
                val part = decode(encoded)
                if (output.size() + part.size > OkSpeechTransport.MAX_RESPONSE_BYTES) throw EngineException(EngineError.INFERENCE, "云端音频过大")
                output.write(part)
            }
            data?.optInt("status") == 2
        }
        return SpeechPcm.output(output.toByteArray())
    }
    private fun audio(response: SpeechHttpResponse, secrets: List<String>): PcmAudio {
        if (response.status !in 200..299 || !response.contentType.startsWith("audio/", ignoreCase = true) && !response.contentType.startsWith("application/octet-stream", ignoreCase = true)) {
            val error = speechJson(response, secrets)
            throw speechFailure(error.optString("err_no").ifBlank { error.optString("status") },
                error.optString("err_msg").ifBlank { error.optString("message") }, secrets)
        }
        val rate = Regex("(?:rate|samplerate)=(\\d+)", RegexOption.IGNORE_CASE).find(response.contentType)?.groupValues?.get(1)?.toIntOrNull() ?: 16000
        return SpeechPcm.output(response.body, rate)
    }
    private fun decode(value: String): ByteArray = runCatching { Base64.getDecoder().decode(value) }
        .getOrElse { throw EngineException(EngineError.UNSUPPORTED_FORMAT, "云端音频编码无效") }
    private fun requireSecret(c: CloudTtsConfig) { if (c.apiSecret.isBlank()) throw EngineException(EngineError.NOT_CONFIGURED, "请填写服务密钥 Secret") }
    private fun secrets(c: CloudTtsConfig) = listOf(c.apiKey, c.apiSecret, c.appId)
    suspend fun close() { transport.close(); tokens.clear() }
}

internal object SpeechText {
    fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun tencentSpeed(rate: Float): Double {
        val anchors = listOf(0.6 to -2.0, 0.8 to -1.0, 1.0 to 0.0, 1.2 to 1.0, 1.5 to 2.0, 2.5 to 6.0)
        val value = rate.toDouble().coerceIn(0.6, 2.5)
        val (a, b) = anchors.zipWithNext().first { value <= it.second.first }
        return a.second + (value - a.first) / (b.first - a.first) * (b.second - a.second)
    }
    /** UTF-8 and code-point limits both apply; supplementary characters are never split. */
    fun segments(text: String, maxCharacters: Int, maxBytes: Int): List<String> {
        val output = mutableListOf<String>(); val current = StringBuilder(); var bytes = 0; var count = 0
        val points = text.codePoints().iterator()
        while (points.hasNext()) {
            val part = String(Character.toChars(points.nextInt())); val length = part.toByteArray().size
            if (count >= maxCharacters || bytes + length > maxBytes) { output += current.toString(); current.setLength(0); bytes = 0; count = 0 }
            current.append(part); bytes += length; count++
        }
        if (current.isNotEmpty()) output += current.toString()
        return output
    }
}
