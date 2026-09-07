package com.example.convert2video.desktopsync

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.ImportedAudioMetadataException
import com.example.convert2video.data.ImportedAudioRepository
import com.example.convert2video.utils.AppLogger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.math.min
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal suspend fun meterUploadRequestBytes(
    source: ByteReadChannel,
    bounded: ByteChannel,
    maxBytes: Long = MAX_UPLOAD_BYTES,
) {
    require(maxBytes >= 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    try {
        while (true) {
            val remaining = maxBytes - totalBytes
            val readLength = min(buffer.size.toLong(), remaining + 1L).toInt()
            val read = source.readAvailable(buffer, 0, readLength)
            if (read < 0) {
                bounded.close()
                return
            }
            if (read == 0) continue

            val allowed = min(read.toLong(), remaining).toInt()
            if (allowed > 0) {
                bounded.writeFully(buffer, 0, allowed)
                totalBytes += allowed.toLong()
            }
            if (read.toLong() > remaining) {
                throw UploadTooLargeException()
            }
        }
    } catch (error: Exception) {
        source.cancel(error)
        bounded.close(error)
    }
}

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data object Running : ServerState
    data object Failed : ServerState
}

/** Local-LAN pairing service with authenticated WAV upload. */
class PairingServerService : Service() {

    class LocalBinder private constructor(
        val serverState: StateFlow<ServerState>,
        val pairingRequest: StateFlow<PairingRequest?>,
        private val invokeTimeout: (Int, Int?) -> Unit,
        private val setAdvertiserFactory: (PairingAdvertiserFactory) -> Unit,
        private val invokeCleanup: () -> Unit,
        private val setReadinessProbe: ((() -> Unit)?) -> Unit,
        private val approvePairing: (Long) -> Boolean,
        private val rejectPairing: (Long) -> Boolean,
        private val setForegroundChecker: ((() -> Boolean)?) -> Unit,
        private val setTokenStore: ((PairingTokenStore?) -> Unit),
        private val setImportedAudioRepository: ((ImportedAudioRepository?) -> Unit),
    ) : Binder() {

        /**
         * Instrumentation cannot ask Android to deliver an FGS quota timeout on demand. This
         * instance-local callback invokes the real Service timeout overload without exposing the
         * service itself or introducing global mutable test state.
         */
        @VisibleForTesting
        internal fun invokeTimeoutForTesting(startId: Int, fgsType: Int?) {
            invokeTimeout(startId, fgsType)
        }

        @VisibleForTesting
        internal fun setAdvertiserFactoryForTesting(factory: PairingAdvertiserFactory) {
            setAdvertiserFactory(factory)
        }

        @VisibleForTesting
        internal fun invokeCleanupForTesting() {
            invokeCleanup()
        }

        @VisibleForTesting
        internal fun setReadinessProbeForTesting(probe: (() -> Unit)?) {
            setReadinessProbe(probe)
        }

        fun approvePairingRequest(requestId: Long): Boolean = approvePairing(requestId)

        fun rejectPairingRequest(requestId: Long): Boolean = rejectPairing(requestId)

        @VisibleForTesting
        internal fun setForegroundCheckerForTesting(checker: (() -> Boolean)?) {
            setForegroundChecker(checker)
        }

        @VisibleForTesting
        internal fun setTokenStoreForTesting(store: PairingTokenStore?) {
            setTokenStore(store)
        }

        @VisibleForTesting
        internal fun setImportedAudioRepositoryForTesting(repository: ImportedAudioRepository?) {
            setImportedAudioRepository(repository)
        }

        companion object {
            internal fun create(
                serverState: StateFlow<ServerState>,
                pairingRequest: StateFlow<PairingRequest?>,
                invokeTimeout: (Int, Int?) -> Unit,
                setAdvertiserFactory: (PairingAdvertiserFactory) -> Unit,
                invokeCleanup: () -> Unit,
                setReadinessProbe: ((() -> Unit)?) -> Unit,
                approvePairing: (Long) -> Boolean,
                rejectPairing: (Long) -> Boolean,
                setForegroundChecker: ((() -> Boolean)?) -> Unit,
                setTokenStore: ((PairingTokenStore?) -> Unit),
                setImportedAudioRepository: ((ImportedAudioRepository?) -> Unit),
            ): LocalBinder = LocalBinder(
                serverState,
                pairingRequest,
                invokeTimeout,
                setAdvertiserFactory,
                invokeCleanup,
                setReadinessProbe,
                approvePairing,
                rejectPairing,
                setForegroundChecker,
                setTokenStore,
                setImportedAudioRepository,
            )
        }
    }

