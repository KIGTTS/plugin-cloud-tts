package com.kigtts.engines.speechservice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import org.json.JSONObject
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
class BuiltinSystemMigrationTest {
    @get:Rule val files = TemporaryFolder()
    private val pcm = SynthesisEvent.Audio(PcmAudio(byteArrayOf(1, 0)))
    @Test fun retiredSavedProviderMovesToBuiltInWhileVoiceLanguageRateResidenceAndOtherDataStay() {
        for (legacy in listOf(LEGACY_SYSTEM_PROVIDER, SYSTEM_PROVIDER)) {
            val storage = PluginStorage(files.newFolder())
            val model = storage.file("models/user-package.bin").also { it.parentFile!!.mkdirs(); it.writeText("keep") }
            storage.file("config/service.json").also { it.parentFile!!.mkdirs() }.writeText(JSONObject()
                .put("provider", legacy).put("voice", "android.voice").put("systemLanguage", "en-US")
                .put("rate", 1.5f).put("resident", true).toString())
            val settings = ServiceSettings(storage)
            assertNull(settings.provider); assertNull(settings.voice); assertEquals("android.voice", settings.systemVoice)
            assertEquals("en-US", settings.language); assertEquals(1.5f, settings.rate); assertTrue(settings.resident)
            assertEquals("keep", model.readText())
            ServiceSettings(storage).let { assertNull(it.provider); assertEquals("android.voice", it.systemVoice) }
        }
    }
    @Test fun currentProviderAndExplicitFallbackVoiceArePreserved() {
        val storage = PluginStorage(files.newFolder())
        ServiceSettings(storage).apply { provider = "engine.piper"; voice = "piper.voice"; systemVoice = "fallback.voice"; save() }
        val current = ServiceSettings(storage)
        assertEquals("engine.piper", current.provider); assertEquals("piper.voice", current.voice); assertEquals("fallback.voice", current.systemVoice)
        storage.file("config/service.json").writeText(JSONObject().put("provider", LEGACY_SYSTEM_PROVIDER)
            .put("voice", "old.voice").put("systemVoice", "explicit.voice").toString())
        assertEquals("explicit.voice", ServiceSettings(storage).systemVoice)
    }
    @Test fun legacyFilterMovesToSystemCategoryWithoutDeletingLibraryOrderOrPins() {
        val storage = PluginStorage(files.newFolder())
        storage.file("config/voice-library.json").also { it.parentFile!!.mkdirs() }.writeText(JSONObject()
            .put("filter", LEGACY_SYSTEM_PROVIDER).put("order", org.json.JSONArray(listOf("engine.piper/model")))
            .put("pins", JSONObject().put("engine.piper/model", true)).toString())
        val library = VoiceLibrary(storage)
        assertEquals(SYSTEM_PROVIDER, library.filter); assertTrue(library.pinned("engine.piper/model", false))
        val saved = JSONObject(storage.file("config/voice-library.json").readText())
        assertEquals("engine.piper/model", saved.getJSONArray("order").getString(0))
        assertTrue(saved.getJSONObject("pins").getBoolean("engine.piper/model"))
        assertEquals(SYSTEM_PROVIDER, saved.getString("filter"))
    }
    @Test fun evenAnEnabledRetiredProviderCannotReceiveControlOrAudioRequests() = runBlocking {
        val options = ServiceSettings(PluginStorage(files.newFolder())).apply { provider = LEGACY_SYSTEM_PROVIDER; systemVoice = "fallback.voice"; resident = true }
        var commands = 0
        val broker = object : SpeechBroker {
            override fun providers() = listOf(TtsProvider(LEGACY_SYSTEM_PROVIDER, "Old system", TtsInfo(supportsControl = true)))
            override suspend fun command(pluginId: String, command: VoiceCommand): PluginOutcome { commands++; return PluginOutcome.RefreshUi }
            override suspend fun control(pluginId: String, command: TtsControl) { commands++ }
            override fun synthesize(pluginId: String, request: TtsRequest) = flow<SynthesisEvent> { commands++; error("retired provider called") }
        }
        val used = mutableListOf<TtsRequest>(); var closed = 0
        val system = object : TtsEngine {
            override fun synthesize(request: TtsRequest) = flow { used += request; emit(SynthesisEvent.Started); emit(pcm); emit(SynthesisEvent.Finished()) }
            override suspend fun close() { closed++ }
        }
        val synthesis = ServiceSynthesis(options, broker, system)
        synthesis.control(TtsControl(TtsControlOperation.PREPARE))
        val events = synthesis.synthesize(TtsRequest("hello")).toList()
        assertEquals(0, commands); assertEquals("fallback.voice", used.single().voiceId)
        assertEquals(1, events.count { it is SynthesisEvent.Started }); assertEquals(1, events.count { it is SynthesisEvent.Finished })
        synthesis.close(); assertEquals(1, closed)
    }
    @Test fun serviceNativeSettingsExposeOneBuiltInSystemPackAndHideRetiredPlugin() = runBlocking {
        val broker = object : SpeechBroker {
            override fun providers() = listOf(TtsProvider(LEGACY_SYSTEM_PROVIDER, "Old", TtsInfo(voices = listOf(VoiceInfo("old", "old", "zh-CN")))))
            override suspend fun command(pluginId: String, command: VoiceCommand) = PluginOutcome.RefreshUi
            override fun synthesize(pluginId: String, request: TtsRequest) = emptyFlow<SynthesisEvent>()
        }
        val environment = object : AndroidPluginEnvironment {
            override val context = ApplicationProvider.getApplicationContext<Context>()
            override val storage = PluginStorage(files.newFolder())
            override val assets = files.newFolder()
            override val speechBroker = broker
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val service = SpeechServicePlugin(); service.attach(environment)
        val page = service.page("packs", PluginContext("", ""))!!
        assertEquals(listOf("系统声音"), page.management!!.items.map { it.title })
        assertFalse(page.management!!.filters.any { it.id == LEGACY_SYSTEM_PROVIDER })
        assertEquals(listOf("manager", "packs"), service.contributions.settingsEntries.map { it.id })
        service.close()
    }
}
