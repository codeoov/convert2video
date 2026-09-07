package com.example.convert2video.desktopsync

import java.security.SecureRandom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAuthTest {

    @Test
    fun success_generatedToken_isUrlSafeAndHasExpectedEntropy() {
        val first = generatePairingToken(SecureRandom())
        val second = generatePairingToken(SecureRandom())

        assertTrue(first.length >= 40)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(first, second)
    }

    @Test
    fun success_bearerTokenMatches_usesExactTokenComparison() {
        val token = "token-value"

        assertTrue(bearerTokenMatches(token, "Bearer $token"))
        assertFalse(bearerTokenMatches(token, "Bearer token-value-other"))
    }

    @Test
    fun failure_bearerTokenParser_rejectsMalformedHeaders() {
        assertFalse(bearerTokenMatches("token", null))
        assertFalse(bearerTokenMatches("token", "Basic token"))
        assertFalse(bearerTokenMatches("token", "Bearer "))
        assertFalse(bearerTokenMatches("token", "Bearer token extra"))
    }
}