    private val serverLock = Any()
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PairingServerStartup")
    }
    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private val _pairingRequest = MutableStateFlow<PairingRequest?>(null)
    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder.create(
        serverState = _serverState.asStateFlow(),
        pairingRequest = _pairingRequest.asStateFlow(),
        invokeTimeout = ::invokeTimeout,
        setAdvertiserFactory = ::setAdvertiserFactoryForTesting,
        invokeCleanup = { cleanupServer(ServerState.Stopped) },
        setReadinessProbe = ::setReadinessProbeForTesting,
        approvePairing = ::approvePairingRequest,
        rejectPairing = ::rejectPairingRequest,
        setForegroundChecker = ::setForegroundCheckerForTesting,
        setTokenStore = ::setTokenStoreForTesting,
        setImportedAudioRepository = ::setImportedAudioRepositoryForTesting,
    )

    private var server: EmbeddedServer<*, *>? = null
    private var advertiser: PairingAdvertiser? = null
    private var advertiserFactory: PairingAdvertiserFactory = DefaultPairingAdvertiserFactory
    private var readinessProbeOverride: (() -> Unit)? = null
    private var isCleanupInFlight = false
    private var isStartupInFlight = false
    private var isDestroyed = false
    private var isTimeoutTerminated = false
    private var pendingStart = false
    private var startupFuture: Future<*>? = null
    private var startupFutureGeneration: Long? = null
    private var startupTaskThread: Thread? = null
    private var startupGeneration = 0L
    private val pairingLock = Any()
    private var pairingRequestSequence = 0L
    private var pendingPairing: PendingPairing? = null
    private var foregroundChecker: () -> Boolean = ::isApplicationForeground
    private var tokenStoreOverride: PairingTokenStore? = null
    private val tokenStore: PairingTokenStore by lazy {
        AndroidKeystorePairingTokenStore(applicationContext)
    }
    private val defaultImportedAudioRepository: ImportedAudioRepository by lazy {
        val database = AppDatabase.getInstance(applicationContext)
        ImportedAudioRepository(applicationContext, database.importedAudioDao())
    }
    private var importedAudioRepositoryOverride: ImportedAudioRepository? = null

    private data class PendingPairing(
        val request: PairingRequest,
        val decision: CompletableDeferred<PairingDecision>,
        var resolutionStarted: Boolean = false,
    )

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> requestStart()
            ACTION_STOP -> {
                synchronized(serverLock) {
                    pendingStart = false
                }
                cleanupServer(ServerState.Stopped)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val terminalState = synchronized(serverLock) {
            isDestroyed = true
            pendingStart = false
            if (_serverState.value == ServerState.Failed) ServerState.Failed else ServerState.Stopped
        }
        cleanupServer(terminalState)
        clearPendingPairing(PairingDecision.ServiceStopped)
        pairingScope.cancel()
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        markTimeoutTermination()
        cleanupServer(ServerState.Stopped)
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        markTimeoutTermination()
        cleanupServer(ServerState.Stopped)
        stopSelf(startId)
    }

    private fun invokeTimeout(startId: Int, fgsType: Int?) {
        if (fgsType == null) {
            onTimeout(startId)
        } else {
            onTimeout(startId, fgsType)
        }
    }

    private fun requestStart() {
        val shouldStart = synchronized(serverLock) {
            when {
                isDestroyed -> false
                isTimeoutTerminated -> false
                isCleanupInFlight -> {
                    pendingStart = true
                    false
                }
                else -> true
            }
        }
        if (shouldStart) {
            startServer()
        }
    }

    private fun startServer() {
        var generation: Long? = null
        synchronized(serverLock) {
            if (isCleanupInFlight || isStartupInFlight || server != null || _serverState.value == ServerState.Running) {
                return
            }

            _serverState.value = ServerState.Starting
            isStartupInFlight = true
            startupGeneration += 1
            if (startAsForeground(notificationFor(R.string.pairing_server_notification_starting))) {
                generation = startupGeneration
            } else {
                isStartupInFlight = false
            }
        }

        val generationToStart = generation
        if (generationToStart == null) {
            if (!cleanupServer(ServerState.Failed)) {
                stopSelf()
            }
            return
        }

        try {
            synchronized(serverLock) {
                if (!isCurrentStartupLocked(generationToStart)) {
                    return
                }
                startupFutureGeneration = generationToStart
                startupFuture = startupExecutor.submit {
                    synchronized(serverLock) {
                        startupTaskThread = Thread.currentThread()
                    }
                    try {
                        startServerAsync(generationToStart)
                    } finally {
                        synchronized(serverLock) {
                            if (startupTaskThread === Thread.currentThread()) {
                                startupTaskThread = null
                                if (startupFutureGeneration == generationToStart) {
                                    startupFuture = null
                                    startupFutureGeneration = null
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Pairing server startup task was rejected", error)
            if (!cleanupServer(ServerState.Failed)) {
                stopSelf()
            }
        }
    }

    private fun startServerAsync(generation: Long) {
        try {
            val pairingServer = embeddedServer(
                factory = CIO,
                host = SERVER_HOST,
                port = SERVER_PORT,
            ) {
                ReceiveRequestBytes.install(this) { call, source ->
                    val bounded = ByteChannel(autoFlush = true)
                    call.launch(Dispatchers.IO) {
                        meterUploadRequestBytes(source, bounded)
                    }
                    bounded
                }
                routing {
                    get(PING_ROUTE) {
                        call.respondJson(HttpStatusCode.OK, JSONObject(pingResponse()))
                    }
                    post(PAIR_REQUEST_ROUTE) {
                        handlePairingRequest(this.call)
                    }
                    get(WHO_AM_I_ROUTE) {
                        handleWhoAmI(this.call)
                    }
                    post(UPLOAD_ROUTE) {
                        handleUpload(this.call)
                    }
                }
            }
            val didStart = synchronized(serverLock) {
                if (!isCurrentStartup(generation)) {
                    false
                } else {
                    server = pairingServer
                    pairingServer.start(wait = false)
                    true
                }
            }
            if (!didStart) {
                try {
                    pairingServer.stop(gracePeriodMillis = 0, timeoutMillis = 0)
                } catch (stopError: Exception) {
                    AppLogger.e(TAG, "Pairing server abandoned during startup failed to stop", stopError)
                }
                return
            }

            (readinessProbeOverride ?: ::awaitPingRouteReady).invoke()

            var notificationUpdateFailed = false
            synchronized(serverLock) {
                if (!isCurrentStartup(generation)) {
                    return
                }
                if (startAsForeground(notificationFor(R.string.pairing_server_notification_running))) {
                    isStartupInFlight = false
                    _serverState.value = ServerState.Running
                } else {
                    notificationUpdateFailed = true
                }
            }
            if (notificationUpdateFailed) {
                if (!cleanupServer(ServerState.Failed)) {
                    stopSelf()
                }
            } else {
                startAdvertiser()
            }
        } catch (error: Exception) {
            if (isCurrentStartup(generation)) {
                AppLogger.e(TAG, "Pairing server failed to start", error)
                if (!cleanupServer(ServerState.Failed)) {
                    stopSelf()
                }
            }
        }
    }

    private fun isCurrentStartup(generation: Long): Boolean = synchronized(serverLock) {
        isCurrentStartupLocked(generation)
    }

    private fun isCurrentStartupLocked(generation: Long): Boolean =
        !isCleanupInFlight &&
        isStartupInFlight &&
        startupGeneration == generation &&
        _serverState.value == ServerState.Starting

    /** The sole serialized cleanup path for STOP, destruction, timeout, and failed startup. */
    private fun cleanupServer(terminalState: ServerState): Boolean {
        clearPendingPairing(PairingDecision.ServiceStopped)
        var serverToStop: EmbeddedServer<*, *>?
        var advertiserToStop: PairingAdvertiser?
        var startupToCancel: Future<*>?
        var startupThreadToCancel: Thread?
        var shouldRestart = false
        synchronized(serverLock) {
            if (isCleanupInFlight) {
                return false
            }
            isCleanupInFlight = true
            isStartupInFlight = false
            startupGeneration += 1
            startupToCancel = startupFuture.also { startupFuture = null }
            startupFutureGeneration = null
            startupThreadToCancel = startupTaskThread
            serverToStop = server.also { server = null }
            advertiserToStop = advertiser.also { advertiser = null }
        }
        if (startupToCancel != null && startupThreadToCancel !== Thread.currentThread()) {
            startupToCancel?.cancel(true)
        }
        try {
            advertiserToStop?.stop()
        } catch (error: Exception) {
            AppLogger.e(TAG, "Pairing advertiser failed to stop", error)
        }
        try {
            serverToStop?.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Pairing server failed to stop", error)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        synchronized(serverLock) {
            _serverState.value = terminalState
            isCleanupInFlight = false
            shouldRestart = pendingStart && !isDestroyed && !isTimeoutTerminated
            pendingStart = false
        }
        if (shouldRestart) {
            startServer()
        }
        return shouldRestart
    }

    private fun setAdvertiserFactoryForTesting(factory: PairingAdvertiserFactory) {
        synchronized(serverLock) {
            if (_serverState.value != ServerState.Stopped || isStartupInFlight || server != null) {
                return
            }
            advertiserFactory = factory
        }
    }

    private fun setReadinessProbeForTesting(probe: (() -> Unit)?) {
        synchronized(serverLock) {
            if (_serverState.value != ServerState.Stopped || isStartupInFlight || server != null) {
                return
            }
            readinessProbeOverride = probe
        }
    }

    private fun setForegroundCheckerForTesting(checker: (() -> Boolean)?) {
        synchronized(serverLock) {
            foregroundChecker = checker ?: ::isApplicationForeground
        }
    }

    private fun setTokenStoreForTesting(store: PairingTokenStore?) {
        synchronized(serverLock) {
            tokenStoreOverride = store
        }
    }

    private fun setImportedAudioRepositoryForTesting(repository: ImportedAudioRepository?) {
        synchronized(serverLock) {
            importedAudioRepositoryOverride = repository
        }
    }

    private fun isApplicationForeground(): Boolean =
        androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private suspend fun handlePairingRequest(call: ApplicationCall) {
        if (!isForeground()) {
            call.respondJson(HttpStatusCode.ServiceUnavailable, errorResponse("app_backgrounded"))
            return
        }
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > PAIRING_REQUEST_MAX_BODY_BYTES) {
            call.respondJson(HttpStatusCode.PayloadTooLarge, errorResponse("bad_request"))
            return
        }
        val body = try {
            call.receiveText()
        } catch (error: Exception) {
            AppLogger.w(TAG, "Pairing request body read failed", error)
            call.respondJson(HttpStatusCode.BadRequest, errorResponse("bad_request"))
            return
        }
        if (body.toByteArray(StandardCharsets.UTF_8).size > PAIRING_REQUEST_MAX_BODY_BYTES) {
            call.respondJson(HttpStatusCode.PayloadTooLarge, errorResponse("bad_request"))
            return
        }
        val payload = try {
            JSONObject(body)
        } catch (error: Exception) {
            call.respondJson(HttpStatusCode.BadRequest, errorResponse("bad_request"))
            return
        }
        val deviceName = payload.optString("deviceName").trim()
        val requestedAt = payload.optLong("requestedAtEpochMillis", 0L)
        if (deviceName.isBlank() || requestedAt <= 0L) {
            call.respondJson(HttpStatusCode.BadRequest, errorResponse("bad_request"))
            return
        }
        val request = PairingRequest(
            requestId = nextPairingRequestId(),
            deviceName = deviceName.take(MAX_DEVICE_NAME_LENGTH),
            remoteHost = call.request.local.remoteHost,
            requestedAtEpochMillis = requestedAt,
        )
        when (val decision = awaitPairingDecision(request)) {
            is PairingDecision.Approved -> {
                call.respondJson(
                    HttpStatusCode.OK,
                    JSONObject().put("token", decision.token),
                )
            }
            PairingDecision.Denied -> call.respondJson(
                HttpStatusCode.Forbidden,
                errorResponse("denied"),
            )
            PairingDecision.TimedOut -> call.respondJson(
                HttpStatusCode.RequestTimeout,
                errorResponse("timeout"),
            )
            PairingDecision.InProgress -> call.respondJson(
                HttpStatusCode.Conflict,
                errorResponse("pairing_in_progress"),
            )
            PairingDecision.Backgrounded -> call.respondJson(
                HttpStatusCode.ServiceUnavailable,
                errorResponse("app_backgrounded"),
            )
            PairingDecision.ServiceStopped -> call.respondJson(
                HttpStatusCode.ServiceUnavailable,
                errorResponse("service_stopped"),
            )
            PairingDecision.StorageFailed -> call.respondJson(
                HttpStatusCode.InternalServerError,
                errorResponse("internal"),
            )
        }
    }

    private suspend fun handleWhoAmI(call: ApplicationCall) {
        if (!isAuthorized(call)) {
            return
        }
        call.respondJson(
            HttpStatusCode.OK,
            JSONObject()
                .put("status", "ok")
                .put("deviceName", Build.MODEL ?: ""),
        )
    }

    private suspend fun isAuthorized(call: ApplicationCall): Boolean {
        val expected = try {
            activeTokenStore().readToken()
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: Exception) {
            AppLogger.e(TAG, "Pairing token read failed", error)
            call.respondJson(HttpStatusCode.InternalServerError, errorResponse(ERROR_INTERNAL))
            return false
        }
        if (bearerTokenMatches(expected, call.request.headers[HttpHeaders.Authorization])) {
            return true
        }
        call.respondJson(HttpStatusCode.Unauthorized, errorResponse(ERROR_UNAUTHORIZED))
        return false
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        // Authenticate before receiveMultipart so an unauthorized caller cannot make the server
        // parse or materialize an upload body.
        if (!isAuthorized(call)) return

        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_UPLOAD_BYTES) {
            call.respondJson(HttpStatusCode.PayloadTooLarge, errorResponse(ERROR_PAYLOAD_TOO_LARGE))
            return
        }

        var uploadSession: ImportedAudioRepository.UploadSession? = null
        val fields = mutableMapOf<String, String>()
        var fileCount = 0
        var badRequest = false
        try {
            try {
                call.receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES).forEachPart { part ->
                    try {
                        when (part) {
                            is PartData.FormItem -> {
                                val name = part.name
                                if (name == null || name !in UPLOAD_TEXT_FIELDS) {
                                    badRequest = true
                                } else if (fields.put(name, part.value) != null) {
                                    badRequest = true
                                }
                            }

                            is PartData.FileItem -> {
                                if (part.name != UPLOAD_FILE_FIELD || fileCount != 0) {
                                    badRequest = true
                                    copyUploadBytes(part.provider(), null)
                                    return@forEachPart
                                }
                                fileCount += 1
                                try {
                                    uploadSession = activeImportedAudioRepository().beginUpload()
                                    uploadSession?.copyTo { output ->
                                        copyUploadBytes(part.provider(), output)
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (tooLarge: UploadTooLargeException) {
                                    throw tooLarge
                                } catch (error: Exception) {
                                    throw UploadStorageFailure(error)
                                }
                            }

                            else -> badRequest = true
                        }
                    } finally {
                        part.dispose()
                    }
                }
            } catch (error: UploadTooLargeException) {
                throw error
            } catch (error: UploadStorageFailure) {
                throw error
            } catch (error: ContentTransformationException) {
                throw error
            } catch (error: BadRequestException) {
                throw error
            } catch (error: IOException) {
                throw UploadMultipartParsingFailure(error)
            }
        } catch (tooLarge: UploadTooLargeException) {
            uploadSession?.abort()
            call.respondJson(HttpStatusCode.PayloadTooLarge, errorResponse(ERROR_PAYLOAD_TOO_LARGE))
            return
        } catch (ce: CancellationException) {
            uploadSession?.abort()
            throw ce
        } catch (error: UploadStorageFailure) {
            uploadSession?.abort()
            AppLogger.e(TAG, "Upload storage or stream failed", error)
            call.respondJson(HttpStatusCode.InternalServerError, errorResponse(ERROR_INTERNAL))
            return
        } catch (error: ContentTransformationException) {
            uploadSession?.abort()
            if (containsUploadTooLarge(error)) {
                call.respondJson(HttpStatusCode.PayloadTooLarge, errorResponse(ERROR_PAYLOAD_TOO_LARGE))
                return
            }
            AppLogger.w(TAG, "Upload multipart validation failed", error)
            call.respondJson(HttpStatusCode.BadRequest, errorResponse(ERROR_BAD_REQUEST))
            return
        } catch (error: BadRequestException) {
            uploadSession?.abort()
            AppLogger.w(TAG, "Upload multipart validation failed", error)
            call.respondJson(HttpStatusCode.BadRequest, errorResponse(ERROR_BAD_REQUEST))
            return
        } catch (error: UploadMultipartParsingFailure) {
            uploadSession?.abort()
            if (containsUploadTooLarge(error)) {
                call.respondJson(HttpStatusCode.PayloadTooLarge, errorResponse(ERROR_PAYLOAD_TOO_LARGE))
                return
            }
            AppLogger.w(TAG, "Upload multipart parser failed", error)
            call.respondJson(HttpStatusCode.BadRequest, errorResponse(ERROR_BAD_REQUEST))
            return
        } catch (error: IllegalArgumentException) {
            uploadSession?.abort()
            AppLogger.w(TAG, "Upload multipart validation failed", error)
            call.respondJson(HttpStatusCode.BadRequest, errorResponse(ERROR_BAD_REQUEST))
            return
        } catch (error: Exception) {
            uploadSession?.abort()
            AppLogger.e(TAG, "Upload multipart processing failed", error)
            call.respondJson(HttpStatusCode.InternalServerError, errorResponse(ERROR_INTERNAL))
            return
        }

        val originalFileName = fields[UPLOAD_ORIGINAL_FILE_NAME_FIELD].orEmpty()
        val sanitizedFileName = ImportedAudioRepository.sanitizeUploadFileName(originalFileName)
        val format = fields[UPLOAD_FORMAT_FIELD].orEmpty()
        val durationHint = fields[UPLOAD_DURATION_MS_FIELD].orEmpty()
        val durationMs = durationHint.toLongOrNull()
        val valid = !badRequest &&
            fileCount == 1 &&
            uploadSession != null &&
            sanitizedFileName.isNotBlank() &&
            format.equals(UPLOAD_FORMAT_WAV, ignoreCase = true) &&
            durationMs != null &&
            durationMs >= 0L
        if (!valid) {
            uploadSession?.abort()
            call.respondJson(HttpStatusCode.BadRequest, errorResponse(ERROR_BAD_REQUEST))
            return
        }

        val record = try {
            uploadSession!!.complete(sanitizedFileName)
        } catch (ce: CancellationException) {
            throw ce
        } catch (error: ImportedAudioMetadataException) {
            AppLogger.e(TAG, "Upload metadata query failed", error)
            call.respondJson(HttpStatusCode.InternalServerError, errorResponse(ERROR_INTERNAL))
            return
        } catch (error: IllegalArgumentException) {
            AppLogger.w(TAG, "Upload WAV validation failed", error)
            call.respondJson(HttpStatusCode.BadRequest, errorResponse(ERROR_BAD_REQUEST))
            return
        } catch (error: Exception) {
            AppLogger.e(TAG, "Upload indexing failed", error)
            call.respondJson(HttpStatusCode.InternalServerError, errorResponse(ERROR_INTERNAL))
            return
        }
        call.respondJson(HttpStatusCode.OK, JSONObject().put("id", record.id))
    }

    private suspend fun copyUploadBytes(
        channel: ByteReadChannel,
        output: java.io.OutputStream?,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            output?.write(buffer, 0, read)
        }
    }

    private suspend fun awaitPairingDecision(request: PairingRequest): PairingDecision {
        if (!isForeground()) return PairingDecision.Backgrounded
        val pending = PendingPairing(
            request = request,
            decision = CompletableDeferred(),
        )
        synchronized(pairingLock) {
            if (pendingPairing != null) return PairingDecision.InProgress
            if (!isForeground()) return PairingDecision.Backgrounded
            pendingPairing = pending
            _pairingRequest.value = request
        }
        return try {
            withTimeoutOrNull(PAIRING_REQUEST_TIMEOUT_MILLIS) {
                pending.decision.await()
            } ?: PairingDecision.TimedOut
        } finally {
            synchronized(pairingLock) {
                if (pendingPairing === pending) {
                    pendingPairing = null
                    _pairingRequest.value = null
                }
            }
        }
    }

    private fun approvePairingRequest(requestId: Long): Boolean {
        val pending = synchronized(pairingLock) {
            val current = pendingPairing ?: return false
            if (current.request.requestId != requestId || current.resolutionStarted) return false
            if (!isForeground()) {
                current.resolutionStarted = true
                current.decision.complete(PairingDecision.Backgrounded)
                return false
            }
            current.resolutionStarted = true
            current
        }
        pairingScope.launch {
            val decision = try {
                val newToken = generatePairingToken()
                activeTokenStore().replaceToken(newToken)
                try {
                    activeTokenStore().storeDeviceName(pending.request.deviceName)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    AppLogger.e(TAG, "Pairing device name storage failed", error)
                    // Roll back: desktop never received the new token, so remove it.
                    try {
                        activeTokenStore().clearToken()
                        activeTokenStore().clearDeviceName()
                    } catch (ce2: kotlinx.coroutines.CancellationException) {
                        throw ce2
                    } catch (rollbackError: Exception) {
                        AppLogger.e(TAG, "Pairing token rollback failed", rollbackError)
                    }
                    pending.decision.complete(PairingDecision.StorageFailed)
                    return@launch
                }
                PairingDecision.Approved(newToken)
            } catch (error: Exception) {
                AppLogger.e(TAG, "Pairing token replacement failed", error)
                PairingDecision.StorageFailed
            }
            pending.decision.complete(decision)
        }
        return true
    }

    private fun rejectPairingRequest(requestId: Long): Boolean {
        synchronized(pairingLock) {
            val current = pendingPairing ?: return false
            if (current.request.requestId != requestId || current.resolutionStarted) return false
            current.resolutionStarted = true
            current.decision.complete(PairingDecision.Denied)
            return true
        }
    }

    private fun clearPendingPairing(decision: PairingDecision) {
        synchronized(pairingLock) {
            pendingPairing?.let { pending ->
                pending.decision.complete(decision)
            }
            pendingPairing = null
            _pairingRequest.value = null
        }
    }

    private fun isForeground(): Boolean = synchronized(serverLock) {
        runCatching { foregroundChecker() }
            .onFailure { error -> AppLogger.w(TAG, "Foreground state check failed", error) }
            .getOrDefault(false)
    }

    private fun nextPairingRequestId(): Long = synchronized(pairingLock) {
        pairingRequestSequence += 1L
        pairingRequestSequence
    }

    private fun activeTokenStore(): PairingTokenStore = tokenStoreOverride ?: tokenStore

    private fun activeImportedAudioRepository(): ImportedAudioRepository =
        importedAudioRepositoryOverride ?: defaultImportedAudioRepository

    private fun markTimeoutTermination() {
        synchronized(serverLock) {
            isTimeoutTerminated = true
            pendingStart = false
        }
    }

    /** Starts NSD only after the Ktor ping route has passed readiness and the service is Running. */
    private fun startAdvertiser() {
        val factory = synchronized(serverLock) {
            if (_serverState.value != ServerState.Running || isCleanupInFlight || advertiser != null) {
                return
            }
            advertiserFactory
        }

        val createdAdvertiser = try {
            factory.create(
                context = applicationContext,
                serviceName = Build.MODEL ?: "",
                servicePort = SERVER_PORT,
            )
        } catch (error: Exception) {
            AppLogger.e(TAG, "Pairing advertiser creation failed; continuing without NSD", error)
            return
        }

        synchronized(serverLock) {
            if (_serverState.value != ServerState.Running || isCleanupInFlight || advertiser != null) {
                try {
                    createdAdvertiser.stop()
                } catch (error: Exception) {
                    AppLogger.w(TAG, "Unused pairing advertiser failed to stop", error)
                }
                return
            }
            advertiser = createdAdvertiser
            try {
                createdAdvertiser.start()
            } catch (error: Exception) {
                advertiser = null
                AppLogger.e(TAG, "Pairing advertiser failed to start; continuing without NSD", error)
                try {
                    createdAdvertiser.stop()
                } catch (stopError: Exception) {
                    AppLogger.w(TAG, "Failed pairing advertiser cleanup after start failure", stopError)
                }
            }
        }
    }

    /** Waits for the real Ktor route before a nonblocking engine start can become Running. */
    @Throws(IOException::class)
    private fun awaitPingRouteReady() {
        var lastFailure: IOException? = null
        repeat(READY_PROBE_ATTEMPTS) {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), SERVER_PORT),
                        READY_PROBE_TIMEOUT_MILLIS,
                    )
                    socket.soTimeout = READY_PROBE_TIMEOUT_MILLIS
                    socket.getOutputStream().apply {
                        write(readinessRequest().toByteArray(StandardCharsets.US_ASCII))
                        flush()
                    }
                    val response = socket.getInputStream()
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                    if (isExpectedPingResponse(response)) {
                        return
                    }
                    lastFailure = IOException("Pairing ping route returned an unexpected response")
                }
            } catch (error: IOException) {
                lastFailure = error
            }

            try {
                Thread.sleep(READY_PROBE_RETRY_DELAY_MILLIS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Pairing server startup was interrupted", error)
            }
        }
        throw IOException("Pairing server did not bind the ping route", lastFailure)
    }

    private fun isExpectedPingResponse(response: String): Boolean {
        val headerEnd = response.indexOf("\r\n\r\n")
        if (headerEnd < 0) {
            return false
        }
        val headers = response.substring(0, headerEnd)
        val hasJsonContentType = headers
            .lineSequence()
            .any { header -> header.trim().equals("Content-Type: application/json", ignoreCase = true) }
        return headers.startsWith("HTTP/1.1 200") &&
            hasJsonContentType &&
            response.substring(headerEnd + 4) == pingResponse()
    }

    private fun readinessRequest(): String = "GET $PING_ROUTE HTTP/1.1\r\n" +
        "Host: 127.0.0.1\r\n" +
        "Connection: close\r\n\r\n"

    private fun startAsForeground(notification: Notification): Boolean {
        return try {
            ensureNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: SecurityException) {
            AppLogger.e(TAG, "Pairing server foreground start was denied", error)
            false
        } catch (error: IllegalStateException) {
            AppLogger.e(TAG, "Pairing server foreground start was rejected", error)
            false
        }
    }

    private fun notificationFor(@StringRes statusRes: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.pairing_server_notification_title))
            .setContentText(getString(statusRes))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.pairing_server_notification_channel))
                .build(),
        )
    }

    private fun pingResponse(): String = JSONObject()
        .put("status", "ok")
        .put("deviceName", Build.MODEL ?: "")
        .toString()

    companion object {
        const val ACTION_START = "com.example.convert2video.desktopsync.action.START"
        const val ACTION_STOP = "com.example.convert2video.desktopsync.action.STOP"
        const val SERVER_PORT = 47_321
        const val PING_ROUTE = "/ping"
        const val PAIR_REQUEST_ROUTE = "/pair/request"
        const val WHO_AM_I_ROUTE = "/whoami"
        const val UPLOAD_ROUTE = com.example.convert2video.desktopsync.UPLOAD_ROUTE
        const val MAX_UPLOAD_BYTES = com.example.convert2video.desktopsync.MAX_UPLOAD_BYTES

        private const val CHANNEL_ID = "desktop_pairing"
        private const val NOTIFICATION_ID = 47_321
        private const val READY_PROBE_ATTEMPTS = 20
        private const val READY_PROBE_RETRY_DELAY_MILLIS = 50L
        private const val READY_PROBE_TIMEOUT_MILLIS = 250
        private const val SERVER_HOST = "0.0.0.0"
        private const val MAX_DEVICE_NAME_LENGTH = 128
        private const val TAG = "PairingServerService"
    }
}

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: JSONObject,
) {
    respondBytes(
        bytes = body.toString().toByteArray(StandardCharsets.UTF_8),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private fun errorResponse(code: String): JSONObject = JSONObject().put("error", code)

private val UPLOAD_TEXT_FIELDS = setOf(
    UPLOAD_ORIGINAL_FILE_NAME_FIELD,
    UPLOAD_FORMAT_FIELD,
    UPLOAD_DURATION_MS_FIELD,
)

private class UploadStorageFailure(cause: Throwable) : IOException(cause)

private class UploadMultipartParsingFailure(cause: Throwable) : IOException(cause)

private fun containsUploadTooLarge(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is UploadTooLargeException) return true
        current = current.cause
    }
    return false
}

internal class UploadTooLargeException : IOException("Upload exceeds the configured file limit")
