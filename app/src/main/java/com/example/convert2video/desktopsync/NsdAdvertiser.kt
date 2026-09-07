package com.example.convert2video.desktopsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.example.convert2video.utils.AppLogger
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** The small service-facing seam keeps the Ktor service independent from Android NSD. */
interface PairingAdvertiser {
    fun start()

    fun stop()
}

fun interface PairingAdvertiserFactory {
    fun create(context: Context, serviceName: String, servicePort: Int): PairingAdvertiser
}

internal interface NsdManagerFacade {
    fun registerService(serviceInfo: NsdServiceInfo, listener: NsdRegistrationListener)

    fun unregisterService(listener: NsdRegistrationListener)
}

internal interface NsdRegistrationListener {
    fun onServiceRegistered(serviceInfo: NsdServiceInfo)

    fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)

    fun onServiceUnregistered(serviceInfo: NsdServiceInfo)

    fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
}

internal interface MulticastLockFacade {
    fun acquire()

    fun release()
}

internal interface NetworkCallbackRegistrar {
    fun register(callback: NetworkChangeCallback): NetworkCallbackRegistration
}

internal interface NetworkCallbackRegistration {
    fun unregister()
}

internal interface NetworkChangeCallback {
    fun onAvailable(network: Any)

    fun onLost(network: Any)
}

internal fun interface RetryHandle {
    fun cancel()
}

internal interface RetryScheduler {
    fun scheduleAt(dueAtMillis: Long, task: () -> Unit): RetryHandle

    fun shutdown()
}

internal fun interface RetrySchedulerFactory {
    fun create(): RetryScheduler
}

internal fun interface AdvertiserClock {
    fun nowMillis(): Long
}

/** Local-LAN transports only; cellular and VPN networks must not trigger NSD registration. */
internal fun localLanNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .build()

/**
 * Advertises the local Phase 1 pairing endpoint. Failure is deliberately local to this class: the
 * Ktor endpoint remains usable when NSD is unavailable, denied, or unsupported by the network.
 */
