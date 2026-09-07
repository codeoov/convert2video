package com.example.convert2video.desktopsync

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Process-scoped UI/controller bridge for the pairing service. */
class DesktopSyncController private constructor(
    private val app: Application,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    private val _pairingRequest = MutableStateFlow<PairingRequest?>(null)
    private val _isPaired = MutableStateFlow(false)
    private val _pairedDeviceName = MutableStateFlow<String?>(null)
    private val tokenStore = AndroidKeystorePairingTokenStore(app)
    private var binder: PairingServerService.LocalBinder? = null
    private var mirrorJob: Job? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = service as? PairingServerService.LocalBinder
            if (local == null) {
                AppLogger.w(TAG, "Unexpected desktop pairing binder")
                return
            }
            binder = local
            isBound = true
            mirrorJob?.cancel()
            mirrorJob = scope.launch {
                launch {
                    local.serverState.collect { _serverState.value = it }
                }
                launch {
                    var prev: PairingRequest? = null
                    local.pairingRequest.collect { req ->
                        val wasNonNull = prev != null
                        _pairingRequest.value = req
                        if (wasNonNull && req == null) {
                            // pairingRequest goes null after Decision.complete() — token is
                            // already persisted at this point, so refresh is safe.
                            launch { refreshPairedState() }
                        }
                        prev = req
                    }
                }
                launch { refreshPairedState() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mirrorJob?.cancel()
            mirrorJob = null
            binder = null
            isBound = false
            _serverState.value = ServerState.Stopped
            _pairingRequest.value = null
        }
    }

    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()
    val pairingRequest: StateFlow<PairingRequest?> = _pairingRequest.asStateFlow()
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()
    val pairedDeviceName: StateFlow<String?> = _pairedDeviceName.asStateFlow()

    init {
        bind()
        scope.launch { refreshPairedState() }
    }

    fun startServer() {
        bind()
        val intent = Intent(app, PairingServerService::class.java)
            .setAction(PairingServerService.ACTION_START)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (error: IllegalStateException) {
            AppLogger.e(TAG, "Desktop pairing foreground start failed", error)
        }
    }

    fun stopServer() {
        app.startService(
            Intent(app, PairingServerService::class.java)
                .setAction(PairingServerService.ACTION_STOP),
        )
    }

    fun approvePairingRequest(requestId: Long): Boolean {
        val approved = binder?.approvePairingRequest(requestId) == true
        if (approved) {
            // The Service persists token+name before completing the Decision (which causes
            // _pairingRequest → null). The null-transition collector above will refresh
            // paired state at that point. This launch is a belt-and-suspenders refresh for
            // any edge case where the collector fires after a disconnect.
            scope.launch { refreshPairedState() }
        }
        return approved
    }

    fun rejectPairingRequest(requestId: Long): Boolean =
        binder?.rejectPairingRequest(requestId) == true

    private suspend fun refreshPairedState() {
        try {
            _isPaired.value = tokenStore.readToken() != null
            _pairedDeviceName.value = tokenStore.readDeviceName()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "refreshPairedState failed", e)
            // leave _isPaired / _pairedDeviceName at their previous values to avoid
            // flashing an incorrect unpaired state on transient I/O errors
        }
    }

    fun unpair() {
        scope.launch {
            try {
                tokenStore.clearToken()
                tokenStore.clearDeviceName()
                _isPaired.value = false
                _pairedDeviceName.value = null
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "Unpair token/name clear failed", e)
                refreshPairedState()
            }
        }
    }

    private fun bind() {
        if (isBound) return
        try {
            isBound = app.bindService(
                Intent(app, PairingServerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!isBound) AppLogger.w(TAG, "Desktop pairing service bind failed")
        } catch (error: Exception) {
            AppLogger.e(TAG, "Desktop pairing service bind failed", error)
        }
    }

    companion object {
        const val TAG = "DesktopSyncController"

        @Volatile
        private var instance: DesktopSyncController? = null

        fun getInstance(application: Application): DesktopSyncController =
            instance ?: synchronized(this) {
                instance ?: DesktopSyncController(application).also { instance = it }
            }

        fun getInstance(context: Context): DesktopSyncController =
            getInstance(context.applicationContext as Application)
    }
}
