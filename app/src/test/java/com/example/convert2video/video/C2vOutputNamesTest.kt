package com.example.convert2video.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class C2vOutputNamesTest {

    private val timestampFormat = SimpleDateFormat("'(C2V)'yyyy-MM-dd_HH-mm", Locale.US)

    /** Fixed epoch — avoids minute-boundary flaky Date() comparisons. */
    private val fixedNowMillis = 1_720_000_000_000L

    private fun fixedTimestamp(): String = timestampFormat.format(Date(fixedNowMillis))

    @Test
    fun success_buildDisplayName_plainWhenSegmentNull() {
        // Given
        val existing = emptySet<String>()
        val expected = "${fixedTimestamp()}.mp4"
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals(expected, name)
    }

    @Test
    fun success_buildDisplayName_nofMWhenSegmentProvided() {
        // Given
        val existing = emptySet<String>()
        val expected = "${fixedTimestamp()}_1of3.mp4"
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            segmentIndex = 1,
            segmentTotal = 3,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals(expected, name)
    }

    @Test
    fun success_buildDisplayName_collisionSuffixForPlain() {
        // Given
        val ts = fixedTimestamp()
        val existing = setOf("$ts.mp4")
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${ts}_2.mp4", name)
    }

    @Test
    fun success_buildDisplayName_collisionSuffixForNofM() {
        // Given
        val stem = "${fixedTimestamp()}_1of3"
        val existing = setOf("$stem.mp4")
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            segmentIndex = 1,
            segmentTotal = 3,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${stem}_2.mp4", name)
    }

    @Test
    fun success_buildDisplayName_nofMCollisionChainTo_3() {
        // Given — stem and stem_2 already taken → next is stem_3
        val stem = "${fixedTimestamp()}_1of3"
        val existing = setOf("$stem.mp4", "${stem}_2.mp4")
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            segmentIndex = 1,
            segmentTotal = 3,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${stem}_3.mp4", name)
    }

    @Test
    fun success_buildDisplayName_plainAndNofMStemsCoexist() {
        // Given — plain `_2` collision name must not steal NofM stem
        val ts = fixedTimestamp()
        val nofMStem = "${ts}_1of2"
        val existing = setOf("$ts.mp4", "${ts}_2.mp4", "$nofMStem.mp4")
        // When
        val plainNext = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            nowMillis = fixedNowMillis,
        )
        val nofMNext = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            segmentIndex = 1,
            segmentTotal = 2,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${ts}_3.mp4", plainNext)
        assertEquals("${nofMStem}_2.mp4", nofMNext)
    }

    @Test
    fun success_firstAvailableDisplayName_skipsTakenPreferred() {
        // Given — preferred already taken (TOCTOU / collision)
        val taken = setOf("(C2V)2026-07-28_10-00_1of3.mp4")
        // When
        val name = C2vOutputNames.firstAvailableDisplayName(
            preferredDisplayName = "(C2V)2026-07-28_10-00_1of3.mp4",
            isTaken = taken::contains,
        )
        // Then — bumps to _2 without rebuilding stem logic
        assertEquals("(C2V)2026-07-28_10-00_1of3_2.mp4", name)
    }

    @Test
    fun success_firstAvailableDisplayName_skipsChainTo_3() {
        // Given
        val preferred = "(C2V)2026-07-28_10-00.mp4"
        val taken = setOf(preferred, "(C2V)2026-07-28_10-00_2.mp4")
        // When
        val name = C2vOutputNames.firstAvailableDisplayName(preferred, taken::contains)
        // Then
        assertEquals("(C2V)2026-07-28_10-00_3.mp4", name)
    }

    @Test
    fun failure_buildDisplayName_mixedSegmentArgs() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            C2vOutputNames.buildDisplayName(
                existingNames = emptySet(),
                segmentIndex = 1,
                segmentTotal = null,
                nowMillis = fixedNowMillis,
            )
        }
        assertTrue(error.message.orEmpty().contains("segmentIndex"))
    }

    @Test
    fun failure_buildDisplayName_indexZeroOfThree() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            C2vOutputNames.buildDisplayName(
                existingNames = emptySet(),
                segmentIndex = 0,
                segmentTotal = 3,
                nowMillis = fixedNowMillis,
            )
        }
        assertTrue(error.message.orEmpty().contains("segmentIndex"))
    }

    @Test
    fun failure_buildDisplayName_indexExceedsTotal() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            C2vOutputNames.buildDisplayName(
                existingNames = emptySet(),
                segmentIndex = 4,
                segmentTotal = 3,
                nowMillis = fixedNowMillis,
            )
        }
        assertTrue(error.message.orEmpty().contains("segmentIndex"))
    }

    @Test
    fun failure_buildDisplayName_totalZero() {
        // Given / When / Then
        val error = assertThrows(IllegalArgumentException::class.java) {
            C2vOutputNames.buildDisplayName(
                existingNames = emptySet(),
                segmentIndex = 1,
                segmentTotal = 0,
                nowMillis = fixedNowMillis,
            )
        }
        assertTrue(error.message.orEmpty().contains("segmentTotal"))
    }

    // ── originalFileStem naming (real recorder filename instead of the timestamp) ──────────

    @Test
    fun success_buildDisplayName_usesOriginalFileStemWhenProvided() {
        // Given — a typical recorder filename with an extension
        val existing = emptySet<String>()
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            originalFileStem = "녹음 2026-08-02 10-15-30.m4a",
            nowMillis = fixedNowMillis,
        )
        // Then — extension stripped, timestamp scheme not used
        assertEquals("녹음 2026-08-02 10-15-30.mp4", name)
    }

    @Test
    fun success_buildDisplayName_originalFileStemWithNofMSuffix() {
        // Given
        val existing = emptySet<String>()
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            segmentIndex = 2,
            segmentTotal = 4,
            originalFileStem = "회의록.wav",
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("회의록_2of4.mp4", name)
    }

    @Test
    fun success_buildDisplayName_originalFileStemCollisionSuffix() {
        // Given — same sanitized stem already saved once
        val existing = setOf("메모.mp4")
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            originalFileStem = "메모.mp3",
            nowMillis = fixedNowMillis,
        )
        // Then — shares firstAvailableDisplayName's `_2` collision logic
        assertEquals("메모_2.mp4", name)
    }

    @Test
    fun success_buildDisplayName_fallsBackToTimestampWhenOriginalFileStemNull() {
        // Given / When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = emptySet(),
            originalFileStem = null,
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${fixedTimestamp()}.mp4", name)
    }

    @Test
    fun success_buildDisplayName_fallsBackToTimestampWhenOriginalFileStemSanitizesToBlank() {
        // Given — only forbidden characters, nothing left after sanitizing
        val existing = emptySet<String>()
        // When
        val name = C2vOutputNames.buildDisplayName(
            existingNames = existing,
            originalFileStem = "///???.m4a",
            nowMillis = fixedNowMillis,
        )
        // Then
        assertEquals("${fixedTimestamp()}.mp4", name)
    }

    @Test
    fun success_sanitizeOriginalFileStem_stripsExtension() {
        assertEquals("녹음파일", C2vOutputNames.sanitizeOriginalFileStem("녹음파일.m4a"))
    }

    @Test
    fun success_sanitizeOriginalFileStem_noExtensionKeptAsIs() {
        assertEquals("녹음파일", C2vOutputNames.sanitizeOriginalFileStem("녹음파일"))
    }

    @Test
    fun success_sanitizeOriginalFileStem_replacesForbiddenCharsWithUnderscore() {
        // Given — Windows/Android-forbidden filesystem chars mixed into a real-looking filename
        val raw = "voice:memo*2026.wav"
        // When
        val sanitized = C2vOutputNames.sanitizeOriginalFileStem(raw)
        // Then
        assertEquals("voice_memo_2026", sanitized)
    }

    @Test
    fun success_sanitizeOriginalFileStem_trimsSurroundingWhitespace() {
        // Given — no extension so both leading/trailing whitespace survive into sanitize
        assertEquals("메모", C2vOutputNames.sanitizeOriginalFileStem("  메모  "))
    }

    @Test
    fun success_sanitizeOriginalFileStem_preservesUnicodeAndEmoji() {
        // Given — Korean + emoji, both legal filesystem chars, must survive sanitizing
        assertEquals("회의📝메모", C2vOutputNames.sanitizeOriginalFileStem("회의📝메모.m4a"))
    }

    @Test
    fun success_sanitizeOriginalFileStem_truncatesToMaxLength() {
        // Given — stem longer than MAX_ORIGINAL_STEM_LENGTH
        val longStem = "가".repeat(200)
        // When
        val sanitized = C2vOutputNames.sanitizeOriginalFileStem("$longStem.m4a")
        // Then
        assertEquals(C2vOutputNames.MAX_ORIGINAL_STEM_LENGTH, sanitized?.length)
        assertEquals("가".repeat(C2vOutputNames.MAX_ORIGINAL_STEM_LENGTH), sanitized)
    }

    @Test
    fun success_sanitizeOriginalFileStem_nullReturnsNull() {
        assertEquals(null, C2vOutputNames.sanitizeOriginalFileStem(null))
    }

    @Test
    fun success_sanitizeOriginalFileStem_blankReturnsNull() {
        assertEquals(null, C2vOutputNames.sanitizeOriginalFileStem("   "))
    }

    @Test
    fun success_sanitizeOriginalFileStem_onlyForbiddenCharsReturnsNull() {
        // Given — sanitizes down to underscores/whitespace only, which trim() clears
        assertEquals(null, C2vOutputNames.sanitizeOriginalFileStem("///.wav"))
    }
}