internal class NsdAdvertiser(
    private val nsdManager: NsdManagerFacade,
    private val multicastLock: MulticastLockFacade,
    private val networkCallbackRegistrar: NetworkCallbackRegistrar,
    private val retrySchedulerFactory: RetrySchedulerFactory,
    private val clock: AdvertiserClock,
    private val serviceName: String,
    private val servicePort: Int,
) : PairingAdvertiser {

    private val stateLock = Any()

    private var isStarted = false
    private var generation = 0L
    private var registrationAttempts = 0
    private var currentNetwork: Any? = null
    private var currentNetworkCallback: NetworkChangeCallback? = null
    private var networkCallbackRegistration: NetworkCallbackRegistration? = null
    private var networkCallbackRetryHandle: RetryHandle? = null
    private var networkCallbackAttempts = 0
    private var networkCallbackEpoch = 0L
    private var isMulticastLockHeld = false
    private var isServiceRegistered = false
    private var currentListener: RegistrationListener? = null
    private var retryHandle: RetryHandle? = null
    private var retryScheduler: RetryScheduler? = null

    override fun start() {
        synchronized(stateLock) {
            if (isStarted) {
                return
            }
            val newRetryScheduler = try {
                retrySchedulerFactory.create()
            } catch (error: Exception) {
                AppLogger.w(TAG, "NSD retry scheduler creation failed; advertising remains stopped", error)
                return
            }
            retryScheduler = newRetryScheduler
            networkCallbackAttempts = 0
            isStarted = true

            acquireMulticastLockLocked()
            registerNetworkCallbackLocked()

            if (isStarted) {
                beginGenerationLocked()
            }
        }
    }

    override fun stop() {
        synchronized(stateLock) {
            if (!isStarted) {
                return
            }
            isStarted = false
            generation += 1
            networkCallbackEpoch += 1
            cancelRetryLocked()
            cancelNetworkCallbackRetryLocked()
            unregisterCurrentServiceLocked()
            unregisterNetworkCallbackLocked()

            releaseMulticastLockLocked()
            shutdownRetrySchedulerLocked()
        }
    }

    private fun onNetworkAvailable(callback: NetworkChangeCallback, network: Any) {
        synchronized(stateLock) {
            if (!isStarted || callback !== currentNetworkCallback || currentNetwork == network) {
                return
            }
            currentNetwork = network
            beginGenerationLocked()
        }
    }

    private fun onNetworkLost(callback: NetworkChangeCallback, network: Any) {
        synchronized(stateLock) {
            if (!isStarted || callback !== currentNetworkCallback || currentNetwork != network) {
                return
            }
            currentNetwork = null
            generation += 1
            cancelRetryLocked()
            unregisterCurrentServiceLocked()
            releaseMulticastLockLocked()
        }
    }

    private fun beginGenerationLocked() {
        if (retryScheduler == null) {
            retryScheduler = try {
                retrySchedulerFactory.create()
            } catch (error: Exception) {
                AppLogger.w(TAG, "NSD retry scheduler creation failed during recovery", error)
                return
            }
        }
        generation += 1
        registrationAttempts = 0
        cancelRetryLocked()
        unregisterCurrentServiceLocked()
        acquireMulticastLockLocked()
        registerAttemptLocked(generation)
    }

    private fun registerAttemptLocked(attemptGeneration: Long) {
        if (!isStarted || generation != attemptGeneration) {
            return
        }

        registrationAttempts += 1
        val listener = RegistrationListener(attemptGeneration)
        currentListener = listener
        try {
            nsdManager.registerService(serviceInfo(), listener)
        } catch (error: Exception) {
            if (!listener.hasCompletedAttempt) {
                listener.fail(error)
            }
        }
    }

    private fun onRegistrationSucceeded(listener: RegistrationListener, registeredInfo: NsdServiceInfo) {
        synchronized(stateLock) {
            if (!isCurrentListener(listener) || listener.hasCompletedAttempt) {
                return
            }
            listener.hasCompletedAttempt = true
            listener.isPlatformRegistrationActive = true
            isServiceRegistered = true
            cancelRetryLocked()
            AppLogger.d(TAG, "NSD service registered: ${registeredInfo.serviceName}")
        }
    }

    private fun onRegistrationFailed(listener: RegistrationListener, errorCode: Int, error: Exception?) {
        synchronized(stateLock) {
            if (!isCurrentListener(listener) || listener.hasCompletedAttempt) {
                return
            }
            listener.hasCompletedAttempt = true
            listener.isPlatformRegistrationActive = false
            if (error != null) {
                AppLogger.w(TAG, "NSD registration attempt failed synchronously", error)
            } else {
                AppLogger.w(TAG, "NSD registration attempt failed: $errorCode")
            }

            if (registrationAttempts >= MAX_REGISTRATION_ATTEMPTS) {
                cleanupAfterFailureLocked()
            } else {
                val expectedGeneration = generation
                val failedListener = listener
                val retryAtMillis = clock.nowMillis() + RETRY_DELAY_MILLIS
                retryHandle = try {
                    retrySchedulerOrThrow().scheduleAt(retryAtMillis) {
                        synchronized(stateLock) {
                            if (!isStarted ||
                                generation != expectedGeneration ||
                                currentListener !== failedListener ||
                                isServiceRegistered
                            ) {
                                return@scheduleAt
                            }
                            retryHandle = null
                            registerAttemptLocked(expectedGeneration)
                        }
                    }
                } catch (scheduleError: Exception) {
                    AppLogger.w(TAG, "NSD retry scheduling failed", scheduleError)
                    cleanupAfterFailureLocked()
                    null
                }
            }
        }
    }

    private fun cleanupAfterFailureLocked() {
        generation += 1
        cancelRetryLocked()
        unregisterCurrentServiceLocked()
        currentNetwork = null
        releaseMulticastLockLocked()
        shutdownRetrySchedulerIfIdleLocked()
    }

    private fun onUnexpectedUnregistration(
        listener: RegistrationListener,
        errorCode: Int?,
    ) {
        synchronized(stateLock) {
            if (!isCurrentListener(listener) ||
                listener.isCleanupRequested ||
                !listener.isPlatformRegistrationActive
            ) {
                return
            }
            listener.isPlatformRegistrationActive = false
            isServiceRegistered = false
            if (errorCode == null) {
                AppLogger.w(TAG, "NSD service was unregistered unexpectedly")
            } else {
                AppLogger.w(TAG, "NSD service unregistration failed: $errorCode")
            }
            beginGenerationLocked()
        }
    }

    private fun registerNetworkCallbackLocked() {
        if (!isStarted || networkCallbackRegistration != null) {
            return
        }
        networkCallbackAttempts += 1
        val expectedEpoch = networkCallbackEpoch
        val callback = createNetworkCallback()
        currentNetworkCallback = callback
        try {
            val registration = networkCallbackRegistrar.register(callback)
            if (!isStarted ||
                expectedEpoch != networkCallbackEpoch ||
                currentNetworkCallback !== callback
            ) {
                registration.unregister()
                return
            }
            networkCallbackRegistration = registration
            networkCallbackAttempts = 0
            cancelNetworkCallbackRetryLocked()
        } catch (error: Exception) {
            if (currentNetworkCallback === callback) {
                currentNetworkCallback = null
            }
            AppLogger.w(TAG, "NSD network callback registration failed", error)
            if (networkCallbackAttempts < MAX_NETWORK_CALLBACK_ATTEMPTS) {
                val retryAtMillis = clock.nowMillis() + RETRY_DELAY_MILLIS
                networkCallbackRetryHandle = try {
                    retrySchedulerOrThrow().scheduleAt(retryAtMillis) {
                        synchronized(stateLock) {
                            if (!isStarted ||
                                expectedEpoch != networkCallbackEpoch ||
                                networkCallbackRegistration != null
                            ) {
                                return@scheduleAt
                            }
                            networkCallbackRetryHandle = null
                            registerNetworkCallbackLocked()
                        }
                    }
                } catch (scheduleError: Exception) {
                    AppLogger.w(TAG, "NSD network callback retry scheduling failed", scheduleError)
                    null
                }
            } else {
                shutdownRetrySchedulerIfIdleLocked()
            }
        }
    }

    private fun createNetworkCallback(): NetworkChangeCallback = object : NetworkChangeCallback {
        override fun onAvailable(network: Any) {
            onNetworkAvailable(this, network)
        }

        override fun onLost(network: Any) {
            onNetworkLost(this, network)
        }
    }

    private fun isCurrentListener(listener: RegistrationListener): Boolean =
        isStarted && currentListener === listener && listener.generation == generation

    private fun cancelRetryLocked() {
        retryHandle?.let { handle ->
            try {
                handle.cancel()
            } catch (error: Exception) {
                AppLogger.w(TAG, "NSD retry cancellation failed", error)
            }
        }
        retryHandle = null
    }

    private fun cancelNetworkCallbackRetryLocked() {
        networkCallbackRetryHandle?.let { handle ->
            try {
                handle.cancel()
            } catch (error: Exception) {
                AppLogger.w(TAG, "NSD network callback retry cancellation failed", error)
            }
        }
        networkCallbackRetryHandle = null
    }

    private fun unregisterCurrentServiceLocked() {
        val listener = currentListener
        currentListener = null
        if (listener == null) {
            isServiceRegistered = false
            return
        }
        isServiceRegistered = false
        listener.isCleanupRequested = true
        if (!listener.isPlatformRegistrationActive) {
            return
        }
        listener.isPlatformRegistrationActive = false
        try {
            nsdManager.unregisterService(listener)
        } catch (error: Exception) {
            AppLogger.w(TAG, "NSD service unregistration failed", error)
        }
    }

    private fun unregisterNetworkCallbackLocked() {
        val registration = networkCallbackRegistration
        networkCallbackRegistration = null
        currentNetworkCallback = null
        if (registration == null) {
            return
        }
        try {
            registration.unregister()
        } catch (error: Exception) {
            AppLogger.w(TAG, "NSD network callback unregistration failed", error)
        }
    }

    private fun acquireMulticastLockLocked() {
        if (!isStarted || isMulticastLockHeld) {
            return
        }
        try {
            multicastLock.acquire()
            isMulticastLockHeld = true
        } catch (error: Exception) {
            AppLogger.w(TAG, "NSD multicast lock acquisition failed; continuing without it", error)
        }
    }

    private fun releaseMulticastLockLocked() {
        if (!isMulticastLockHeld) {
            return
        }
        try {
            multicastLock.release()
        } catch (error: Exception) {
            AppLogger.w(TAG, "NSD multicast lock release failed", error)
        }
        isMulticastLockHeld = false
    }

    private fun shutdownRetrySchedulerLocked() {
        retryScheduler?.let { scheduler ->
            try {
                scheduler.shutdown()
            } catch (error: Exception) {
                AppLogger.w(TAG, "NSD retry scheduler shutdown failed", error)
            }
        }
        retryScheduler = null
    }

    private fun shutdownRetrySchedulerIfIdleLocked() {
        if (networkCallbackRetryHandle == null) {
            shutdownRetrySchedulerLocked()
        }
    }

    private fun retrySchedulerOrThrow(): RetryScheduler =
        retryScheduler ?: error("NSD retry scheduler is not active")

    private fun serviceInfo(): NsdServiceInfo = NsdServiceInfo().apply {
        serviceName = this@NsdAdvertiser.serviceName
        serviceType = SERVICE_TYPE
        port = servicePort
    }

    private inner class RegistrationListener(
        val generation: Long,
    ) : NsdRegistrationListener {
        var hasCompletedAttempt = false
        var isPlatformRegistrationActive = true
        var isCleanupRequested = false

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            onRegistrationSucceeded(this, serviceInfo)
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            onRegistrationFailed(this, errorCode, null)
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            onUnexpectedUnregistration(this, null)
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            onUnexpectedUnregistration(this, errorCode)
        }

        fun fail(error: Exception) {
            onRegistrationFailed(this, SYNCHRONOUS_FAILURE_CODE, error)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_c2vsync._tcp"
        const val MAX_REGISTRATION_ATTEMPTS = 3
        const val MAX_NETWORK_CALLBACK_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 5_000L

        private const val SYNCHRONOUS_FAILURE_CODE = -1
        private const val TAG = "NsdAdvertiser"
    }
}

