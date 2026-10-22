package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ServiceRoutingTest {
    @get:Rule val files = TemporaryFolder()
    private class Broker : SpeechBroker {
        val controls = mutableListOf<Pair<String, TtsControl>>()
        var generation = "1"
        override fun providers() = listOf("piper", "other").map { TtsProvider(it, it, TtsInfo(supportsControl = true), generation = generation) }
        override suspend fun command(pluginId: String, command: VoiceCommand) = PluginOutcome.RefreshUi
        override fun synthesize(pluginId: String, request: TtsRequest) = emptyFlow<SynthesisEvent>()
        override suspend fun control(pluginId: String, command: TtsControl) { controls += pluginId to command }
    }
    private class System : TtsEngine {
        val controls = mutableListOf<TtsControl>()
        override fun synthesize(request: TtsRequest) = emptyFlow<SynthesisEvent>()
        override suspend fun control(command: TtsControl) { controls += command }
    }
    @Test fun selectingAnotherEngineReleasesOldEvenWhenResidentIsEnabled() = runBlocking {
        val settings = ServiceSettings(PluginStorage(files.newFolder())).apply { provider = "piper"; voice = "v1"; resident = true }
        val broker = Broker(); val system = System(); val routing = ServiceRouting(settings, broker, system)
        routing.control(TtsControl(TtsControlOperation.PREPARE))
        assertEquals(TtsControlOperation.SET_RESIDENT, broker.controls[0].second.operation)
        assertTrue(broker.controls[0].second.resident)
        assertEquals("v1", broker.controls[1].second.voiceId)
        settings.provider = "other"; settings.voice = "v2"
        routing.begin()
        assertEquals(TtsControlOperation.RELEASE, broker.controls[2].second.operation)
        assertEquals("piper", broker.controls[2].first)
        assertEquals("other", broker.controls[3].first)
        assertEquals(TtsControlOperation.SET_RESIDENT, broker.controls[3].second.operation)
        settings.provider = null
        routing.begin()
        assertEquals("other", broker.controls.last().first)
        assertEquals(TtsControlOperation.RELEASE, broker.controls.last().second.operation)
        assertTrue(system.controls.last().resident)
        routing.close()
    }
    @Test fun voiceSwitchPreparesNewPackAndSameRouteRefreshDoesNotRewarmOrResetIdle() = runBlocking {
        val settings = ServiceSettings(PluginStorage(files.newFolder())).apply { provider = "piper"; voice = "v1" }
        val broker = Broker(); val routing = ServiceRouting(settings, broker, System())
        routing.begin()
        settings.voice = "v2"
        routing.begin()
        assertEquals(listOf(TtsControlOperation.SET_RESIDENT, TtsControlOperation.RELEASE,
            TtsControlOperation.SET_RESIDENT, TtsControlOperation.PREPARE), broker.controls.map { it.second.operation })
        assertEquals("v2", broker.controls.last().second.voiceId)
        val before = broker.controls.size
        repeat(5) { routing.begin() }
        assertEquals(before, broker.controls.size)
        settings.resident = true; routing.begin()
        assertTrue(broker.controls[broker.controls.lastIndex - 1].second.resident)
        assertEquals(TtsControlOperation.PREPARE, broker.controls.last().second.operation)
        routing.close()
    }
    @Test fun residentPreferencePersists() {
        val storage = PluginStorage(files.newFolder())
        assertFalse(ServiceSettings(storage).resident)
        ServiceSettings(storage).apply { resident = true; save() }
        assertTrue(ServiceSettings(storage).resident)
    }
    @Test fun restartedProviderReceivesResidentPolicyAndPreparationAgain() = runBlocking {
        val settings = ServiceSettings(PluginStorage(files.newFolder())).apply { provider = "piper"; resident = true }
        val broker = Broker(); val routing = ServiceRouting(settings, broker, System())
        routing.control(TtsControl(TtsControlOperation.PREPARE)); broker.controls.clear()
        broker.generation = "2"; routing.begin()
        assertEquals(listOf(TtsControlOperation.RELEASE, TtsControlOperation.SET_RESIDENT, TtsControlOperation.PREPARE), broker.controls.map { it.second.operation })
        assertTrue(broker.controls[1].second.resident)
        routing.close()
    }
    @Test fun automaticReadPreparesNewProviderOnRoutingChangeWithoutRewarmingEveryUpdate() = runBlocking {
        val settings = ServiceSettings(PluginStorage(files.newFolder())).apply { provider = "piper" }
        val broker = Broker(); val routing = ServiceRouting(settings, broker, System())
        routing.control(TtsControl(TtsControlOperation.PREPARE))
        routing.changed(true)
        settings.provider = "other"; routing.begin()
        assertEquals("other", broker.controls.last().first)
        assertEquals(TtsControlOperation.PREPARE, broker.controls.last().second.operation)
        val count = broker.controls.size
        repeat(3) { routing.begin() }
        assertEquals(count, broker.controls.size)
        routing.close()
    }
}
