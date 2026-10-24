package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CloudTtsUiTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = PluginContext("", "")
    @Test fun accountAndPackRowsOpenAndVoiceFormKeepsRemoteIdUntilExplicitSave() = runTest {
        val (plugin, _) = plugin()
        val account = plugin.execute("add-provider/azure", context) as PluginOutcome.OpenPluginPage
        assertEquals(account, plugin.execute(account.pageId, context))
        var page = plugin.page(account.pageId, context)!!
        val key = blocks(page.blocks).filterIsInstance<PageBlock.Input>().first { it.title == "Speech Key" }
        val edit = plugin.execute(key.actionId, context) as PluginOutcome.TextInput
        assertTrue(edit.secret)
        assertEquals(PluginOutcome.RefreshUi, plugin.execute(edit.actionId, context.copy(inputs = mapOf("value" to "secret-key"))))
        assertTrue(plugin.execute(edit.actionId, context) is PluginOutcome.Message)
        page = plugin.page(account.pageId, context)!!
        val createPack = blocks(page.blocks).filterIsInstance<PageBlock.Actions>().flatMap { it.actions }.first { it.label == "创建语音包" }
        val inputPack = plugin.execute(createPack.id, context) as PluginOutcome.TextInput
        val pack = plugin.execute(inputPack.actionId, context.copy(inputs = mapOf("value" to "我的声音"))) as PluginOutcome.OpenPluginPage
        assertEquals(pack, plugin.execute(pack.pageId, context))
        val addVoice = blocks(plugin.page(pack.pageId, context)!!.blocks).filterIsInstance<PageBlock.Actions>().flatMap { it.actions }.first()
        val menu = plugin.execute(addVoice.id, context) as PluginOutcome.Menu
        assertEquals(listOf("预设声音", "克隆声音"), menu.items.map { it.label })
        val draft = plugin.execute(menu.items.first().id, context) as PluginOutcome.OpenPluginPage
        for ((label, value) in listOf("声音名称" to "晓晓", "服务商声音 ID" to "zh-CN-XiaoxiaoNeural")) {
            val field = blocks(plugin.page(draft.pageId, context)!!.blocks).filterIsInstance<PageBlock.Input>().first { it.title == label }
            val input = plugin.execute(field.actionId, context) as PluginOutcome.TextInput
            plugin.execute(input.actionId, context.copy(inputs = mapOf("value" to value)))
        }
        assertTrue(plugin.tts.info.voices.isEmpty())
        val save = blocks(plugin.page(draft.pageId, context)!!.blocks).filterIsInstance<PageBlock.Actions>().flatMap { it.actions }.first { it.label == "添加声音" }
        val saved = plugin.execute(save.id, context) as PluginOutcome.OpenPluginPage
        assertEquals(pack.pageId, saved.pageId)
        assertEquals("晓晓", plugin.tts.info.voices.single().name)
        val voiceId = plugin.tts.info.voices.single().id
        assertNotEquals("zh-CN-XiaoxiaoNeural", voiceId)
    }
    @Test fun serviceVoiceCommandsCanRenameUpdatePinAndDeleteAPack() = runTest {
        val (plugin, root) = plugin()
        val settings = CloudTtsSettings(PluginStorage(root)); val account = settings.addProvider(CloudTtsProvider.AZURE)
        settings.updateProvider(account.id, "apiKey", "key"); val pack = settings.createPack(account.id, "原名")
        settings.addVoice(pack.id, account.id, "晓晓", "zh-CN", CloudVoiceKind.PRESET, "zh-CN-XiaoxiaoNeural")
        plugin.attach(environment(root))
        assertEquals(PluginOutcome.RefreshUi, plugin.voiceManager.command(VoiceCommand(VoiceOperation.RENAME, packId = pack.id, value = "新名")))
        assertEquals("新名", plugin.tts.info.packs.single().metadata.name)
        plugin.voiceManager.command(VoiceCommand(VoiceOperation.UPDATE_METADATA, packId = pack.id, metadata = VoicePackMetadata("新名", "备注", true, 12)))
        val metadata = plugin.tts.info.packs.single().metadata; assertTrue(metadata.pinned); assertEquals(12L, metadata.order); assertEquals("备注", metadata.remark)
        val source = PluginStorage(root).file("avatar.png")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        assertEquals(PluginOutcome.RefreshUi, plugin.voiceManager.command(VoiceCommand(VoiceOperation.SET_AVATAR, packId = pack.id, resourcePath = "avatar.png")))
        source.delete()
        val avatar = CloudTtsSettings(PluginStorage(root)).engineInfo().packs.single().avatarPath
        assertNotNull(avatar); assertTrue(PluginStorage(root).file(avatar!!).isFile)
        assertTrue(plugin.voiceManager.command(VoiceCommand(VoiceOperation.SET_AVATAR, packId = pack.id, resourcePath = "missing.png")) is PluginOutcome.Message)
        plugin.voiceManager.command(VoiceCommand(VoiceOperation.DELETE, packId = pack.id))
        assertTrue(plugin.tts.info.packs.isEmpty()); assertTrue(plugin.tts.info.voices.isEmpty())
    }
    @Test fun sameRemoteVoiceOnDifferentAccountsHasDistinctIdsAndBothAppearInLibrary() {
        val root = folder.newFolder(); val settings = CloudTtsSettings(PluginStorage(root))
        repeat(2) { index ->
            val account = settings.addProvider(CloudTtsProvider.AZURE); settings.updateProvider(account.id, "apiKey", "key-$index")
            val pack = settings.createPack(account.id, "声音包 $index")
            settings.addVoice(pack.id, account.id, "晓晓", "zh-CN", CloudVoiceKind.PRESET, "zh-CN-XiaoxiaoNeural")
        }
        val reloaded = CloudTtsSettings(PluginStorage(root))
        assertEquals(2, reloaded.engineInfo().voices.size); assertEquals(2, reloaded.engineInfo().voices.map { it.id }.distinct().size)
        assertEquals(listOf("Azure", "Azure"), reloaded.engineInfo().packs.map { it.providerLabel })
        assertEquals(listOf("zh-CN-XiaoxiaoNeural", "zh-CN-XiaoxiaoNeural"), reloaded.voiceList().map { it.remoteId })
        reloaded.toggleProvider(reloaded.providers().first().id)
        assertEquals(1, reloaded.engineInfo().voices.size)
    }
    @Test fun unsupportedCloneProvidersOnlyOfferPresetAndMalformedActionsDoNotCrash() = runTest {
        val (plugin, root) = plugin(); val settings = CloudTtsSettings(PluginStorage(root))
        val account = settings.addProvider(CloudTtsProvider.BAIDU); val pack = settings.createPack(account.id, "声音")
        plugin.attach(environment(root))
        val menu = plugin.execute("add-voice/${ttsEncode(pack.id)}", context) as PluginOutcome.Menu
        assertEquals(listOf("预设声音"), menu.items.map { it.label })
        assertTrue(plugin.execute("save", context) is PluginOutcome.Message)
        assertTrue(plugin.execute("provider/!!!", context) is PluginOutcome.Message || plugin.execute("provider/!!!", context) is PluginOutcome.OpenPluginPage)
    }
    private suspend fun plugin(): Pair<CloudTtsPlugin, File> {
        val root = folder.newFolder(); return CloudTtsPlugin().also { it.attach(environment(root)) } to root
    }
    private fun environment(root: File) = object : PluginEnvironment {
        override val storage = PluginStorage(root); override val assets = root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("not used")
    }
    private fun blocks(values: List<PageBlock>): List<PageBlock> = values.flatMap { if (it is PageBlock.Group) listOf(it) + blocks(it.children) else listOf(it) }
}