internal val DefaultPairingAdvertiserFactory = PairingAdvertiserFactory { context, serviceName, servicePort ->
    val appContext = context.applicationContext
    val nsdManager = appContext.getSystemService(NsdManager::class.java)
        ?: error("NsdManager is unavailable")
    val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: error("WifiManager is unavailable")

    NsdAdvertiser(
        nsdManager = AndroidNsdManagerFacade(nsdManager),
        multicastLock = AndroidMulticastLockFacade(
            wifiManager.createMulticastLock("convert2video-nsd"),
        ),
        networkCallbackRegistrar = AndroidNetworkCallbackRegistrar(
            appContext.getSystemService(ConnectivityManager::class.java)
                ?: error("ConnectivityManager is unavailable"),
        ),
        retrySchedulerFactory = RetrySchedulerFactory { ExecutorRetryScheduler() },
        clock = AdvertiserClock(SystemClock::elapsedRealtime),
        serviceName = serviceName,
        servicePort = servicePort,
    )
}

private class AndroidNsdManagerFacade(
    private val nsdManager: NsdManager,
) : NsdManagerFacade {
    private val listeners = Collections.synchronizedMap(
        mutableMapOf<NsdRegistrationListener, NsdManager.RegistrationListener>(),
    )

    override fun registerService(serviceInfo: NsdServiceInfo, listener: NsdRegistrationListener) {
        val didRegister = AtomicBoolean(false)
        val didComplete = AtomicBoolean(false)
        val platformListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                didRegister.set(true)
                didComplete.set(true)
                listener.onServiceRegistered(registeredInfo)
            }

            override fun onRegistrationFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                didComplete.set(true)
                listeners.remove(listener)
                listener.onRegistrationFailed(failedInfo, errorCode)
            }

            override fun onServiceUnregistered(unregisteredInfo: NsdServiceInfo) {
                listeners.remove(listener)
                listener.onServiceUnregistered(unregisteredInfo)
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                listeners.remove(listener)
                listener.onUnregistrationFailed(info, errorCode)
            }
        }
        listeners[listener] = platformListener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, platformListener)
        } catch (error: Exception) {
            if (!didRegister.get() && !didComplete.get()) {
                listeners.remove(listener)
            }
            throw error
        }
    }

    override fun unregisterService(listener: NsdRegistrationListener) {
        val platformListener = listeners.remove(listener) ?: return
        nsdManager.unregisterService(platformListener)
    }
}

