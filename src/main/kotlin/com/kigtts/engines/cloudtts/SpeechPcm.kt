package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object SpeechPcm {
    fun validate(audio: PcmAudio) {
        if (audio.sampleRate != 16_000 || audio.channels != 1) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "云端识别需要 16 kHz 单声道 PCM 音频")
        if (audio.bytes.isEmpty()) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "录音为空")
        if (audio.bytes.size > 16_000 * 2 * 60) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "单次录音不能超过 60 秒")
        if (audio.bytes.startsWithAscii("RIFF")) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "识别输入应为 PCM 数据，不能包含 WAV 文件头")
    }
    fun wav(audio: PcmAudio): ByteArray {
        val output = ByteBuffer.allocate(audio.bytes.size + 44).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray()).putInt(audio.bytes.size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(audio.channels.toShort()).putInt(audio.sampleRate).putInt(audio.sampleRate * audio.channels * 2)
            .putShort((audio.channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(audio.bytes.size).put(audio.bytes)
        return output.array()
    }
    fun output(bytes: ByteArray, sampleRate: Int = 16_000): PcmAudio {
        if (bytes.isEmpty()) throw EngineException(EngineError.INFERENCE, "云端服务没有返回音频")
        if (bytes.startsWithAscii("RIFF")) return readWav(bytes)
        if (bytes.startsWithAscii("ID3") || (bytes.size > 1 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224))
            throw EngineException(EngineError.UNSUPPORTED_FORMAT, "云端返回了压缩音频，请使用 PCM 输出接口")
        if (bytes.size % 2 != 0) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "云端 PCM 音频不完整")
        return PcmAudio(bytes, sampleRate, 1)
    }
    private fun readWav(bytes: ByteArray): PcmAudio {
        fun invalid(): Nothing = throw EngineException(EngineError.UNSUPPORTED_FORMAT, "云端 WAV 音频格式不受支持")
        if (bytes.size < 44 || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") invalid()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12; var rate = 0; var data: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val tag = String(bytes, offset, 4, Charsets.US_ASCII)
            val count = buffer.getInt(offset + 4)
            if (count < 0 || count.toLong() + offset + 8 > bytes.size) invalid()
            if (tag == "fmt ") {
                if (count < 16 || buffer.getShort(offset + 8).toInt() != 1 || buffer.getShort(offset + 10).toInt() != 1 || buffer.getShort(offset + 22).toInt() != 16) invalid()
                rate = buffer.getInt(offset + 12)
            }
            if (tag == "data") data = bytes.copyOfRange(offset + 8, offset + 8 + count)
            offset += 8 + count + (count and 1)
        }
        if (rate !in listOf(8000, 16000, 24000, 48000) || data == null || data.isEmpty() || data.size % 2 != 0) invalid()
        return PcmAudio(data, rate, 1)
    }
    private fun ByteArray.startsWithAscii(text: String) = size >= text.length && text.indices.all { this[it].toInt() == text[it].code }
}
