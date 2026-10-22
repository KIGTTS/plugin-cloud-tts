package com.kigtts.engines.system

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {
    @Test fun readsOddPaddedChunkAndPcmPayload() {
        val wav = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(50).put("WAVE".toByteArray())
        wav.put("JUNK".toByteArray()).putInt(1).put(7).put(0)
        wav.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(4).putShort(100).putShort(-100)
        val result = WavPcm.decode(wav.array())
        assertEquals(16_000, result.sampleRate); assertEquals(1, result.channels)
        assertArrayEquals(byteArrayOf(100, 0, -100, -1), result.bytes)
    }
    @Test fun rejectsTruncatedWavInsteadOfReadingPastChunkBounds() {
        val bytes = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(12)
            .put("WAVE".toByteArray()).put("data".toByteArray()).putInt(Int.MAX_VALUE).array()
        assertThrows(IllegalArgumentException::class.java) { WavPcm.decode(bytes) }
    }
}
