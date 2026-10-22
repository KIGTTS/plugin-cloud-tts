// SPDX-License-Identifier: GPL-3.0-only
package com.kigtts.engines.system

import com.kigtts.plugin.api.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RIFF chunks may be in any order and have padding; never assume a fixed 44-byte header. */
object WavPcm {
    fun read(file: File): PcmAudio {
        require(file.length() in 12..64L * 1024 * 1024) { "无效或过大的 WAV" }
        return decode(file.readBytes())
    }
    fun decode(bytes: ByteArray): PcmAudio {
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 0; var channels = 0; var data: ByteArray? = null; var format = 0; var bits = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4)
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= bytes.size)
            val start = offset + 8
            when (name) {
                "fmt " -> {
                    require(length >= 16)
                    format = buffer.getShort(start).toInt(); channels = buffer.getShort(start + 2).toInt()
                    rate = buffer.getInt(start + 4); bits = buffer.getShort(start + 14).toInt()
                }
                "data" -> data = bytes.copyOfRange(start, start + length)
            }
            offset = start + length + (length and 1)
        }
        if (format != 1 || bits != 16) throw EngineException(EngineError.UNSUPPORTED_FORMAT, "系统 TTS 输出不是 PCM16 WAV")
        return PcmAudio(requireNotNull(data), rate, channels)
    }
}
