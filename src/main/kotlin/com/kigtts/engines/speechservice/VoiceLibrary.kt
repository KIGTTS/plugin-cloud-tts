// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import org.json.JSONObject
import org.json.JSONArray

internal class VoiceLibrary(storage: PluginStorage) {
    private val file = storage.file("config/voice-library.json").also { storage.configuration }
    private val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    var filter = json.optString("filter", "all").let { if (it == LEGACY_SYSTEM_PROVIDER) SYSTEM_PROVIDER else it }
    private var order = json.optJSONArray("order")?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: emptyList()
    private val pins = json.optJSONObject("pins") ?: JSONObject()
    init { if (json.optString("filter", "all") == LEGACY_SYSTEM_PROVIDER) save() }
    fun pinned(key: String, initial: Boolean) = if (pins.has(key)) pins.getBoolean(key) else initial
    fun pin(key: String, value: Boolean) { pins.put(key, value); save() }
    fun ordered(entries: List<PackEntry>): List<PackEntry> {
        val initial = entries.sortedWith(compareBy<PackEntry> { it.pack.metadata.order }.thenBy { it.key }).map { it.key }
        val ranked = ItemOrder.reconcile(order, initial).withIndex().associate { it.value to it.index }
        return entries.sortedWith(compareByDescending<PackEntry> { pinned(it.key, it.pack.metadata.pinned) }.thenBy { ranked[it.key] })
    }
    fun reorder(entries: List<PackEntry>, visible: List<String>, proposed: List<String>) {
        ItemOrder.validate(proposed, visible)
        val all = ordered(entries).map { it.key }.toMutableList()
        val indices = all.indices.filter { all[it] in visible }
        indices.forEachIndexed { index, slot -> all[slot] = proposed[index] }
        order = all; save()
    }
    fun save() { file.writeText(JSONObject().put("order", JSONArray(order)).put("pins", pins).put("filter", filter).toString()) }
}

internal data class PackEntry(val provider: TtsProvider, val pack: VoicePackInfo) {
    val key get() = provider.pluginId + "/" + pack.id
}
internal const val SYSTEM_PROVIDER = "@system"
internal fun voicePacks(providers: List<TtsProvider>): List<PackEntry> = providers.flatMap { provider ->
    val packs = if (provider.pluginId == SYSTEM_PROVIDER) listOf(VoicePackInfo("system", VoicePackMetadata("系统默认声音", "系统语音合成"), emptyList()))
        else provider.info.packs.ifEmpty { provider.info.voices.groupBy { it.packId ?: it.id }.map { (id, voices) ->
            VoicePackInfo(id, VoicePackMetadata(voices.first().packName ?: voices.first().name, voices.first().description), voices.map { it.id })
        } }
    packs.map { PackEntry(provider, it) }
}
