// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.system

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.util.Locale

interface SystemTtsConnection {
    fun voices(): List<VoiceInfo>
    fun synthesize(request: TtsRequest): Flow<PcmAudio>
    suspend fun close()
}
internal class AndroidSystemTtsConnection(private val environment: AndroidPluginEnvironment,
    private val engine: TextToSpeech) : SystemTtsConnection {
    override fun voices() = engine.voices.orEmpty().sortedBy { it.name }.take(500).map {
        VoiceInfo(it.name, it.name, it.locale.toLanguageTag(), if (it.isNetworkConnectionRequired) "需要网络" else "本地声音")
    }
    override fun synthesize(request: TtsRequest): Flow<PcmAudio> {
        if (engine.setLanguage(Locale.forLanguageTag(request.language)) < 0)
            throw EngineException(EngineError.NOT_CONFIGURED, "系统 TTS 未安装 ${request.language} 的语音数据")
        request.voiceId?.let { id -> engine.voices.firstOrNull { it.name == id }?.let { engine.voice = it } }
        engine.setSpeechRate(request.rate)
        return systemAudioStream(environment, engine, request.text)
    }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.Main) {
        engine.stop(); engine.shutdown(); Log.i("SystemTtsPerf", "connection released"); Unit
    }
    companion object {
        suspend fun open(environment: AndroidPluginEnvironment): SystemTtsConnection {
            @Suppress("DEPRECATION")
            val packages = environment.context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }.distinct().take(10)
            for (name in listOf<String?>(null) + packages) {
                var candidate: TextToSpeech? = null
                try {
                    val initialized = CompletableDeferred<Int>()
                    withContext(Dispatchers.Main) { candidate = TextToSpeech(environment.context, { initialized.complete(it) }, name) }
                    if (withTimeout(7_000) { initialized.await() } == TextToSpeech.SUCCESS) {
                        Log.i("SystemTtsPerf", "connection loaded")
                        return AndroidSystemTtsConnection(environment, requireNotNull(candidate))
                    }
                } catch (_: TimeoutCancellationException) {
                } catch (cancel: CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main) { candidate?.shutdown() }; throw cancel
                } catch (_: Exception) { }
                withContext(NonCancellable + Dispatchers.Main) { candidate?.shutdown() }
            }
            throw EngineException(EngineError.INITIALIZATION, "没有可用的系统 TTS 服务，请先安装语音引擎")
        }
    }
}
