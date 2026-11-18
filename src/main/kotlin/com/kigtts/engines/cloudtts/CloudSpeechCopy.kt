package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.EngineError
import com.kigtts.plugin.api.EngineException

internal typealias SpeechCopy = (String, String) -> String

internal val defaultSpeechCopy: SpeechCopy = { _, fallback -> fallback }

internal class CloudSettingIssue(val key: String, fallback: String) : IllegalArgumentException(fallback)

internal class LocalizedSettingIssue(message: String) : IllegalArgumentException(message)

internal fun localizedEngineError(error: EngineException, copy: SpeechCopy): EngineException {
    val message = when (error.code) {
        EngineError.NOT_CONFIGURED -> copy("feedback.credentials", "请检查云端账户和授权")
        EngineError.UNSUPPORTED_FORMAT -> copy("feedback.input_format", "云端服务不支持当前音频格式")
        EngineError.TIMEOUT -> copy("feedback.timeout", "云端请求超时，请重试")
        EngineError.INITIALIZATION, EngineError.UNAVAILABLE ->
            copy("feedback.unavailable", "无法连接云端服务，请检查网络")
        EngineError.INFERENCE -> copy("feedback.request_failed", "云端请求失败，请检查账户、网络和服务授权")
    }
    return EngineException(error.code, message, error)
}
