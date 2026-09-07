package com.example.convert2video.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class C2vRecordingNamesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Fixed epoch — avoids second-boundary flaky Date() comparisons. */
    private val fixedNowMillis = 1_720_000_000_000L

    /**
     * Oracle matching production: Instant + ZoneId.systemDefault() + same pattern/Locale.US.
     * No SimpleDateFormat. No ZoneId injection into production API.
     */
    private val expectedStemOracle: String =
        Instant.ofEpochMilli(fixedNowMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US))

    /** Zone-independent shape: `yyyy-MM-dd_HH-mm-ss.(m4a|wav)` */
    private val displayNameRegex = Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.(m4a|wav)$""")

    @Test
    fun success_buildDisplayName_hasNoC2vPrefix() {
        // Given / When
        val name = C2vRecordingNames.buildDisplayName(
            existingNames = emptySet(),
            format = RecordingFormat.AAC,
            nowMillis = fixedNowMillis,
        )
        // Then — 신규 무접두사 + 형태 계약 (TZ 독립)
        assertFalse(name.contains("(C2V)"))
        assertTrue(name.matches(displayNameRegex))
        assertTrue(name.endsWith(".m4a"))
    }

    @Test
    fun success_buildDisplayName_fixedEpochStemMatchesOracle() {
        // Given / When
        val name = C2vRecordingNames.buildDisplayName(
            existingNames = emptySet(),
            format = RecordingFormat.AAC,
            nowMillis = fixedNowMillis,
        )
        // Then — exact stem for fixed epoch (oracle = Instant + systemDefault + Locale.US)
        assertEquals("$expectedStemOracle.m4a", name)
    }

    @Test
    fun success_buildDisplayName_aacExtension() {
        // Given / When
        val name = C2vRecordingNames.buildDisplayName(
            existingNames = emptySet(),
            format = RecordingFormat.AAC,
            nowMillis = fixedNowMillis,
        )
        // Then — regex only (no test-side formatter clone for shape)
        assertTrue(name.matches(Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.m4a$""")))
    }

    @Test
    fun success_buildDisplayName_wavExtension() {
        // Given / When
        val name = C2vRecordingNames.buildDisplayName(
            existingNames = emptySet(),
            format = RecordingFormat.WAV,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertTrue(name.matches(Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.wav$""")))
    }

    @Test
    fun success_buildDisplayName_collisionSuffix_2() {
        // Given — derive stem from SUT (no formatter clone)
        val base = C2vRecordingNames.buildDisplayName(
            existingNames = emptySet(),
            format = RecordingFormat.AAC,
            nowMillis = fixedNowMillis,
        )
        val existing = setOf(base)
        // When
        val name = C2vRecordingNames.buildDisplayName(
            existingNames = existing,
            format = RecordingFormat.AAC,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${base.removeSuffix(".m4a")}_2.m4a", name)
    }

    @Test
    fun success_buildDisplayName_collisionChainTo_3() {
        // Given
        val base = C2vRecordingNames.buildDisplayName(
            existingNames = emptySet(),
            format = RecordingFormat.WAV,
            nowMillis = fixedNowMillis,
        )
        val stem = base.removeSuffix(".wav")
        val existing = setOf(base, "${stem}_2.wav")
        // When
        val name = C2vRecordingNames.buildDisplayName(
            existingNames = existing,
            format = RecordingFormat.WAV,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${stem}_3.wav", name)
    }

    @Test
    fun success_firstAvailableDisplayName_skipsTakenPreferred() {
        // Given
        val preferred = "2026-08-03_10-00-00.m4a"
        val taken = setOf(preferred)
        // When
        val name = C2vRecordingNames.firstAvailableDisplayName(preferred, taken::contains)
        // Then
        assertEquals("2026-08-03_10-00-00_2.m4a", name)
    }

    @Test
    fun failure_firstAvailableDisplayName_exceedsMaxSuffix() {
        // Given — preferred + _2…_99 all taken
        val preferred = "2026-08-03_10-00-00.m4a"
        val stem = preferred.removeSuffix(".m4a")
        val taken = buildSet {
            add(preferred)
            for (n in 2..C2vRecordingNames.MAX_SUFFIX) {
                add("${stem}_$n.m4a")
            }
        }
        // When / Then
        assertThrows(IllegalStateException::class.java) {
            C2vRecordingNames.firstAvailableDisplayName(preferred, taken::contains)
        }
    }

    @Test
    fun failure_firstAvailableDisplayName_rejectsInvalidExtension() {
        // Given
        val preferred = "2026-08-03_10-00-00.mp3"
        // When / Then
        assertThrows(IllegalArgumentException::class.java) {
            C2vRecordingNames.firstAvailableDisplayName(preferred) { false }
        }
    }

    @Test
    fun success_claimUniqueDestFile_createsEmptyFileAtomically() {
        // Given
        val dir = tempFolder.newFolder("c2v_music")
        val preferred = "2026-08-03_10-00-00.m4a"
        // When
        val claimed = C2vRecordingNames.claimUniqueDestFile(dir, preferred)
        // Then
        assertEquals(preferred, claimed.displayName)
        assertTrue(claimed.destFile.exists())
        assertEquals(0L, claimed.destFile.length())
    }

    @Test
    fun success_claimUniqueDestFile_bumpsSuffixWhenExists() {
        // Given
        val dir = tempFolder.newFolder("c2v_music2")
        val preferred = "2026-08-03_10-00-00.wav"
        File(dir, preferred).createNewFile()
        // When
        val claimed = C2vRecordingNames.claimUniqueDestFile(dir, preferred)
        // Then
        assertEquals("2026-08-03_10-00-00_2.wav", claimed.displayName)
        assertTrue(claimed.destFile.exists())
    }
}
