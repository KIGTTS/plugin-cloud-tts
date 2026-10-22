package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ServiceSynthesisTest {
    @get:Rule val files = TemporaryFolder()
    private val pcm = SynthesisEvent.Audio(PcmAudio(byteArrayOf(1, 0)))
    private class Broker : SpeechBroker {
        var catalog = listOf(TtsProvider("test.provider", "test", TtsInfo(maxTextLength = 3)))
        var catalogError = false
        val requests = mutableListOf<TtsRequest>()
        var output: suspend FlowCollector<SynthesisEvent>.(TtsRequest) -> Unit = { emit(SynthesisEvent.Audio(PcmAudio(byteArrayOf(2, 0)))) }
        override fun providers(): List<TtsProvider> { if (catalogError) error("broker disconnected"); return catalog }
        override suspend fun command(pluginId: String, command: VoiceCommand) = PluginOutcome.RefreshUi
        override fun synthesize(pluginId: String, request: TtsRequest) = flow {
            requests += request; emit(SynthesisEvent.Started); output(request); emit(SynthesisEvent.Finished())
        }
    }
    private inner class System : TtsEngine {
        val requests = mutableListOf<TtsRequest>()
        override fun synthesize(request: TtsRequest) = flow {
            requests += request; emit(SynthesisEvent.Started); emit(pcm); emit(SynthesisEvent.Finished())
        }
    }
    private fun settings() = ServiceSettings(PluginStorage(files.newFolder())).also { it.provider = "test.provider"; it.voice = "chosen" }
    @Test fun splitsProviderRequestsWithoutSplittingSurrogatesAndEmitsSingleLifecycle() = runBlocking {
        val broker = Broker(); val system = System()
        val options = settings().also { it.rate = 1.5f }
        val events = ServiceSynthesis(options, broker, system).synthesize(TtsRequest("ab😀cd", rate = 2f)).toList()
        assertEquals(listOf("ab", "😀c", "d"), broker.requests.map { it.text })
        assertTrue(broker.requests.all { it.voiceId == "chosen" && it.rate == 3f })
        assertEquals(1, events.count { it is SynthesisEvent.Started })
        assertEquals(1, events.count { it is SynthesisEvent.Finished })
        assertTrue(system.requests.isEmpty())
    }
    @Test fun providerMissingUnconfiguredOrBrokerDisconnectedUsesBuiltInSystem() = runBlocking {
        for (state in 0..2) {
            val broker = Broker().also { when (state) {
                0 -> it.catalog = emptyList()
                1 -> it.catalog = listOf(TtsProvider("test.provider", "test", TtsInfo(EngineAvailability.NEEDS_RESOURCES)))
                else -> it.catalogError = true
            } }
            val system = System()
            val options = settings().also { it.systemVoice = "system.voice" }
            ServiceSynthesis(options, broker, system).synthesize(TtsRequest("你好")).toList()
            assertNull(system.requests.single().voiceId)
            assertTrue(broker.requests.isEmpty())
        }
    }
    @Test fun failureBeforeAudioFallsBackOnlyForRemainingText() = runBlocking {
        val broker = Broker().also { it.output = { if (it.text == "def") error("inference failed") else emit(pcm) } }
        val system = System()
        val events = ServiceSynthesis(settings(), broker, system).synthesize(TtsRequest("abcdefghi")).toList()
        assertEquals(listOf("abc", "def"), broker.requests.map { it.text })
        assertEquals("defghi", system.requests.single().text)
        assertEquals(1, events.count { it is SynthesisEvent.Started })
        assertEquals(1, events.count { it is SynthesisEvent.Finished })
    }
    @Test fun failureAfterAudioNeverReplaysFromSystem() = runBlocking {
        val broker = Broker().also { it.output = { emit(pcm); error("failure after audio") } }; val system = System()
        val result = runCatching { ServiceSynthesis(settings(), broker, system).synthesize(TtsRequest("abc")).toList() }
        assertTrue(result.isFailure)
        assertTrue(system.requests.isEmpty())
    }
    @Test fun silentProviderFallsBack() = runBlocking {
        val broker = Broker().also { it.output = {} }; val system = System()
        ServiceSynthesis(settings(), broker, system).synthesize(TtsRequest("abc")).toList()
        assertEquals("abc", system.requests.single().text)
    }
    @Test fun cancellationReachesProviderAndDoesNotTriggerFallback() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val broker = Broker().also { it.output = { entered.complete(Unit); try { awaitCancellation() } finally { released.complete(Unit) } } }
        val system = System()
        val task = launch { ServiceSynthesis(settings(), broker, system).synthesize(TtsRequest("abc")).collect() }
        withTimeout(5000) { entered.await() }; task.cancelAndJoin()
        assertTrue(released.isCompleted)
        assertTrue(system.requests.isEmpty())
    }
    @Test fun settingsPersistEngineVoiceAndSystemFallbackConfiguration() {
        val storage = PluginStorage(files.newFolder())
        ServiceSettings(storage).apply { provider = "test.provider"; voice = "v"; systemVoice = "s"; rate = 1.5f; language = "en-US"; save() }
        val restored = ServiceSettings(storage)
        assertEquals("test.provider", restored.provider); assertEquals("v", restored.voice)
        assertEquals("s", restored.systemVoice); assertEquals("en-US", restored.language); assertEquals(1.5f, restored.rate)
    }
}
