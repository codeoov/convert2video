package com.example.convert2video.record

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical RIFF/WAVE PCM header size in bytes (fmt + data headers). */
internal const val WAV_HEADER_BYTES = 44

/** Canonical 44-byte PCM WAV header (RIFF/WAVE). */
internal fun buildWavHeader(
    totalPcmBytes: Long,
    sampleRate: Int,
    channelCount: Int,
    bitsPerSample: Int,
): ByteArray {
    require(totalPcmBytes >= 0L) { "totalPcmBytes must be >= 0" }
    require(sampleRate > 0) { "sampleRate must be > 0" }
    require(channelCount > 0) { "channelCount must be > 0" }
    require(bitsPerSample > 0 && bitsPerSample % 8 == 0) { "bitsPerSample must be positive multiple of 8" }

    val byteRate = sampleRate * channelCount * bitsPerSample / 8
    val blockAlign = channelCount * bitsPerSample / 8
    val chunkSize = 36L + totalPcmBytes

    val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(chunkSize.toInt())
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16) // Subchunk1Size
    buffer.putShort(1) // PCM
    buffer.putShort(channelCount.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(byteRate)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(totalPcmBytes.toInt())
    return buffer.array()
}
