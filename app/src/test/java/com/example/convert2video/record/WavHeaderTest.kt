package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavHeaderTest {

    @Test
    fun success_buildWavHeader_riffAndWaveMagicBytes() {
        // Given / When
        val header = buildWavHeader(
            totalPcmBytes = 100L,
            sampleRate = 44_100,
            channelCount = 1,
            bitsPerSample = 16,
        )
        // Then
        assertEquals(WAV_HEADER_BYTES, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", header.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))
    }

    @Test
    fun success_buildWavHeader_chunkSizeEqualsPcmBytesPlus36() {
        // Given
        val pcm = 2_000L
        // When
        val header = buildWavHeader(pcm, 44_100, 1, 16)
        val chunkSize = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        // Then
        assertEquals(36 + pcm.toInt(), chunkSize)
    }

    @Test
    fun success_buildWavHeader_subchunk2SizeEqualsPcmBytes() {
        // Given
        val pcm = 8_820L
        // When
        val header = buildWavHeader(pcm, 22_050, 2, 16)
        val subchunk2 = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        // Then
        assertEquals(pcm.toInt(), subchunk2)
    }

    @Test
    fun success_buildWavHeader_sampleRateAndChannels() {
        // Given / When
        val header = buildWavHeader(0L, 48_000, 2, 16)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        // Then — AudioFormat PCM=1, channels=2, rate=48000, byteRate=192000, blockAlign=4
        assertEquals(16, le.getInt(16))
        assertEquals(1.toShort(), le.getShort(20))
        assertEquals(2.toShort(), le.getShort(22))
        assertEquals(48_000, le.getInt(24))
        assertEquals(192_000, le.getInt(28))
        assertEquals(4.toShort(), le.getShort(32))
        assertEquals(16.toShort(), le.getShort(34))
    }

    @Test
    fun success_buildWavHeader_zeroPcmStillValidHeader() {
        // Given / When
        val header = buildWavHeader(0L, 44_100, 1, 16)
        val chunkSize = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        // Then — placeholder header used at capture start
        assertEquals(36, chunkSize)
    }

    @Test
    fun success_peakAmplitudePcm16le_returnsAbsPeak() {
        // Given — samples 100 and -5000 (LE)
        val buf = byteArrayOf(
            100, 0,
            (-5000 and 0xff).toByte(), ((-5000 shr 8) and 0xff).toByte(),
        )
        // When / Then
        assertEquals(5000, AudioRecordWavBackend.peakAmplitudePcm16le(buf, buf.size))
    }
}
