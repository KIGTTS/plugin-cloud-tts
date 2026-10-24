package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.EngineError
import com.kigtts.plugin.api.EngineException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class SpeechTokens(private val transport: SpeechTransport, private val clock: () -> Instant = Instant::now,
    private val nonce: () -> String = { UUID.randomUUID().toString() }) {
    private data class Token(val value: String, val expires: Long)
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Token>()

    suspend fun aliyun(key: String, secret: String, endpoint: String): String = token("ali", key, secret, endpoint) {
        val international = endpoint.contains("ap-southeast-1")
        val region = if (international) "ap-southeast-1" else "cn-shanghai"
        val host = if (international) "nlsmeta.ap-southeast-1.aliyuncs.com" else "nls-meta.cn-shanghai.aliyuncs.com"
        val query = SpeechSigning.aliyun(mapOf("AccessKeyId" to key, "Action" to "CreateToken", "Format" to "JSON",
            "RegionId" to region, "SignatureMethod" to "HMAC-SHA1", "SignatureNonce" to nonce(), "SignatureVersion" to "1.0",
            "Timestamp" to clock().truncatedTo(ChronoUnit.SECONDS).toString(), "Version" to "2019-02-28"), secret)
        val response = speechJson(transport.http(SpeechHttpRequest("https://$host/?$query")), listOf(key, secret))
        val result = response.optJSONObject("Token")
            ?: throw speechFailure(response.optString("Code"), response.optString("Message"), listOf(key, secret))
        val value = result.optString("Id")
        if (value.isBlank()) throw EngineException(EngineError.INFERENCE, "阿里云未返回有效 Token")
        Token(value, result.optLong("ExpireTime", clock().epochSecond + 300))
    }

    suspend fun baidu(key: String, secret: String): String = token("baidu", key, secret, "") {
        val body = SpeechSigning.query(mapOf("grant_type" to "client_credentials", "client_id" to key, "client_secret" to secret))
        val response = speechJson(transport.http(SpeechHttpRequest("https://aip.baidubce.com/oauth/2.0/token", body.toByteArray(), "application/x-www-form-urlencoded")), listOf(key, secret))
        val value = response.optString("access_token")
        if (value.isBlank()) throw speechFailure(response.optString("error"), response.optString("error_description"), listOf(key, secret))
        Token(value, clock().epochSecond + response.optLong("expires_in", 300))
    }
    private suspend fun token(provider: String, key: String, secret: String, endpoint: String, fetch: suspend () -> Token): String = mutex.withLock {
        if (key.isBlank() || secret.isBlank()) throw EngineException(EngineError.NOT_CONFIGURED, "请填写服务密钥和密钥 Secret")
        val cacheKey = SpeechSigning.digest("$provider\u0000$key\u0000$secret\u0000$endpoint".toByteArray())
        cache.entries.removeAll { it.value.expires <= clock().epochSecond + 60 }
        cache[cacheKey]?.let { return@withLock it.value }
        val result = fetch()
        if (cache.size >= 8) cache.clear()
        cache[cacheKey] = result
        result.value
    }
    suspend fun clear() = mutex.withLock { cache.clear() }
}

internal fun speechFailure(code: String, message: String, secrets: List<String> = emptyList()): EngineException {
    var safe = message.replace(Regex("https?://\\S+"), "[接口地址]").replace(Regex("[\\r\\n]+"), " ").take(200)
    secrets.filter { it.isNotEmpty() }.forEach { safe = safe.replace(it, "***") }
    val safeCode = code.replace(Regex("[^a-zA-Z0-9_.-]"), "").take(64)
    return EngineException(EngineError.INFERENCE, "云端服务${safeCode.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}：${safe.ifBlank { "请求未成功，请检查配置和服务授权" }}")
}

internal fun speechJson(response: SpeechHttpResponse, secrets: List<String> = emptyList()): JSONObject {
    val json = if (response.body.size <= OkSpeechTransport.MAX_RESPONSE_BYTES) runCatching { JSONObject(response.text()) }.getOrNull() else null
    if (response.status !in 200..299) {
        val detail = json?.optString("message")?.ifBlank { json.optString("Message") }?.ifBlank { json.optString("error_description") }
        throw speechFailure("HTTP ${response.status}", detail.orEmpty().ifBlank { "请求失败，请检查网络、密钥和服务授权" }, secrets)
    }
    return json ?: throw EngineException(EngineError.INFERENCE, "云端服务返回了无效结果")
}
