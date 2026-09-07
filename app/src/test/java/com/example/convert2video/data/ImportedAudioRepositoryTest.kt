package com.example.convert2video.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ImportedAudioRepositoryTest {

    @Test
    fun success_resolveUniqueDestFileName_usesSanitizedDisplayName() {
        // Given
        val destDir = File.createTempFile("import_dir", "").apply {
            delete()
            mkdirs()
        }
        // When
        val name = ImportedAudioRepository.resolveUniqueDestFileName(destDir, "My Song.mp3")
        // Then
        assertEquals("My Song.mp3", name)
        destDir.deleteRecursively()
    }

    @Test
    fun success_resolveUniqueDestFileName_sanitizesForbiddenChars() {
        // Given
        val destDir = File.createTempFile("import_dir", "").apply {
            delete()
            mkdirs()
        }
        // When
        val name = ImportedAudioRepository.resolveUniqueDestFileName(destDir, "bad/name?.mp3")
        // Then
        assertEquals("bad_name_.mp3", name)
        destDir.deleteRecursively()
    }

    @Test
    fun success_resolveUniqueDestFileName_collisionAppendsSuffix() {
        // Given
        val destDir = File.createTempFile("import_dir", "").apply {
            delete()
            mkdirs()
        }
        File(destDir, "song.mp3").createNewFile()
        // When
        val name = ImportedAudioRepository.resolveUniqueDestFileName(destDir, "song.mp3")
        // Then
        assertEquals("song_2.mp3", name)
        destDir.deleteRecursively()
    }
}
