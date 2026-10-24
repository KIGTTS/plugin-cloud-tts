package com.kigtts.engines.cloudtts

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object SpeechSigning {
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    fun query(values: Map<String, String>) = values.entries.sortedBy { it.key }.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hmac(key: ByteArray, value: String, algorithm: String = "HmacSHA256"): ByteArray = Mac.getInstance(algorithm).run {
        init(SecretKeySpec(key, algorithm)); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    fun tencent(endpoint: String, service: String, action: String, version: String, key: String, secret: String,
        body: ByteArray, now: Instant): Map<String, String> {
        val uri = URI(endpoint)
        require(uri.scheme == "https" && (uri.path.isNullOrEmpty() || uri.path == "/") && uri.rawQuery == null) { "腾讯云接口地址应为 HTTPS 服务域名" }
        val contentType = "application/json; charset=utf-8"
        val canonical = "POST\n/\n\ncontent-type:$contentType\nhost:${uri.host}\n\ncontent-type;host\n${digest(body)}"
        val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now)
        val scope = "$date/$service/tc3_request"
        val toSign = "TC3-HMAC-SHA256\n${now.epochSecond}\n$scope\n${digest(canonical.toByteArray())}"
        val dateKey = hmac("TC3$secret".toByteArray(), date)
        val serviceKey = hmac(dateKey, service)
        val signingKey = hmac(serviceKey, "tc3_request")
        val signature = hmac(signingKey, toSign).joinToString("") { "%02x".format(it) }
        return mapOf("Content-Type" to contentType, "X-TC-Action" to action, "X-TC-Version" to version,
            "X-TC-Timestamp" to now.epochSecond.toString(), "Authorization" to "TC3-HMAC-SHA256 Credential=$key/$scope, SignedHeaders=content-type;host, Signature=$signature")
    }
    fun aliyun(parameters: Map<String, String>, secret: String): String {
        val signature = Base64.getEncoder().encodeToString(hmac("$secret&".toByteArray(), "GET&%2F&${encode(query(parameters))}", "HmacSHA1"))
        return query(parameters + ("Signature" to signature))
    }
    fun xfyun(endpoint: String, key: String, secret: String, now: Instant): String {
        val uri = URI(endpoint)
        require(uri.scheme == "https" || uri.scheme == "wss") { "讯飞接口地址必须使用 HTTPS 或 WSS" }
        require(uri.rawQuery == null) { "讯飞接口地址不应包含鉴权参数" }
        val date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC).format(now)
        val path = uri.rawPath.ifBlank { "/" }
        val signed = "host: ${uri.host}\ndate: $date\nGET $path HTTP/1.1"
        val signature = Base64.getEncoder().encodeToString(hmac(secret.toByteArray(), signed))
        val auth = "api_key=\"$key\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        return "wss://${uri.rawAuthority}$path?" + query(mapOf("authorization" to Base64.getEncoder().encodeToString(auth.toByteArray()), "date" to date, "host" to uri.host))
    }
}
