package com.example.convert2video.auth

import android.app.Application
import android.content.ContextWrapper
import com.example.convert2video.drive.DriveAuthorizationOutcome
import com.example.convert2video.drive.createDriveAuthGateway
import com.example.convert2video.youtube.AuthorizationOutcome
import com.example.convert2video.youtube.createYouTubeAuthGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class HuaweiAuthGatewayTest {

    private val application = Application()

    @Test
    fun success_huaweiYouTubeGatewayIsNoOpAndReturnsUnavailable() = runBlocking {
        // Given
        val gateway = createYouTubeAuthGateway(application)

        // When
        val outcome = gateway.requestAuthorization()

        // Then
        assertFalse(gateway.isAuthorized)
        assertNull(gateway.cachedChannelTitle)
        assertNull(gateway.cachedAccountName)
        assertEquals(
            AuthorizationOutcome.Failed("Google 인증을 진행할 수 없습니다"),
            outcome,
        )
        gateway.rememberChannelTitle("ignored")
        gateway.rememberAccountName("ignored")
        gateway.signOut()
        assertFalse(gateway.isAuthorized)
    }

    @Test
    fun success_huaweiDriveGatewayIsNoOpAndReturnsUnavailable() = runBlocking {
        // Given
        val gateway = createDriveAuthGateway(application)

        // When
        val outcome = gateway.requestAuthorization()

        // Then
        assertFalse(gateway.isAuthorized)
        assertNull(gateway.cachedAccountEmail)
        assertNull(gateway.cachedAppFolderId)
        assertEquals(
            DriveAuthorizationOutcome.Failed("Google 인증을 진행할 수 없습니다"),
            outcome,
        )
        gateway.rememberAccountEmail("ignored")
        gateway.rememberAppFolderId("ignored")
        gateway.clearAppFolderId()
        gateway.signOut()
        assertFalse(gateway.isAuthorized)
    }

    @Test
    fun success_huaweiGatewayIntentResultsAreUnavailableWithoutPendingIntent() {
        // Given
        val intent = android.content.Intent("test")

        // When / Then
        assertEquals(
            AuthorizationOutcome.Failed("Google 인증을 진행할 수 없습니다"),
            createYouTubeAuthGateway(application).resultFromIntent(intent),
        )
        assertEquals(
            DriveAuthorizationOutcome.Failed("Google 인증을 진행할 수 없습니다"),
            createDriveAuthGateway(application).resultFromIntent(intent),
        )
    }

    @Test
    fun success_huaweiFactoriesAcceptContextWrapperAndStayNoOp() {
        // Given
        val wrapper = ContextWrapper(Application())

        // When
        val youtube = createYouTubeAuthGateway(wrapper)
        val drive = createDriveAuthGateway(wrapper)

        // Then — factories use applicationContext while Huawei remains context-free.
        assertFalse(youtube.isAuthorized)
        assertNull(youtube.cachedChannelTitle)
        assertFalse(drive.isAuthorized)
        assertNull(drive.cachedAppFolderId)
    }

    @Test
    fun failure_huaweiAuthorizationPreservesCancellation() = runBlocking {
        // Given
        val cancelledContext = Job().apply { cancel() }

        // When / Then
        try {
            withContext(cancelledContext) {
                createYouTubeAuthGateway(application).requestAuthorization()
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: no-op authorization must not convert cancellation into Failed.
        }
    }

    @Test
    fun failure_huaweiDriveAuthorizationPreservesCancellation() = runBlocking {
        // Given
        val cancelledContext = Job().apply { cancel() }

        // When / Then
        try {
            withContext(cancelledContext) {
                createDriveAuthGateway(application).requestAuthorization()
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: Drive no-op authorization must not convert cancellation into Failed.
        }
    }
}
