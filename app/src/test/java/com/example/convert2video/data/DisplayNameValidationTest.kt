package com.example.convert2video.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameValidationTest {

    // Given / When / Then

    @Test
    fun success_isValidDisplayNameStem_acceptsNormalName() {
        assertTrue(isValidDisplayNameStem("my_recording"))
    }

    @Test
    fun failure_isValidDisplayNameStem_rejectsForbiddenChars() {
        assertFalse(isValidDisplayNameStem("bad/name"))
        assertFalse(isValidDisplayNameStem("bad*name"))
    }

    @Test
    fun failure_isValidDisplayNameStem_rejectsBlank() {
        assertFalse(isValidDisplayNameStem(""))
        assertFalse(isValidDisplayNameStem("   "))
    }

    @Test
    fun failure_isValidDisplayNameStem_rejectsTooLong() {
        assertFalse(isValidDisplayNameStem("a".repeat(MAX_DISPLAY_NAME_STEM_LENGTH + 1)))
    }
}
