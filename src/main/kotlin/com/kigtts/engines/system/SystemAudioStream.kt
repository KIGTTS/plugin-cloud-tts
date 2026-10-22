// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.system

import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Callback audio is emitted immediately; older engines fall back to a short segment's WAV. */
internal fun systemAudioStream(environment: AndroidPluginEnvironment, engine: TextToSpeech, text: String) = flow {
    val token = UUID.randomUUID().toString()
    val file = File(environment.storage.temporary, "system-tts-$token.wav")
    val chunks = Channel<PcmAudio>(Channel.UNLIMITED)
    val lock = Any()
    var format: com.kigtts.plugin.api.AudioFormat? = null
    var encoding = 0
    var pending = byteArrayOf()
    var received = 0L
    val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onBeginSynthesis(id: String?, rate: Int, audioFormat: Int, channels: Int) = synchronized(lock) {
            if (id == token && rate in 8_000..192_000 && channels in 1..2) {
                format = com.kigtts.plugin.api.AudioFormat(rate, channels); encoding = audioFormat
            }
        }
        override fun onAudioAvailable(id: String?, audio: ByteArray?) = synchronized(lock) {
            val current = format
            if (id != token || audio == null || current == null) return@synchronized
            val sampleBytes = when (encoding) { AudioFormat.ENCODING_PCM_8BIT -> 1; AudioFormat.ENCODING_PCM_16BIT -> 2; AudioFormat.ENCODING_PCM_FLOAT -> 4; else -> return@synchronized }
            received += audio.size
            if (received > 16L * 1024 * 1024) { chunks.close(EngineException(EngineError.INFERENCE, "系统 TTS 音频过大")); return@synchronized }
            val source = pending + audio
            val length = source.size / (sampleBytes * current.channels) * sampleBytes * current.channels
            pending = source.copyOfRange(length, source.size)
            if (length > 0) {
                val pcm = when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> source.copyOf(length)
                    else -> ByteArray(length / sampleBytes * 2).also { target ->
                        val floats = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
                        for (index in 0 until length / sampleBytes) {
                            val value = if (sampleBytes == 1) (((source[index].toInt() and 255) - 128) shl 8)
                                else (floats.getFloat(index * 4).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f } * 32767).toInt()
                            target[index * 2] = value.toByte(); target[index * 2 + 1] = (value shr 8).toByte()
                        }
                    }
                }
                chunks.trySend(PcmAudio(pcm, current.sampleRate, current.channels))
            }
        }
        override fun onDone(id: String?) = synchronized(lock) {
            if (id == token) chunks.close(if (pending.isEmpty()) null else EngineException(EngineError.INFERENCE, "系统 TTS 音频不完整"))
        }
        @Deprecated("Platform callback") override fun onError(id: String?) { if (id == token) chunks.close(EngineException(EngineError.INFERENCE, "系统 TTS 合成失败")) }
        override fun onError(id: String?, code: Int) { onError(id) }
        override fun onStop(id: String?, interrupted: Boolean) { if (id == token) chunks.close(EngineException(EngineError.INFERENCE, "系统 TTS 合成已停止")) }
    }
    var streamed = false
    try {
        engine.setOnUtteranceProgressListener(listener)
        if (engine.synthesizeToFile(text, Bundle(), file, token) != TextToSpeech.SUCCESS)
            throw EngineException(EngineError.INFERENCE, "系统 TTS 拒绝合成请求")
        try { withTimeout(30_000) { for (audio in chunks) { streamed = true; emit(audio) } } }
        catch (timeout: TimeoutCancellationException) { throw EngineException(EngineError.TIMEOUT, "系统 TTS 合成超时", timeout) }
        if (!streamed) {
            val audio = WavPcm.read(file)
            val stride = 2 * audio.channels
            val size = 32_768 / stride * stride
            for (offset in audio.bytes.indices step size) {
                currentCoroutineContext().ensureActive()
                emit(PcmAudio(audio.bytes.copyOfRange(offset, minOf(offset + size, audio.bytes.size)), audio.sampleRate, audio.channels))
            }
        }
    } finally {
        chunks.cancel()
        withContext(NonCancellable + Dispatchers.Main) { engine.stop(); engine.setOnUtteranceProgressListener(null) }
        file.delete()
    }
}
