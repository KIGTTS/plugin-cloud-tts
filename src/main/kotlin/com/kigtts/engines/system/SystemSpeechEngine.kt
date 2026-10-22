// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.system

import android.os.SystemClock
import android.util.Log
import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.kigtts.plugin.api.*
import com.kigtts.engines.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The platform connection, PCM callbacks and idle policy stay inside the engine package. */
class SystemSpeechEngine(private val environment: AndroidPluginEnvironment,
    private val rate: () -> Float = { 1f },
    private val factory: suspend () -> SystemTtsConnection = { AndroidSystemTtsConnection.open(environment) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    idleMillis: Long = 120_000) : TtsEngine {
    private val connection = ResidentResource<Unit, SystemTtsConnection>(scope, { it.close() }, idleMillis)
    override val info get() = TtsInfo(supportsControl = true)
    suspend fun refreshVoices(): List<VoiceInfo> = emptyList()
    override suspend fun control(command: TtsControl) {
        when (command.operation) {
            TtsControlOperation.PREPARE -> connection.use(Unit, factory) { }
            TtsControlOperation.RELEASE -> connection.release()
            TtsControlOperation.SET_RESIDENT -> connection.setResident(command.resident)
            TtsControlOperation.ACTIVITY -> connection.setActive(command.active)
        }
    }
    override fun synthesize(request: TtsRequest) = flow {
        emit(SynthesisEvent.Started)
        if (request.text.isBlank()) { emit(SynthesisEvent.Finished()); return@flow }
        require(request.text.length <= info.maxTextLength) { "单次朗读文本过长" }
        val start = SystemClock.elapsedRealtime()
        var first = true
        connection.use(Unit, factory) { engine ->
            for (segment in speechSegments(request.text)) {
                currentCoroutineContext().ensureActive()
                if (segment.isBlank()) continue
                engine.synthesize(request.copy(text = segment, voiceId = null,
                    rate = (rate() * request.rate).coerceIn(.25f, 4f))).collect { audio ->
                    if (first) { Log.i("SystemTtsPerf", "first audio ms=${SystemClock.elapsedRealtime() - start}"); first = false }
                    emit(SynthesisEvent.Audio(audio))
                }
            }
        }
        Log.i("SystemTtsPerf", "finished ms=${SystemClock.elapsedRealtime() - start}")
        emit(SynthesisEvent.Finished())
    }.flowOn(Dispatchers.IO)
    override suspend fun close() { connection.close(); scope.cancel() }
}
