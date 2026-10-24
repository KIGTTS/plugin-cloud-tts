package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class CloudTtsEngine(private val settings: CloudTtsSettings,
    private val protocols: CloudTtsProtocols = CloudTtsProtocols()) : TtsEngine {
    override val info: TtsInfo get() = settings.engineInfo()

    override fun synthesize(request: TtsRequest): Flow<SynthesisEvent> = flow {
        val voice = (if (request.voiceId != null) settings.voiceList().firstOrNull { it.id == request.voiceId } else settings.currentVoice())
            ?: throw EngineException(EngineError.NOT_CONFIGURED, "请先添加并选用声音")
        val provider = settings.providers().firstOrNull { it.id == voice.providerId && it.enabled }
            ?: throw EngineException(EngineError.NOT_CONFIGURED, "当前声音的云端服务未启用")
        if (request.text.isBlank()) throw EngineException(EngineError.INFERENCE, "请输入要朗读的文字")
        if (request.text.codePointCount(0, request.text.length) > info.maxTextLength) throw EngineException(EngineError.INFERENCE, "朗读文字过长，请分段发送")
        val (characters, bytes) = when (provider.provider) {
            CloudTtsProvider.TENCENT -> 150 to 450
            CloudTtsProvider.ALIYUN -> 300 to 1200
            CloudTtsProvider.BAIDU -> 300 to 900
            CloudTtsProvider.IFlytek -> 1800 to 7200
            CloudTtsProvider.AZURE -> 2000 to 8000
        }
        emit(SynthesisEvent.Started)
        for (text in SpeechText.segments(request.text, characters, bytes)) {
            if (text.isNotBlank()) emit(SynthesisEvent.Audio(protocols.synthesize(provider, voice, request.copy(text = text))))
        }
        emit(SynthesisEvent.Finished())
    }.flowOn(Dispatchers.IO)
    override suspend fun close() = protocols.close()
}
