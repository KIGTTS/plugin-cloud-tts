package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class VoiceLibraryTest {
    @get:Rule val files = TemporaryFolder()
    private fun provider(id: String, enabled: Boolean = true) = TtsProvider(id, id, TtsInfo(
        voices = listOf(VoiceInfo("one", "第一声", "zh", packId = "pack"), VoiceInfo("two", "第二声", "zh", packId = "pack")),
        packs = listOf(VoicePackInfo("pack", VoicePackMetadata(id, "备注"), listOf("one", "two"), editable = true, exportable = true))), enabled)
    @Test fun oneRowPerPackAcrossEnginesIncludingDisabledCachedPacks() {
        val storage = PluginStorage(files.newFolder()); val library = VoiceLibrary(storage); val settings = ServiceSettings(storage)
        val pages = VoiceLibraryPages(settings, library) { listOf(provider("first.engine"), provider("second.engine", false)) }
        val page = pages.page("packs")!!
        assertEquals(2, page.management!!.items.size)
        assertTrue(page.management!!.items.last().subtitle.contains("已停用"))
        assertEquals(2, pages.page("pack/${encodeId("first.engine")}/${encodeId("pack")}")!!.management!!.items.size)
    }
    @Test fun crossEngineReorderAndFilteredReorderPersistWithoutMovingHiddenEntries() {
        val storage = PluginStorage(files.newFolder()); val entries = voicePacks(listOf(provider("a.engine"), provider("b.engine"), provider("c.engine")))
        val library = VoiceLibrary(storage)
        library.reorder(entries, entries.map { it.key }, entries.reversed().map { it.key })
        assertEquals(entries.reversed().map { it.key }, VoiceLibrary(storage).ordered(entries).map { it.key })
        library.reorder(entries, listOf(entries[0].key, entries[2].key), listOf(entries[0].key, entries[2].key))
        assertEquals(listOf(entries[0].key, entries[1].key, entries[2].key), VoiceLibrary(storage).ordered(entries).map { it.key })
        library.pin(entries[2].key, true)
        assertEquals(entries[2].key, VoiceLibrary(storage).ordered(entries).first().key)
    }
}
