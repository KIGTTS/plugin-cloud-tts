// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*

internal class VoiceLibraryActions(private val environment: PluginEnvironment, private val settings: ServiceSettings,
    private val library: VoiceLibrary, private val pages: VoiceLibraryPages, private val broker: SpeechBroker) {
    suspend fun execute(action: String, context: PluginContext): PluginOutcome {
        if (action == "library") return PluginOutcome.OpenPage("packs")
        if (action.startsWith("pack-filter/")) { library.filter = decodeId(action.substringAfter('/')); library.save(); return PluginOutcome.RefreshUi }
        if (action == "pack-order") {
            val order = PluginJson.decodeFromString<List<String>>(requireNotNull(context.inputs["order"]))
            val entries = pages.entries(); val visible = pages.visible()
            ItemOrder.validate(order, visible.map { it.key })
            require(order.map { id -> visible.first { it.key == id } }.map { library.pinned(it.key, it.pack.metadata.pinned) }.zipWithNext().none { !it.first && it.second })
            library.reorder(entries, visible.map { it.key }, order); return PluginOutcome.RefreshUi
        }
        val parts = action.split('/'); val owner = decodeId(parts[1]); val id = decodeId(parts[2])
        val entry = pages.entries().first { it.provider.pluginId == owner && it.pack.id == id }
        val current = entry.pack.metadata
        suspend fun command(operation: VoiceOperation, metadata: VoicePackMetadata? = null) =
            broker.command(owner, VoiceCommand(operation, packId = id, metadata = metadata, voiceId = entry.pack.voiceIds.firstOrNull()))
        return when (parts[0]) {
            "pack" -> PluginOutcome.OpenPage(action)
            "pack-use" -> {
                val voice = settings.voice?.takeIf { it in entry.pack.voiceIds } ?: entry.provider.info.selectedVoiceId?.takeIf { it in entry.pack.voiceIds } ?: entry.pack.voiceIds.firstOrNull()
                if (owner == SYSTEM_PROVIDER) { settings.provider = null; settings.voice = null }
                else { if (entry.provider.info.managesVoices && voice != null) broker.command(owner, VoiceCommand(VoiceOperation.SELECT, voiceId = voice)); settings.provider = owner; settings.voice = voice }
                settings.save(); PluginOutcome.RefreshUi
            }
            "pack-pin" -> {
                val pin = !library.pinned(entry.key, current.pinned)
                if (entry.pack.editable && entry.provider.enabled) command(VoiceOperation.UPDATE_METADATA, current.copy(pinned = pin))
                library.pin(entry.key, pin); PluginOutcome.RefreshUi
            }
            "pack-name", "pack-remark" -> PluginOutcome.TextInput(if (parts[0] == "pack-name") "语音包名称" else "语音包备注",
                action.replace(parts[0], parts[0] + "-save"), if (parts[0] == "pack-name") current.name else current.remark, multiline = parts[0] == "pack-remark")
            "pack-name-save", "pack-remark-save" -> { command(VoiceOperation.UPDATE_METADATA,
                if (parts[0] == "pack-name-save") current.copy(name = context.inputs["value"].orEmpty()) else current.copy(remark = context.inputs["value"].orEmpty())); PluginOutcome.RefreshUi }
            "pack-avatar" -> PluginOutcome.ImportResource("更换语音包头像", "resources/inbox", action.replace("pack-avatar", "pack-avatar-save"), mimeTypes = listOf("image/*"))
            "pack-avatar-save" -> {
                val path = requireNotNull(context.inputs["resourcePath"])
                try { broker.command(owner, VoiceCommand(VoiceOperation.SET_AVATAR, packId = id, resourcePath = path)) }
                finally { environment.storage.file(path).delete() }
                PluginOutcome.RefreshUi
            }
            "pack-update" -> PluginOutcome.ImportResource("更新语音包（保留名称、备注、头像和顺序）", "resources/inbox", action.replace("pack-update", "pack-update-save"))
            "pack-update-save" -> {
                val path = requireNotNull(context.inputs["resourcePath"])
                try { broker.command(owner, VoiceCommand(VoiceOperation.IMPORT, packId = id, resourcePath = path)) }
                finally { environment.storage.file(path).delete() }
                if (settings.provider == owner) {
                    val updated = pages.entries().first { it.key == entry.key }
                    if (settings.voice !in updated.pack.voiceIds) { settings.voice = updated.pack.voiceIds.firstOrNull(); settings.save() }
                }
                PluginOutcome.RefreshUi
            }
            "pack-export" -> {
                if (entry.pack.editable) command(VoiceOperation.UPDATE_METADATA, current.copy(
                    pinned = library.pinned(entry.key, current.pinned), order = pages.entries().indexOfFirst { it.key == entry.key }.toLong()))
                command(VoiceOperation.EXPORT)
            }
            "pack-delete" -> PluginOutcome.Menu("确认删除“${current.name}”及包中的全部声音？", listOf(PageAction(action.replace("pack-delete", "pack-delete-confirm"), "确认删除", PluginIcon.CLOSE)))
            "pack-delete-confirm" -> {
                command(VoiceOperation.DELETE)
                if (settings.provider == owner && settings.voice in entry.pack.voiceIds) { settings.voice = null; settings.provider = null; settings.save() }
                PluginOutcome.RefreshUi
            }
            else -> PluginOutcome.Message("未知语音包操作")
        }
    }
}
