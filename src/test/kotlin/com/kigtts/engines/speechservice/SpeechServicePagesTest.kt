package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SpeechServicePagesTest {
    @get:Rule val files = TemporaryFolder()
    private val voice = VoiceInfo("voice/one", "清朗", "zh-CN")
    private val system = TtsProvider(SYSTEM_PROVIDER, "系统声音", TtsInfo())
    private val source = TtsProvider("test.provider", "Piper", TtsInfo(voices = listOf(voice)))
    private fun settings() = ServiceSettings(PluginStorage(files.newFolder()))

    @Test fun configurationShowsVoiceInsteadOfEngineStatusAndKeepsResidentInService() {
        val settings = settings().apply { provider = source.pluginId; voice = this@SpeechServicePagesTest.voice.id; resident = true }
        val pages = SpeechServicePages(settings) { listOf(system, source) }
        val page = pages.page("manager", true)!!
        val items = page.blocks.filterIsInstance<PageBlock.Group>().flatMap { it.children }
        val sound = items.filterIsInstance<PageBlock.Input>().single()
        assertEquals("声音", sound.title); assertEquals("清朗", sound.value); assertEquals("library", sound.actionId)
        assertEquals(1f, items.filterIsInstance<PageBlock.Slider>().single().control.value)
        assertTrue(items.filterIsInstance<PageBlock.Toggle>().single().checked)
        assertEquals(listOf("activate"), pages.page("manager", false)!!.blocks.filterIsInstance<PageBlock.Actions>().flatMap { it.actions }.map { it.id })
    }

    @Test fun unavailableVoiceFallsBackAndInactiveProvidersCannotBeSelected() {
        val settings = settings().apply { provider = source.pluginId; voice = this@SpeechServicePagesTest.voice.id }
        val pages = SpeechServicePages(settings) { listOf(system, source.copy(enabled = false)) }
        assertEquals("系统默认声音", pages.page("manager", true)!!.blocks.filterIsInstance<PageBlock.Group>()
            .flatMap { it.children }.filterIsInstance<PageBlock.Input>().single().value)
        assertEquals(listOf("system-default"), pages.page("voices", true)!!.management!!.items.map { it.id })
    }

    @Test fun voicePickerPaginatesAllVoicesAndUsesOriginalEncodedSelectionRoutes() {
        val settings = settings().apply { provider = source.pluginId; voice = this@SpeechServicePagesTest.voice.id }
        val many = source.copy(info = source.info.copy(voices = listOf(voice) + (1..61).map { voice.copy(id = "$it") }))
        val pages = SpeechServicePages(settings) { listOf(system, many) }
        val all = (0..2).flatMap { pages.page("voices/$it", true)!!.management!!.items }
        assertEquals(63, all.size)
        assertEquals(63, all.map { it.id }.distinct().size)
        val selected = all.single { it.selected }
        assertEquals("voice/${encodeId(source.pluginId)}/${encodeId(voice.id)}", selected.primaryAction)
        assertEquals("Piper", selected.group)
        assertEquals(listOf("sounds/1"), pages.page("voices/0", true)!!.blocks.filterIsInstance<PageBlock.Actions>().flatMap { it.actions }.map { it.id })
        assertEquals(15, pages.page("voices/999", true)!!.management!!.items.size)
    }
    @Test fun providerCatalogWithRepeatedVoiceIdsDoesNotBreakVoicePicker() {
        val duplicated = system.copy(info = TtsInfo(voices = listOf(voice, voice.copy(language = "en-US"))))
        val pages = SpeechServicePages(settings()) { listOf(duplicated, source) }
        val page = pages.page("voices/0", true)!!
        assertEquals(2, page.management!!.items.size)
        assertEquals(2, page.management!!.items.map { it.id }.distinct().size)
        assertEquals("系统默认声音", page.management!!.items.single { it.id == "system-default" }.title)
        assertEquals("", languageName("und"))
        PluginSnapshot(PluginDescriptor("test.service", "服务", "test"), PluginContributions(), listOf(page))
    }
}
