package com.kigtts.engines.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kigtts.plugin.api.*
import com.kigtts.plugin.android.AndroidPluginEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SystemSpeechEngineTest {
    @get:Rule val files = TemporaryFolder()
    private fun environment() = object : AndroidPluginEnvironment {
        override val context = ApplicationProvider.getApplicationContext<Context>()
        override val storage = PluginStorage(files.newFolder())
        override val assets = files.newFolder()
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    private class Connection : SystemTtsConnection {
        val requests = mutableListOf<TtsRequest>()
        var closed = 0
        var gate: CompletableDeferred<Unit>? = null
        override fun voices() = listOf(VoiceInfo("v", "voice", "zh-CN"))
        override fun synthesize(request: TtsRequest) = flow {
            requests += request
            emit(PcmAudio(byteArrayOf(1, 0)))
            gate?.await()
        }
        override suspend fun close() { closed++ }
    }
    @Test fun prepareAndVoiceRefreshReuseConnectionUntilExplicitRelease() = runBlocking {
        val connections = mutableListOf<Connection>()
        val engine = SystemSpeechEngine(environment(), { 1.5f }, { Connection().also { connections += it } })
        engine.control(TtsControl(TtsControlOperation.PREPARE))
        assertTrue(engine.refreshVoices().isEmpty())
        engine.synthesize(TtsRequest("hello", rate = 2f, voiceId = "old-system-voice")).toList()
        engine.synthesize(TtsRequest("again")).toList()
        assertEquals(1, connections.size); assertEquals(3f, connections.single().requests.first().rate)
        assertNull(connections.single().requests.first().voiceId)
        engine.control(TtsControl(TtsControlOperation.RELEASE)); assertEquals(1, connections.single().closed)
        engine.synthesize(TtsRequest("next")).toList(); assertEquals(2, connections.size)
        engine.close(); assertEquals(1, connections.last().closed)
    }
    @Test fun firstAudioStreamsBeforeRequestFinishesAndCancelKeepsConnectionReusable() = runBlocking {
        val connection = Connection().also { it.gate = CompletableDeferred() }; var initialized = 0
        val engine = SystemSpeechEngine(environment(), { 1f }, { initialized++; connection })
        val first = CompletableDeferred<Unit>()
        val job = launch { engine.synthesize(TtsRequest("first. " + "x".repeat(200))).collect {
            if (it is SynthesisEvent.Audio) first.complete(Unit)
        } }
        withTimeout(5000) { first.await() }; assertFalse(job.isCompleted)
        job.cancelAndJoin(); connection.gate?.complete(Unit)
        val text = "first. " + "x".repeat(200)
        val events = engine.synthesize(TtsRequest(text)).toList()
        assertEquals(1, initialized); assertEquals(0, connection.closed)
        assertEquals(text, connection.requests.drop(1).joinToString("") { it.text })
        assertEquals(1, events.count { it is SynthesisEvent.Started }); assertEquals(1, events.count { it is SynthesisEvent.Finished })
        engine.close()
    }
}
