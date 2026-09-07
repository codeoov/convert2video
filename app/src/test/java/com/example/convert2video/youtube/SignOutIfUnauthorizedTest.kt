package com.example.convert2video.youtube

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class SignOutIfUnauthorizedTest {

    @Test
    fun success_signOutIfUnauthorized_401_invokesSignOut() {
        // Given
        var signOutCalls = 0

        // When
        signOutIfUnauthorized(httpCode = 401) { signOutCalls++ }

        // Then
        assertEquals(1, signOutCalls)
    }

    @Test
    fun success_signOutIfUnauthorized_403_doesNotSignOut() {
        // Given
        var signOutCalls = 0

        // When
        signOutIfUnauthorized(httpCode = 403) { signOutCalls++ }

        // Then
        assertEquals(0, signOutCalls)
    }

    @Test
    fun success_signOutIfUnauthorized_null_doesNotSignOut() {
        // Given
        var signOutCalls = 0

        // When
        signOutIfUnauthorized(httpCode = null) { signOutCalls++ }

        // Then
        assertEquals(0, signOutCalls)
    }

    @Test
    fun success_signOutIfUnauthorized_401_signOutThrow_doesNotPropagate() {
        // Given / When / Then — Worker must still reach failWithNotification
        signOutIfUnauthorized(httpCode = 401) { error("signOut boom") }
    }

    @Test
    fun exception_signOutIfUnauthorized_401_rethrowsCancellationException() {
        // Given / When / Then
        try {
            signOutIfUnauthorized(httpCode = 401) {
                throw CancellationException("signOut-cancel")
            }
            fail("expected CancellationException to be rethrown")
        } catch (ce: CancellationException) {
            assertEquals("signOut-cancel", ce.message)
        }
    }

    @Test
    fun success_predecessorSkipLogConstant_isFixedPhrase() {
        // Given / When / Then
        assertEquals(
            "youtube upload skipped: predecessor itemFailed",
            YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG,
        )
        assertFalse(YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG.contains("content://"))
        assertEquals("signed out after HTTP 401", SIGNED_OUT_AFTER_HTTP_401_LOG)
    }
}
