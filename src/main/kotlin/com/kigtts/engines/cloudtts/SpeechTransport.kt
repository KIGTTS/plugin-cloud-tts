package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.EngineError
import com.kigtts.plugin.api.EngineException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SpeechHttpRequest(val url: String, val body: ByteArray? = null,
    val contentType: String = "application/json; charset=utf-8", val headers: Map<String, String> = emptyMap())
internal data class SpeechHttpResponse(val status: Int, val body: ByteArray, val contentType: String = "") {
    fun text() = body.toString(Charsets.UTF_8)
}
internal data class SpeechFrame(val text: String, val delayAfterMillis: Long = 0)

/** Kept injectable so protocol tests never require cloud credentials. */
internal interface SpeechTransport {
    suspend fun http(request: SpeechHttpRequest): SpeechHttpResponse
    suspend fun socket(url: String, frames: List<SpeechFrame>, onMessage: (String) -> Boolean)
    fun close() {}
}

internal class OkSpeechTransport : SpeechTransport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS).callTimeout(80, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun http(request: SpeechHttpRequest): SpeechHttpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        request.body?.let { builder.post(it.toRequestBody(request.contentType.toMediaType())) }
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(networkFailure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { SpeechHttpResponse(it.code, it.body?.byteStream()?.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            if (output.size() + count > MAX_RESPONSE_BYTES) throw EngineException(EngineError.INFERENCE, "云端响应过大")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: byteArrayOf(), it.header("Content-Type").orEmpty()) }
                }
                if (continuation.isActive) result.fold(continuation::resume) {
                    continuation.resumeWithException(if (it is IOException) networkFailure(it) else it)
                }
            }
        })
    }

    override suspend fun socket(url: String, frames: List<SpeechFrame>, onMessage: (String) -> Boolean) = coroutineScope {
        withTimeout(80_000) {
            val opened = CompletableDeferred<Unit>()
            val incoming = Channel<String>(64)
            val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > MAX_RESPONSE_BYTES || incoming.trySend(text).isFailure) {
                        incoming.close(EngineException(EngineError.INFERENCE, "云端响应过快或过大")); webSocket.cancel()
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); incoming.close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    response?.close()
                    val error = EngineException(EngineError.UNAVAILABLE, "云端连接失败，请检查网络、密钥和服务授权")
                    opened.completeExceptionally(error); incoming.close(error)
                }
            })
            var sender: Job? = null
            try {
                opened.await()
                sender = launch {
                    frames.forEach { frame ->
                        ensureActive()
                        if (!socket.send(frame.text)) throw EngineException(EngineError.UNAVAILABLE, "云端连接已关闭")
                        if (frame.delayAfterMillis > 0) delay(frame.delayAfterMillis)
                    }
                }
                var finished = false
                for (message in incoming) if (onMessage(message)) { finished = true; break }
                if (!finished) throw EngineException(EngineError.INFERENCE, "云端连接中断，未收到完整结果")
            } finally {
                sender?.cancelAndJoin(); incoming.cancel(); socket.cancel()
            }
        }
    }
    override fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    private fun networkFailure(error: IOException) = EngineException(
        if (error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException) EngineError.TIMEOUT else EngineError.UNAVAILABLE,
        if (error is java.io.InterruptedIOException) "云端请求超时，请重试" else "无法连接云端服务，请检查网络")
    companion object { const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024 }
}
