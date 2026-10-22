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
        assertEquals(PluginOutcome.OpenPage("voices/1"), service.execute("sounds/1", PluginContext("", "")))
        service.execute("system-default", PluginContext("", ""))
        ServiceSettings(environment.storage).let { assertNull(it.provider); assertNull(it.voice); assertNull(it.systemVoice) }
        service.tts.close()
    }
}
