package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingExtensionTest {

    // Given / When / Then

    @Test
    fun success_recordingExtension_aac_returnsM4a() {
        assertEquals("m4a", recordingExtension("AAC"))
    }

    @Test
    fun success_recordingExtension_wav_returnsWav() {
        assertEquals("wav", recordingExtension("WAV"))
    }

    @Test
    fun success_recordingFormatFromFile_wavLowercase_returnsWav() {
        assertEquals(RecordingFormat.WAV, recordingFormatFromFile(java.io.File("clip.wav")))
    }

    @Test
    fun success_recordingFormatFromFile_wavUppercase_returnsWav() {
        assertEquals(RecordingFormat.WAV, recordingFormatFromFile(java.io.File("clip.WAV")))
    }

    @Test
    fun success_recordingFormatFromFile_m4a_returnsAac() {
        assertEquals(RecordingFormat.AAC, recordingFormatFromFile(java.io.File("clip.m4a")))
    }

    @Test
    fun success_recordingFormatFromFile_unknown_returnsAac() {
        assertEquals(RecordingFormat.AAC, recordingFormatFromFile(java.io.File("clip.mp3")))
        assertEquals(RecordingFormat.AAC, recordingFormatFromFile(java.io.File("clip")))
    }
}