private class AndroidMulticastLockFacade(
    private val lock: WifiManager.MulticastLock,
) : MulticastLockFacade {
    init {
        lock.setReferenceCounted(false)
    }

    override fun acquire() = lock.acquire()

    override fun release() = lock.release()
}

private class AndroidNetworkCallbackRegistrar(
    private val connectivityManager: ConnectivityManager,
) : NetworkCallbackRegistrar {
    private var platformCallback: ConnectivityManager.NetworkCallback? = null

    override fun register(callback: NetworkChangeCallback): NetworkCallbackRegistration {
        val registeredCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                callback.onAvailable(network)
            }

            override fun onLost(network: Network) {
                callback.onLost(network)
            }
        }
        platformCallback = registeredCallback
        try {
            connectivityManager.registerNetworkCallback(localLanNetworkRequest(), registeredCallback)
        } catch (error: Exception) {
            if (platformCallback === registeredCallback) {
                platformCallback = null
            }
            throw error
        }
        return object : NetworkCallbackRegistration {
            private var isUnregistered = false

            override fun unregister() {
                if (isUnregistered) {
                    return
                }
                isUnregistered = true
                if (platformCallback !== registeredCallback) {
                    return
                }
                try {
                    connectivityManager.unregisterNetworkCallback(registeredCallback)
                } finally {
                    if (platformCallback === registeredCallback) {
                        platformCallback = null
                    }
                }
            }
        }
    }
}

private class ExecutorRetryScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NsdAdvertiserRetry")
    },
    private val clock: AdvertiserClock = AdvertiserClock(SystemClock::elapsedRealtime),
) : RetryScheduler {
    override fun scheduleAt(dueAtMillis: Long, task: () -> Unit): RetryHandle {
        val delayMillis = max(0L, dueAtMillis - clock.nowMillis())
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return RetryHandle { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
