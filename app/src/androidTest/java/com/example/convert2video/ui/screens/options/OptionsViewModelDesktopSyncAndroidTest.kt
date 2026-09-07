package com.example.convert2video.ui.screens.options

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.desktopsync.ServerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsViewModelDesktopSyncAndroidTest {

    @Test
    fun success_startIsIndependentOfEntitlementAndStartsFromStopped() = runScenario {
        val serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
        var startCount = 0
        installDesktopSyncSeamsForTest(
            startServer = { startCount++ },
            serverState = { serverState },
        )

        // Given/When: no entitlement provider is involved.
        startDesktopSync()

        // Then
        assertEquals(1, startCount)
    }

    @Test
    fun success_startingOrRunningSuppressesDuplicateStart() = runScenario {
        val serverState = MutableStateFlow<ServerState>(ServerState.Starting)
        var startCount = 0
        installDesktopSyncSeamsForTest(
            startServer = { startCount++ },
            serverState = { serverState },
        )

        // Given/When
        startDesktopSync()
        serverState.value = ServerState.Running
        startDesktopSync()

        // Then
        assertEquals(0, startCount)
    }

    @Test
    fun success_failedStateAllowsRetryAfterTerminalTransition() = runScenario {
        val serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
        var startCount = 0
        installDesktopSyncSeamsForTest(
            startServer = {
                startCount++
                serverState.value = ServerState.Starting
            },
            serverState = { serverState },
        )

        // Given/When: first request enters startup and then fails.
        startDesktopSync()
        serverState.value = ServerState.Failed
        yield()

        // When: retry from Failed.
        startDesktopSync()

        // Then
        assertEquals(2, startCount)
    }

    @Test
    fun failure_serverStateReadResetsGuardAndDoesNotStart() = runScenario {
        var startCount = 0
        installDesktopSyncSeamsForTest(
            startServer = { startCount++ },
            serverState = { error("server state unavailable") },
        )

        // Given/When
        startDesktopSync()

        // Then
        assertEquals(0, startCount)
    }

    @Test
    fun success_startExceptionResetsGuardAndAllowsRetry() = runScenario {
        val serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
        var startCount = 0
        installDesktopSyncSeamsForTest(
            startServer = {
                startCount++
                if (startCount == 1) error("start failed")
            },
            serverState = { serverState },
        )

        // Given/When
        startDesktopSync()
        startDesktopSync()

        // Then
        assertEquals(2, startCount)
    }

    @Test
    fun exception_startCancellationIsRethrownAndAllowsRetry() = runScenario {
        val serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
        var startCount = 0
        installDesktopSyncSeamsForTest(
            startServer = {
                startCount++
                if (startCount == 1) throw CancellationException("cancelled")
            },
            serverState = { serverState },
        )

        // Given/When/Then
        var cancellationRethrown = false
        try {
            startDesktopSync()
        } catch (e: CancellationException) {
            cancellationRethrown = true
        }
        assertTrue(cancellationRethrown)
        startDesktopSync()
        assertEquals(2, startCount)
    }

    private fun runScenario(block: suspend OptionsViewModel.() -> Unit) {
        runBlocking(Dispatchers.Main) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = OptionsViewModel(app)
            try {
                viewModel.block()
            } finally {
                viewModel.installDesktopSyncSeamsForTest()
            }
        }
    }
}
