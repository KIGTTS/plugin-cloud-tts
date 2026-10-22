// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.speechservice

import com.kigtts.plugin.api.*
import org.json.JSONObject

/** All actions remain service-owned; provider settings cross only the scoped speech broker. */
internal fun packSettingsControls(entry: PackEntry): PluginOutcome.Controls = PluginOutcome.Controls(
    entry.pack.metadata.name, entry.pack.parameters.map { parameter ->
        PluginControl.Slider(parameter.title, packAction("pack-setting", entry) + "/" + encodeId(parameter.id),
            parameter.value, parameter.min, parameter.max, parameter.steps, parameter.suffix)
    }, packAction("pack-settings-reset", entry))

internal suspend fun updatePackSetting(entry: PackEntry, parameterId: String, value: String?, broker: SpeechBroker) {
    val parameter = requireNotNull(entry.pack.parameters.firstOrNull { it.id == parameterId }) { "语音参数已改变" }
    val number = requireNotNull(value?.toFloatOrNull()) { "请输入有效的参数值" }
    require(number.isFinite() && number in parameter.min..parameter.max) { "语音参数超出范围" }
    broker.command(entry.provider.pluginId, VoiceCommand(VoiceOperation.UPDATE_PARAMETERS, packId = entry.pack.id,
        value = JSONObject().put(parameterId, number).toString()))
}
