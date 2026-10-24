package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.PluginStorage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudTtsSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun voicesRequireAnExplicitPackAndKeepPresetCloneAndProviderMetadata() {
        val settings = CloudTtsSettings(PluginStorage(folder.newFolder("voices")))
        assertTrue(settings.providers().isEmpty())
        val provider = settings.addProvider(CloudTtsProvider.BAIDU)
        settings.updateProvider(provider.id, "apiKey", "local-key")
        settings.updateProvider(provider.id, "apiSecret", "local-secret")
        val pack = settings.createPack(provider.id, "我的声音")
        settings.addVoice(pack.id, provider.id, "女声", "zh-CN", CloudVoiceKind.PRESET, "baidu-female")
        settings.addVoice(pack.id, provider.id, "我的克隆", "zh-CN", CloudVoiceKind.CLONE, "clone-1", "自定义音色")
        assertEquals(2, settings.voiceList().size)
        assertEquals(CloudVoiceKind.CLONE, settings.voiceList().last().kind)
        assertEquals("我的声音", settings.packList().single().name)
        assertEquals("百度云", settings.toProvider()!!.info.packs.single().providerLabel)
        assertEquals("预设声音", settings.toProvider()!!.info.voices.first().description)
        assertTrue(settings.toProvider()!!.info.voices.all { it.packId == pack.id })
    }

    @Test fun noRemoteVoiceIsCreatedByAddingAProvider() {
        val settings = CloudTtsSettings(PluginStorage(folder.newFolder("empty")))
        settings.addProvider(CloudTtsProvider.TENCENT)
        assertTrue(settings.voiceList().isEmpty())
        assertTrue(settings.packList().isEmpty())
    }
}
