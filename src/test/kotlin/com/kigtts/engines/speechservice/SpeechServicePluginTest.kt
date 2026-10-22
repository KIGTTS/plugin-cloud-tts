package com.kigtts.engines.speechservice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kigtts.plugin.android.AndroidPluginEnvironment
import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SpeechServicePluginTest {
    @get:Rule val files = TemporaryFolder()
    private class Broker : SpeechBroker {
        var info = TtsInfo(voices = listOf(VoiceInfo("voice", "Voice · Speaker", "en-US", deletable = true, packName = "Voice")))
        val commands = mutableListOf<VoiceCommand>()
        var voice: String? = null
        override fun providers() = listOf(TtsProvider("test.provider", "Provider", info))
        override suspend fun command(pluginId: String, command: VoiceCommand): PluginOutcome { commands += command; return PluginOutcome.RefreshUi }
        override fun synthesize(pluginId: String, request: TtsRequest) = flowOf(SynthesisEvent.Started,
            SynthesisEvent.Audio(PcmAudio(byteArrayOf(2, 0))), SynthesisEvent.Finished()).also { voice = request.voiceId }
    }
    private fun environment(broker: Broker) = object : AndroidPluginEnvironment {
        override val context = ApplicationProvider.getApplicationContext<Context>()
        override val storage = PluginStorage(files.newFolder())
        override val assets = files.newFolder()
        override val speechBroker = broker
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
    }
    @Test fun providerWithoutVoiceManagerCanStillSelectAndSynthesizeItsVoice() = runBlocking {
        val broker = Broker(); val environment = environment(broker)
        val service = SpeechServicePlugin().apply { attach(environment) }
        assertEquals(TtsRole.SERVICE, service.tts.info.role)
        service.execute("voice/${encoded("test.provider")}/${encoded("voice")}", PluginContext("", ""))
        service.tts.synthesize(TtsRequest("hello")).toList()
        assertTrue(broker.commands.isEmpty()); assertEquals("voice", broker.voice)
        assertEquals("test.provider", ServiceSettings(environment.storage).provider)
        assertTrue(service.execute("activate", PluginContext("", "")) is PluginOutcome.SelectTtsService)
    }
    @Test fun deletionRequiresConfirmationAndImportedInboxIsCleaned() = runBlocking {
        val broker = Broker().apply { info = info.copy(managesVoices = true, importsVoicePacks = true) }
        val environment = environment(broker)
        val service = SpeechServicePlugin().apply { attach(environment) }
        val action = "${encoded("test.provider")}/${encoded("voice")}"
        assertTrue(service.execute("delete/$action", PluginContext("", "")) is PluginOutcome.Menu)
        assertEquals("Voice", (service.execute("rename/$action", PluginContext("", "")) as PluginOutcome.TextInput).initialValue)
        assertTrue(broker.commands.isEmpty())
        service.execute("deleted/$action", PluginContext("", ""))
        assertEquals(VoiceOperation.DELETE, broker.commands.single().operation)
        val path = "resources/inbox/voice.kigvpk"
        environment.storage.file(path).apply { parentFile!!.mkdirs(); writeText("voice") }
        service.execute("imported/${encoded("test.provider")}", PluginContext("", "", inputs = mapOf("resourcePath" to path)))
        assertEquals(VoiceOperation.IMPORT, broker.commands.last().operation)
        assertFalse(environment.storage.file(path).exists())
    }
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    @Test fun pickerPageNavigationDoesNotDecodePageNumberAsProviderAndSystemDefaultClearsVoice() = runBlocking {
        val broker = Broker(); val environment = environment(broker)
        ServiceSettings(environment.storage).apply { provider = "test.provider"; voice = "voice"; systemVoice = "old.system.voice"; save() }
        val service = SpeechServicePlugin().apply { attach(environment) }
        assertEquals(PluginOutcome.OpenPage("packs"), service.execute("sounds/1", PluginContext("", "")))
        service.execute("system-default", PluginContext("", ""))
        ServiceSettings(environment.storage).let { assertNull(it.provider); assertNull(it.voice); assertNull(it.systemVoice) }
        service.tts.close()
    }
    @Test fun rateUsesSliderAndPackSettingsAppearForTheSelectedVoiceAndInItsMenu() = runBlocking {
        val spec = VoiceParameter("noise_scale", "音色变化", .6f, 0f, 2f, .667f)
        val broker = Broker().apply { info = info.copy(packs = listOf(VoicePackInfo("pack", VoicePackMetadata("我的声音"),
            listOf("voice"), parameters = listOf(spec)))) }
        val environment = environment(broker)
        ServiceSettings(environment.storage).apply { provider = "test.provider"; voice = "voice"; save() }
        val service = SpeechServicePlugin().apply { attach(environment) }
        val manager = service.page("manager", PluginContext("", ""))!!.blocks.filterIsInstance<PageBlock.Group>()
        val parameters = manager.single { it.title == "我的声音" }
        val slider = parameters.children.filterIsInstance<PageBlock.Slider>().single().control
        assertEquals(.6f, slider.value); assertEquals(2f, slider.max)
        service.execute(slider.actionId, PluginContext("", "", inputs = mapOf("value" to "0.9")))
        assertEquals(VoiceOperation.UPDATE_PARAMETERS, broker.commands.single().operation)
        assertEquals("pack", broker.commands.single().packId)
        assertEquals(.9, org.json.JSONObject(broker.commands.single().value!!).getDouble("noise_scale"), .001)
        assertTrue(runCatching { service.execute(slider.actionId, PluginContext("", "", inputs = mapOf("value" to "9"))) }.isFailure)
        assertEquals(1, broker.commands.size)
        val rate = (service.execute("rate", PluginContext("", "")) as PluginOutcome.Controls).controls.single() as PluginControl.Slider
        assertEquals(.25f, rate.min); assertEquals(4f, rate.max)
        service.execute(rate.actionId, PluginContext("", "", inputs = mapOf("value" to "1.5")))
        assertEquals(1.5f, ServiceSettings(environment.storage).rate)
        val packPage = service.page("pack/${encoded("test.provider")}/${encoded("pack")}", PluginContext("", ""))!!
        val controls = service.execute(packPage.management!!.items.single().actions.single().id, PluginContext("", "")) as PluginOutcome.Controls
        assertEquals(slider.actionId, controls.controls.single().actionId)
        service.tts.close()
    }
}
