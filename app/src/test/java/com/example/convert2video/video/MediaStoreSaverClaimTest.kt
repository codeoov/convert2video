package com.example.convert2video.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Filesystem-level TOCTOU claim tests for [MediaStoreSaver.claimUniqueDestFile]
 * (no Android Context / FileProvider).
 */
class MediaStoreSaverClaimTest {

    @Test
    fun success_claimUniqueDestFile_usesPreferredWhenFree() {
        // Given
        val dir = Files.createTempDirectory("c2v_claim_").toFile()
        try {
            // When
            val claimed = MediaStoreSaver.claimUniqueDestFile(dir, "seg_1of2.mp4")
            // Then
            assertEquals("seg_1of2.mp4", claimed.displayName)
            assertTrue(claimed.destFile.exists())
            assertEquals(0L, claimed.destFile.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun success_claimUniqueDestFile_bumpsSuffixWhenPreferredExists() {
        // Given — preferred already on disk (simulates TOCTOU loser)
        val dir = Files.createTempDirectory("c2v_claim_").toFile()
        try {
            File(dir, "seg_1of2.mp4").createNewFile()
            // When
            val claimed = MediaStoreSaver.claimUniqueDestFile(dir, "seg_1of2.mp4")
            // Then
            assertEquals("seg_1of2_2.mp4", claimed.displayName)
            assertTrue(claimed.destFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
