package com.example.convert2video.desktopsync

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.ImportedAudioRepository
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingServerServiceAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val serviceBound = CountDownLatch(1)
    private var isBound = false
    private lateinit var binder: PairingServerService.LocalBinder
    private lateinit var serverState: StateFlow<ServerState>
    private lateinit var pairingRequests: StateFlow<PairingRequest?>
    private lateinit var fakeAdvertiser: FakeAdvertiser
    private lateinit var fakeTokenStore: FakePairingTokenStore

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as PairingServerService.LocalBinder
            serverState = binder.serverState
            pairingRequests = binder.pairingRequest
            serviceBound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    @Before
    fun setUp() {
        isBound = context.bindService(
            Intent(context, PairingServerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue("Pairing service should bind", isBound)
        assertTrue("Pairing service should connect", serviceBound.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        awaitState(ServerState.Stopped)
        fakeAdvertiser = FakeAdvertiser()
        fakeTokenStore = FakePairingTokenStore()
        binder.setAdvertiserFactoryForTesting { _, _, _ -> fakeAdvertiser }
        binder.setReadinessProbeForTesting(null)
        binder.setForegroundCheckerForTesting { true }
        binder.setTokenStoreForTesting(fakeTokenStore)
    }

    @After
    fun tearDown() {
        if (isBound) {
            context.startService(stopIntent())
            awaitState(ServerState.Stopped)
            binder.setForegroundCheckerForTesting(null)
            binder.setTokenStoreForTesting(null)
            binder.setImportedAudioRepositoryForTesting(null)
            context.unbindService(serviceConnection)
            isBound = false
        } else {
            context.stopService(Intent(context, PairingServerService::class.java))
        }
    }

    @Test
    fun success_ping_returnsExactStatusHeaderAndBody_withForegroundNotification() {
        // Given
        startServerAndAwaitRunning()
        assertEquals(1, fakeAdvertiser.startCount)

        // When
        val pingResponse = request(PairingServerService.PING_ROUTE)
        val otherResponse = request("/other")

        // Then
        assertRunningNotificationVisible()
        assertEquals("HTTP/1.1 200 OK", pingResponse.statusLine)
        assertEquals("application/json", pingResponse.headers["content-type"])
        assertEquals(expectedPingBody(), pingResponse.body)
        assertTrue(otherResponse.statusLine.startsWith("HTTP/1.1 404"))
    }

    @Test
    fun success_bindingWithoutExplicitStart_doesNotListenOnPairingPort() {
        // Given/When: the test setup binds the service with BIND_AUTO_CREATE only.
        awaitState(ServerState.Stopped)

        // Then: binding constructs the service but does not start its Ktor listener.
        ServerSocket(PairingServerService.SERVER_PORT).use { socket ->
            assertTrue(socket.isBound)
        }
    }

    @Test
    fun failure_whoAmIWithoutBearerToken_returnsUnauthorized() {
        // Given
        startServer()
        awaitState(ServerState.Running)

        // When
        val response = request(PairingServerService.WHO_AM_I_ROUTE)

        // Then
        assertEquals("HTTP/1.1 401 Unauthorized", response.statusLine)
        assertEquals("unauthorized", JSONObject(response.body).getString("error"))
    }

    @Test
    fun failure_tokenStoreRead_returnsInternalServerError() {
        fakeTokenStore.failOnRead = true
        startServer()
        awaitState(ServerState.Running)

        val response = request(PairingServerService.WHO_AM_I_ROUTE)

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine)
        assertEquals(ERROR_INTERNAL, JSONObject(response.body).getString("error"))
    }

    @Test
    fun failure_uploadMissingOrDuplicateField_returnsBadRequest() {
        fakeTokenStore.token = "test-upload-token"
        startServer()
        awaitState(ServerState.Running)

        val missing = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(omitFormat = true),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )
        val duplicate = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(duplicateDuration = true),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )

        assertEquals("HTTP/1.1 400 Bad Request", missing.statusLine)
        assertEquals("HTTP/1.1 400 Bad Request", duplicate.statusLine)
    }

    @Test
    fun failure_uploadInvalidFormatOrDuration_returnsBadRequest() {
        fakeTokenStore.token = "test-upload-token"
        startServer()
        awaitState(ServerState.Running)

        val invalidFormat = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(format = "mp3"),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )
        val invalidDuration = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(durationMs = "-1"),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )

        assertEquals("HTTP/1.1 400 Bad Request", invalidFormat.statusLine)
        assertEquals("HTTP/1.1 400 Bad Request", invalidDuration.statusLine)
    }

    @Test
    fun failure_uploadUnknownOrDuplicateStrictFields_returnsBadRequest() {
        fakeTokenStore.token = "test-upload-token"
        startServer()
        awaitState(ServerState.Running)
        val cases = listOf(
            validUploadBody(unknownField = true),
            validUploadBody(duplicateFile = true),
            validUploadBody(duplicateOriginal = true),
            validUploadBody(duplicateFormat = true),
        )

        cases.forEach { body ->
            val response = request(
                PairingServerService.UPLOAD_ROUTE,
                method = "POST",
                body = body,
                headers = mapOf("Authorization" to "Bearer test-upload-token"),
                contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
            )
            assertEquals("HTTP/1.1 400 Bad Request", response.statusLine)
        }
    }

    @Test
    fun failure_uploadEmptyOrInvalidWav_returnsBadRequest() {
        fakeTokenStore.token = "test-upload-token"
        startServer()
        awaitState(ServerState.Running)

        val invalid = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(filePayload = "NOT_A_WAV"),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )
        val empty = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(filePayload = ""),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )

        assertEquals("HTTP/1.1 400 Bad Request", invalid.statusLine)
        assertEquals("HTTP/1.1 400 Bad Request", empty.statusLine)
    }

    @Test
    fun failure_uploadDeclaredTooLarge_returnsPayloadTooLarge() {
        fakeTokenStore.token = "test-upload-token"
        startServer()
        awaitState(ServerState.Running)

        val response = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
            contentLengthOverride = PairingServerService.MAX_UPLOAD_BYTES + 1L,
        )

        assertEquals("HTTP/1.1 413 Request Entity Too Large", response.statusLine)
    }

    @Test
    fun failure_chunkedRequestBudget_stopsAtExactReadBoundary() = runBlocking {
        val source = ByteChannel(autoFlush = true)
        val bounded = ByteChannel(autoFlush = true)
        source.writeFully(ByteArray(9) { it.toByte() }, 0, 9)
        source.close()

        meterUploadRequestBytes(source, bounded, maxBytes = 8L)

        val received = ByteArray(16)
        assertEquals(8, bounded.readAvailable(received, 0, received.size))
        assertTrue(bounded.isClosedForRead)
        assertTrue(bounded.closedCause is UploadTooLargeException)
    }

    @Test
    fun failure_uploadWithoutBearerToken_isRejectedBeforeMultipartParsing() {
        // Given
        startServer()
        awaitState(ServerState.Running)

        // When
        val response = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )

        // Then
        assertEquals("HTTP/1.1 401 Unauthorized", response.statusLine)
        assertEquals(ERROR_UNAUTHORIZED, JSONObject(response.body).getString("error"))
    }

    @Test
    fun success_uploadWithWavAndDurationHint_returnsIndexedId() {
        // Given
        fakeTokenStore.token = "test-upload-token"
        binder.setImportedAudioRepositoryForTesting(
            ImportedAudioRepository(
                context = context,
                dao = AppDatabase.getInstance(context).importedAudioDao(),
                durationProvider = { 1_000L },
            ),
        )
        startServer()
        awaitState(ServerState.Running)

        // When
        val response = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(
                durationMs = "999999",
                originalFileName = "folder/sample:bad.mp3",
                fileName = "ignored.mp3",
            ),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )

        // Then
        assertEquals("HTTP/1.1 200 OK", response.statusLine)
        assertEquals("application/json", response.headers["content-type"])
        val id = JSONObject(response.body).getLong("id")
        val row = runBlocking {
            AppDatabase.getInstance(context).importedAudioDao().observeAll().first().single { it.id == id }
        }
        assertEquals("folder_sample_bad.mp3", row.originalDisplayName)
        assertEquals(1_000L, row.durationMs)
        assertTrue(File(row.filePath).isFile)
        assertTrue(File(row.filePath).name.endsWith(".wav"))
    }

    @Test
    fun success_uploadWithUppercaseFormat_isAcceptedCaseInsensitively() {
        // Given
        fakeTokenStore.token = "test-upload-token"
        binder.setImportedAudioRepositoryForTesting(
            ImportedAudioRepository(
                context = context,
                dao = AppDatabase.getInstance(context).importedAudioDao(),
                durationProvider = { 1_000L },
            ),
        )
        startServer()
        awaitState(ServerState.Running)

        // When
        val response = request(
            PairingServerService.UPLOAD_ROUTE,
            method = "POST",
            body = validUploadBody(format = "WAV"),
            headers = mapOf("Authorization" to "Bearer test-upload-token"),
            contentType = "multipart/form-data; boundary=$UPLOAD_BOUNDARY",
        )

        // Then
        assertEquals("HTTP/1.1 200 OK", response.statusLine)
    }

    @Test
    fun success_pairingRequestApproved_returnsTokenThatAuthenticatesWhoAmI() {
        // Given
        startServer()
        awaitState(ServerState.Running)
        val responseHolder = arrayOfNulls<HttpResponse>(1)
        val requestThread = Thread {
            responseHolder[0] = request(
                PairingServerService.PAIR_REQUEST_ROUTE,
                method = "POST",
                body = JSONObject()
                    .put("deviceName", "Test Desktop")
                    .put("requestedAtEpochMillis", System.currentTimeMillis())
                    .toString(),
            )
        }

        // When
        requestThread.start()
        val pending = runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                pairingRequests.filter { it != null }.first()!!
            }
        }
        assertEquals("Test Desktop", pending.deviceName)
        assertTrue(binder.approvePairingRequest(pending.requestId))
        requestThread.join(TEST_TIMEOUT_MILLIS)

        // Then
        assertFalse("Pairing request should finish", requestThread.isAlive)
        val pairingResponse = responseHolder[0] ?: error("Pairing response was not captured")
        assertEquals("HTTP/1.1 200 OK", pairingResponse.statusLine)
        val pairingJson = JSONObject(pairingResponse.body)
        assertFalse(pairingJson.has("entitlementJwt"))
        val token = pairingJson.getString("token")
        assertEquals(token, fakeTokenStore.token)

        val whoAmIResponse = request(
            PairingServerService.WHO_AM_I_ROUTE,
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        assertEquals("HTTP/1.1 200 OK", whoAmIResponse.statusLine)
        val whoAmIJson = JSONObject(whoAmIResponse.body)
        assertEquals(Build.MODEL ?: "", whoAmIJson.getString("deviceName"))
        assertFalse(whoAmIJson.has("entitlementJwt"))
    }

    @Test
    fun success_pairingAndWhoAmI_alwaysOmitEntitlementJwt() {
        // Given
        startServerAndAwaitRunning()
        val responseHolder = arrayOfNulls<HttpResponse>(1)
        val requestThread = Thread {
            responseHolder[0] = request(
                PairingServerService.PAIR_REQUEST_ROUTE,
                method = "POST",
                body = pairingRequestBody("Pro Desktop"),
            )
        }

        // When
        requestThread.start()
        val pending = runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                pairingRequests.filter { it != null }.first()!!
            }
        }
        assertTrue(binder.approvePairingRequest(pending.requestId))
        requestThread.join(TEST_TIMEOUT_MILLIS)

        // Then
        val pairingJson = JSONObject(responseHolder[0]?.body ?: error("Pairing response was not captured"))
        assertFalse(pairingJson.has("entitlementJwt"))
        val token = pairingJson.getString("token")
        val whoAmIJson = JSONObject(
            request(
                PairingServerService.WHO_AM_I_ROUTE,
                headers = mapOf("Authorization" to "Bearer $token"),
            ).body,
        )
        assertFalse(whoAmIJson.has("entitlementJwt"))
    }

    @Test
    fun success_errorResponses_omitEntitlementJwt() {
        // Given
        fakeTokenStore.token = "test-token"
        startServerAndAwaitRunning()

        // When
        val unauthorized = request(PairingServerService.WHO_AM_I_ROUTE)
        val badPairing = request(
            PairingServerService.PAIR_REQUEST_ROUTE,
            method = "POST",
            body = "{}",
        )

        // Then
        assertFalse(JSONObject(unauthorized.body).has("entitlementJwt"))
        assertFalse(JSONObject(badPairing.body).has("entitlementJwt"))
    }

    @Test
    fun success_pairingRequestRejected_returnsForbidden() {
        // Given
        startServer()
        awaitState(ServerState.Running)
        val responseHolder = arrayOfNulls<HttpResponse>(1)
        val requestThread = Thread {
            responseHolder[0] = request(
                PairingServerService.PAIR_REQUEST_ROUTE,
                method = "POST",
                body = pairingRequestBody("Reject Desktop"),
            )
        }

        // When
        requestThread.start()
        val pending = runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                pairingRequests.filter { it != null }.first()!!
            }
        }
        assertTrue(binder.rejectPairingRequest(pending.requestId))
        requestThread.join(TEST_TIMEOUT_MILLIS)

        // Then
        assertFalse("Pairing request should finish", requestThread.isAlive)
        assertEquals("HTTP/1.1 403 Forbidden", responseHolder[0]?.statusLine)
    }

    @Test
    fun failure_pairingRequestWhileBackgrounded_returnsServiceUnavailable() {
        // Given
        binder.setForegroundCheckerForTesting { false }
        startServer()
        awaitState(ServerState.Running)

        // When
        val response = request(
            PairingServerService.PAIR_REQUEST_ROUTE,
            method = "POST",
            body = pairingRequestBody("Backgrounded Desktop"),
        )

        // Then
        assertEquals("HTTP/1.1 503 Service Unavailable", response.statusLine)
        assertEquals("app_backgrounded", JSONObject(response.body).getString("error"))
    }

    @Test
    fun success_duplicateStartsAndStops_areIdempotent() {
        // Given / When
        startServer()
        startServer()
        awaitState(ServerState.Running)
        startServer()

        // Then
        awaitState(ServerState.Running)
        assertRunningNotificationVisible()
        assertEquals(1, pairingNotifications().size)

        context.startService(stopIntent())
        context.startService(stopIntent())
        awaitState(ServerState.Stopped)
        assertEquals(1, fakeAdvertiser.stopCount)
        assertNoPairingNotification()
        assertPortClosed()
    }

    @Test
    fun failure_occupiedPort_transitionsToFailed_thenCleanlyRestartsAfterRelease() {
        // Given / When
        ServerSocket(PairingServerService.SERVER_PORT).use {
            startServer()
            startServer()
            awaitState(ServerState.Starting)
            assertStartingNotificationVisible()
            awaitState(ServerState.Failed)

            // Then
            assertNoPairingNotification()
            assertFalse(
                pairingNotifications().any { notification ->
                    notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT) ==
                        context.getString(R.string.pairing_server_notification_running)
                },
            )
        }

        // When
        startServerAndAwaitRunning()

        // Then
        assertRunningNotificationVisible()
        assertEquals(expectedPingBody(), request(PairingServerService.PING_ROUTE).body)
    }

    @Test
    fun success_stopDuringStartup_cancelsStaleStartup_andAllowsImmediateRestart() {
        // Given
        ServerSocket(PairingServerService.SERVER_PORT).use {
            startServer()
            awaitState(ServerState.Starting)

            // When
            context.startService(stopIntent())
            awaitState(ServerState.Stopped)
        }

        // Then
        startServerAndAwaitRunning()
        assertEquals(1, fakeAdvertiser.startCount)
    }

    @Test
    fun success_onDestroy_closesPortAndSurvivesRepeatedCleanup() {
        // Given
        startServerAndAwaitRunning()

        // When
        context.stopService(Intent(context, PairingServerService::class.java))
        context.unbindService(serviceConnection)
        isBound = false

        // Then
        awaitState(ServerState.Stopped)
        assertEquals(1, fakeAdvertiser.stopCount)
        assertNoPairingNotification()
        assertPortClosed()
        context.stopService(Intent(context, PairingServerService::class.java))
        awaitState(ServerState.Stopped)
        assertPortClosed()
    }

    @Test
    fun success_onTimeoutWithStartId_closesPortAndSurvivesRepeatedCleanup() {
        // Given
        startServerAndAwaitRunning()

        // When
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            binder.invokeTimeoutForTesting(TEST_START_ID, null)
            binder.invokeTimeoutForTesting(TEST_START_ID, null)
        }

        // Then
        awaitState(ServerState.Stopped)
        assertEquals(1, fakeAdvertiser.stopCount)
        assertNoPairingNotification()
        assertPortClosed()
    }

    @Test
    fun success_onTimeoutWithFgsType_closesPortAndSurvivesRepeatedCleanup() {
        // Given
        startServerAndAwaitRunning()

        // When
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            binder.invokeTimeoutForTesting(TEST_START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            binder.invokeTimeoutForTesting(TEST_START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }

        // Then
        awaitState(ServerState.Stopped)
        assertEquals(1, fakeAdvertiser.stopCount)
        assertNoPairingNotification()
        assertPortClosed()
    }

    @Test
    fun success_startDuringTimeoutCleanup_isIgnoredAndDoesNotRestart() {
        // Given
        startServerAndAwaitRunning()
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        fakeAdvertiser.blockStop(stopEntered, allowStop)
        val timeoutThread = Thread {
            binder.invokeTimeoutForTesting(TEST_START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }

        // When
        timeoutThread.start()
        assertTrue("Timeout cleanup should be in progress", stopEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        startServer()
        allowStop.countDown()
        timeoutThread.join(TEST_TIMEOUT_MILLIS)

        // Then
        assertFalse("Timeout thread should finish", timeoutThread.isAlive)
        awaitState(ServerState.Stopped)
        assertEquals(1, fakeAdvertiser.startCount)
        assertEquals(1, fakeAdvertiser.stopCount)
    }

    @Test
    fun success_stopDuringReadinessProbe_cancelsFutureAndAllowsImmediateRestart() {
        // Given
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        binder.setReadinessProbeForTesting {
            probeEntered.countDown()
            try {
                releaseProbe.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }

        // When
        startServer()
        assertTrue("Readiness probe should be entered", probeEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        context.startService(stopIntent())
        awaitState(ServerState.Stopped)
        releaseProbe.countDown()
        binder.setReadinessProbeForTesting(null)

        // Then
        startServerAndAwaitRunning()
        assertEquals(1, fakeAdvertiser.startCount)
    }

    @Test
    fun failure_advertiserStart_doesNotStopKtorPing_andCleansAdvertiserOnce() {
        // Given
        fakeAdvertiser.failOnStart = true

        // When
        startServerAndAwaitRunning()

        // Then
        assertEquals(expectedPingBody(), request(PairingServerService.PING_ROUTE).body)
        assertEquals(1, fakeAdvertiser.startCount)
        assertEquals(1, fakeAdvertiser.stopCount)

        // When
        context.startService(stopIntent())
        awaitState(ServerState.Stopped)

        // Then
        assertEquals(1, fakeAdvertiser.stopCount)
    }

    @Test
    fun success_startArrivingDuringCleanup_isQueuedAndStartsAfterCleanup() {
        // Given
        startServerAndAwaitRunning()
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        fakeAdvertiser.blockStop(stopEntered, allowStop)
        val cleanupThread = Thread {
            binder.invokeCleanupForTesting()
        }

        // When
        cleanupThread.start()
        assertTrue("Cleanup should be in progress", stopEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        startServer()
        allowStop.countDown()
        cleanupThread.join(TEST_TIMEOUT_MILLIS)

        // Then
        assertFalse("Cleanup thread should finish", cleanupThread.isAlive)
        awaitState(ServerState.Running)
        assertEquals(2, fakeAdvertiser.startCount)
    }

    private fun startServerAndAwaitRunning() {
        startServer()
        awaitState(ServerState.Running)
        assertRunningNotificationVisible()
    }

    private fun startServer() {
        ContextCompat.startForegroundService(context, startIntent())
    }

    private fun awaitState(expectedState: ServerState) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                serverState.filter { it == expectedState }.first()
            }
        }
        assertEquals(expectedState, serverState.value)
    }

    private fun request(
        route: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        contentLengthOverride: Long? = null,
    ): HttpResponse {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), PairingServerService.SERVER_PORT),
                SOCKET_TIMEOUT_MILLIS,
            )
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(requestFor(method, route, body, headers, contentType, contentLengthOverride).toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            val rawResponse = socket.getInputStream()
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            return HttpResponse.parse(rawResponse)
        }
    }

    private fun assertPortClosed() {
        val connected = try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), PairingServerService.SERVER_PORT),
                    SOCKET_TIMEOUT_MILLIS,
                )
            }
            true
        } catch (_: IOException) {
            false
        }
        assertFalse("Pairing server port must be closed", connected)
    }

    private fun assertRunningNotificationVisible() {
        val notifications = pairingNotifications()
        assertEquals(1, notifications.size)
        assertEquals(
            context.getString(R.string.pairing_server_notification_running),
            notifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
    }

    private fun assertStartingNotificationVisible() {
        val notifications = pairingNotifications()
        assertEquals(1, notifications.size)
        assertEquals(
            context.getString(R.string.pairing_server_notification_starting),
            notifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
    }

    private fun assertNoPairingNotification() {
        assertTrue(pairingNotifications().isEmpty())
    }

    private fun pairingNotifications(): List<StatusBarNotification> {
        val expectedTitle = context.getString(R.string.pairing_server_notification_title)
        return notificationManager.activeNotifications.filter { statusBarNotification ->
            statusBarNotification.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == expectedTitle
        }
    }

    private fun expectedPingBody(): String = JSONObject()
        .put("status", "ok")
        .put("deviceName", Build.MODEL ?: "")
        .toString()

    private fun pairingRequestBody(deviceName: String): String = JSONObject()
        .put("deviceName", deviceName)
        .put("requestedAtEpochMillis", System.currentTimeMillis())
        .toString()

    private fun requestFor(
        method: String,
        route: String,
        body: String?,
        headers: Map<String, String>,
        contentType: String? = null,
        contentLengthOverride: Long? = null,
    ): String = buildString {
        append(method).append(' ').append(route).append(" HTTP/1.1\r\n")
        append("Host: 127.0.0.1\r\n")
        append("Connection: close\r\n")
        if (body != null) {
            append("Content-Type: ").append(contentType ?: "application/json").append("\r\n")
            append("Content-Length: ")
                .append(contentLengthOverride ?: body.toByteArray(StandardCharsets.UTF_8).size)
                .append("\r\n")
        }
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
        if (body != null) append(body)
    }

    private fun validUploadBody(
        durationMs: String = "120",
        format: String = "wav",
        omitFormat: Boolean = false,
        duplicateDuration: Boolean = false,
        originalFileName: String = "test.wav",
        fileName: String = "test.wav",
        filePayload: String = "RIFF0000WAVEfmt ",
        duplicateFile: Boolean = false,
        duplicateOriginal: Boolean = false,
        duplicateFormat: Boolean = false,
        unknownField: Boolean = false,
    ) = buildString {
        append("--").append(UPLOAD_BOUNDARY).append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
        append("Content-Type: audio/wav\r\n\r\n")
        append(filePayload)
        if (duplicateFile) {
            append("\r\n--").append(UPLOAD_BOUNDARY).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"second.wav\"\r\n\r\n")
            append(filePayload)
        }
        append("\r\n--").append(UPLOAD_BOUNDARY).append("\r\n")
        append("Content-Disposition: form-data; name=\"originalFileName\"\r\n\r\n")
        append(originalFileName).append("\r\n")
        if (duplicateOriginal) {
            append("--").append(UPLOAD_BOUNDARY).append("\r\n")
            append("Content-Disposition: form-data; name=\"originalFileName\"\r\n\r\n")
            append(originalFileName).append("\r\n")
        }
        if (!omitFormat) {
            append("--").append(UPLOAD_BOUNDARY).append("\r\n")
            append("Content-Disposition: form-data; name=\"format\"\r\n\r\n")
            append(format).append("\r\n")
            if (duplicateFormat) {
                append("--").append(UPLOAD_BOUNDARY).append("\r\n")
                append("Content-Disposition: form-data; name=\"format\"\r\n\r\n")
                append(format).append("\r\n")
            }
        }
        append("--").append(UPLOAD_BOUNDARY).append("\r\n")
        append("Content-Disposition: form-data; name=\"durationMs\"\r\n\r\n")
        append(durationMs).append("\r\n")
        if (duplicateDuration) {
            append("--").append(UPLOAD_BOUNDARY).append("\r\n")
            append("Content-Disposition: form-data; name=\"durationMs\"\r\n\r\n")
            append(durationMs).append("\r\n")
        }
        if (unknownField) {
            append("--").append(UPLOAD_BOUNDARY).append("\r\n")
            append("Content-Disposition: form-data; name=\"unknown\"\r\n\r\n")
            append("unexpected\r\n")
        }
        append("--").append(UPLOAD_BOUNDARY).append("--\r\n")
    }

    private fun startIntent(): Intent = Intent(context, PairingServerService::class.java)
        .setAction(PairingServerService.ACTION_START)

    private fun stopIntent(): Intent = Intent(context, PairingServerService::class.java)
        .setAction(PairingServerService.ACTION_STOP)

    private class FakeAdvertiser : PairingAdvertiser {
        var startCount = 0
            private set
        var stopCount = 0
            private set
        var failOnStart = false
        private var stopEntered: CountDownLatch? = null
        private var allowStop: CountDownLatch? = null

        fun blockStop(stopEntered: CountDownLatch, allowStop: CountDownLatch) {
            this.stopEntered = stopEntered
            this.allowStop = allowStop
        }

        override fun start() {
            startCount += 1
            if (failOnStart) {
                throw IllegalStateException("fake advertiser start failure")
            }
        }

        override fun stop() {
            stopCount += 1
            stopEntered?.countDown()
            allowStop?.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private class FakePairingTokenStore : PairingTokenStore {
        var token: String? = null
        var failOnRead = false
        private val deviceNames = mutableMapOf<String, String>()

        override suspend fun readToken(): String? {
            if (failOnRead) throw IllegalStateException("token read failure")
            return token
        }

        override suspend fun replaceToken(token: String) {
            this.token = token
        }

        override suspend fun clearToken() {
            token = null
        }

        override suspend fun storeDeviceName(name: String) {
            require(name.isNotBlank()) { "Device name must not be blank; use clearDeviceName() to remove" }
            deviceNames["device_name"] = name
        }

        override suspend fun readDeviceName(): String? {
            val v = deviceNames["device_name"] ?: return null
            return v.ifBlank { null }
        }

        override suspend fun clearDeviceName() {
            deviceNames.remove("device_name")
        }
    }

    private data class HttpResponse(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        companion object {
            fun parse(rawResponse: String): HttpResponse {
                val headerEnd = rawResponse.indexOf("\r\n\r\n")
                require(headerEnd >= 0) { "Pairing response must include HTTP headers" }
                val headerLines = rawResponse.substring(0, headerEnd).split("\r\n")
                val headers = headerLines.drop(1).associate { line ->
                    val separator = line.indexOf(':')
                    require(separator > 0) { "Malformed pairing response header" }
                    line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
                }
                return HttpResponse(
                    statusLine = headerLines.first(),
                    headers = headers,
                    body = rawResponse.substring(headerEnd + 4),
                )
            }
        }
    }

    private companion object {
        const val UPLOAD_BOUNDARY = "c2v-test-boundary"
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val TEST_START_ID = 101
        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}
