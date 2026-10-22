// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.PluginStorage
import org.json.JSONObject

internal class ServiceSettings(storage: PluginStorage) {
    private val file = storage.file("config/service.json").also { storage.configuration }
    private val data = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    var provider: String? = data.optString("provider").takeIf { it.isNotBlank() }
    var voice: String? = data.optString("voice").takeIf { it.isNotBlank() }
    var language: String = data.optString("systemLanguage", "zh-CN")
    var systemVoice: String? = data.optString("systemVoice").takeIf { it.isNotBlank() }
    var resident: Boolean = data.optBoolean("resident", false)
    var rate: Float = data.optDouble("rate", 1.0).toFloat().takeIf { it.isFinite() && it in .25f..4f } ?: 1f
    init {
        if (provider == LEGACY_SYSTEM_PROVIDER || provider == SYSTEM_PROVIDER) {
            if (systemVoice == null) systemVoice = voice
            provider = null; voice = null
            save()
        }
    }
    fun save() { file.writeText(JSONObject().put("provider", provider.orEmpty()).put("voice", voice.orEmpty()).put("systemVoice", systemVoice.orEmpty()).put("systemLanguage", language).put("rate", rate).put("resident", resident).toString()) }
}

/** Old saved selections are migrated to the service-owned platform connection. */
internal const val LEGACY_SYSTEM_PROVIDER = "engine.system-tts"
