// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Uniform facade owns routing and fallback, while providers own inference and voice files. */
internal class ServiceSynthesis(private val settings: ServiceSettings, private val broker: SpeechBroker,
    private val system: TtsEngine) : TtsEngine {
    private val routing = ServiceRouting(settings, broker, system)
    override val info get() = TtsInfo(role = TtsRole.SERVICE, selectedVoiceId = if (settings.provider == null) settings.systemVoice else settings.voice, supportsControl = true)
    fun settingsChanged(automaticRead: Boolean? = null) = routing.changed(automaticRead)
    override suspend fun control(command: TtsControl) { routing.control(command) }
    override suspend fun close() = routing.close()
    override fun synthesize(request: TtsRequest) = flow {
        require(request.text.length <= info.maxTextLength) { "单次朗读文本过长" }
        val provider = routing.begin()
        if (provider == null) {
            routing.fallback()
            system.synthesize(request.copy(voiceId = settings.systemVoice, rate = (request.rate * settings.rate).coerceIn(.25f, 4f))).collect { emit(it) }
            return@flow
        }
        emit(SynthesisEvent.Started)
        var offset = 0
        val limit = provider.info.maxTextLength
        while (offset < request.text.length) {
            var end = minOf(offset + limit, request.text.length)
            if (end < request.text.length && request.text[end - 1].isHighSurrogate()) end--
            var audioSent = false
            try {
                broker.synthesize(provider.pluginId, request.copy(text = request.text.substring(offset, end),
                    voiceId = settings.voice, rate = (request.rate * settings.rate).coerceIn(.25f, 4f))).collect { event ->
                    if (event is SynthesisEvent.Audio && event.audio.bytes.isNotEmpty()) { audioSent = true; emit(event) }
                }
                if (!audioSent && request.text.substring(offset, end).isNotBlank())
                    throw EngineException(EngineError.INFERENCE, "引擎未输出音频")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (audioSent) throw error
                routing.fallback()
                system.synthesize(request.copy(text = request.text.substring(offset),
                    voiceId = settings.systemVoice,
                    rate = (request.rate * settings.rate).coerceIn(.25f, 4f))).collect { if (it != SynthesisEvent.Started) emit(it) }
                return@flow
            }
            offset = end
        }
        emit(SynthesisEvent.Finished())
    }.flowOn(Dispatchers.IO)
}
