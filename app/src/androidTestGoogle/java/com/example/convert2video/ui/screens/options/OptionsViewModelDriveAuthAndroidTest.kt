package com.example.convert2video.ui.screens.options

import android.accounts.Account
import android.app.Activity
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.drive.DriveApiResult
import com.example.convert2video.drive.DriveAuthorizationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OptionsViewModelDriveAuthAndroidTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
    }

    @Test
    fun failure_driveAuthorizationCancel_clearsInFlightAndEmitsCancelMessage() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val expected = app.getString(com.example.convert2video.R.string.drive_login_cancelled)
        withContext(Dispatchers.Main) {
            viewModel.markDriveAuthInFlightForTest()
            assertTrue(viewModel.isDriveAuthInFlight.value)
        }

        // When
        val message = withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                val pending = async { viewModel.userMessage.first() }
                viewModel.onDriveAuthorizationActivityResult(Activity.RESULT_CANCELED, null)
                pending.await()
            }
        }

        // Then
        withContext(Dispatchers.Main) {
            assertFalse(viewModel.isDriveAuthInFlight.value)
        }
        assertEquals(expected, message)
    }

    @Test
    fun success_signOutDrive_clearsInFlightWhileAuthInFlight() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        withContext(Dispatchers.Main) {
            viewModel.markDriveAuthInFlightForTest()
            assertTrue(viewModel.isDriveAuthInFlight.value)

            // When
            viewModel.signOutDrive()

            // Then
            assertFalse(viewModel.isDriveAuthInFlight.value)
            assertFalse(viewModel.isDriveAuthorized.value)
            assertEquals(null, viewModel.driveAccountEmail.value)
        }
    }

    @Test
    fun failure_driveAuthorizationLaunchFailed_clearsInFlightAndEmitsFallback() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val expected = app.getString(com.example.convert2video.R.string.drive_auth_incomplete)
        withContext(Dispatchers.Main) {
            viewModel.markDriveAuthInFlightForTest()
        }

        // When
        val message = withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                val pending = async { viewModel.userMessage.first() }
                viewModel.onDriveAuthorizationLaunchFailed()
                pending.await()
            }
        }

        // Then
        withContext(Dispatchers.Main) {
            assertFalse(viewModel.isDriveAuthInFlight.value)
        }
        assertEquals(expected, message)
    }

    @Test
    fun success_refreshDriveStorageQuota_skipsAuthorizationWhenSignedOut() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val authCalls = AtomicInteger(0)
        withContext(Dispatchers.Main) {
            viewModel.signOutDrive()
            viewModel.installDriveQuotaSeamsForTest(
                requestAuthorization = {
                    authCalls.incrementAndGet()
                    DriveAuthorizationOutcome.Failed("should not run")
                },
            )

            // When
            viewModel.refreshDriveStorageQuota()
        }

        // Then
        withContext(Dispatchers.Main) {
            assertEquals(0, authCalls.get())
            assertEquals(null, viewModel.driveStorageQuota.value)
        }
    }

    @Test
    fun success_quotaFetchFailure_keepsDriveAccountEmail() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val email = "keep@example.com"
        withContext(Dispatchers.Main) {
            viewModel.installDriveQuotaSeamsForTest(
                requestAuthorization = {
                    DriveAuthorizationOutcome.Authorized("test-token")
                },
                fetchAccountEmail = { DriveApiResult.Success(email) },
                fetchStorageQuota = { DriveApiResult.Failure("quota_unparseable") },
            )
            viewModel.onDriveAccountPicked(Account(email, "com.google"))
        }

        // When
        withTimeout(5_000) {
            while (true) {
                val ready = withContext(Dispatchers.Main) {
                    !viewModel.isDriveAuthInFlight.value &&
                        viewModel.driveAccountEmail.value == email
                }
                if (ready) break
                delay(20)
            }
        }

        // Then
        withContext(Dispatchers.Main) {
            assertEquals(email, viewModel.driveAccountEmail.value)
            assertEquals(null, viewModel.driveStorageQuota.value)
        }
    }
}
