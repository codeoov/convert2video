package com.example.convert2video.desktopsync

import android.net.nsd.NsdServiceInfo
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NsdAdvertiserTest {

    @Test
    fun success_localLanNetworkRequest_excludesCellularAndVpn() {
        // Given / When
        val request = localLanNetworkRequest()

        // Then
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test
    fun success_startAndStopAreIdempotent_registersExpectedServiceAndReleasesOnce() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()
        advertiser.start()
        fixture.nsd.succeedCurrent()
        advertiser.stop()
        advertiser.stop()

        // Then
        assertEquals(1, fixture.nsd.registrations.size)
        assertEquals("Pixel", fixture.nsd.registrations.single().serviceInfo.serviceName)
        assertEquals(NsdAdvertiser.SERVICE_TYPE, fixture.nsd.registrations.single().serviceInfo.serviceType)
        assertEquals(47_321, fixture.nsd.registrations.single().serviceInfo.port)
        assertEquals(1, fixture.lock.acquireCount)
        assertEquals(1, fixture.lock.releaseCount)
        assertEquals(1, fixture.network.unregisterCount)
        assertEquals(1, fixture.nsd.unregisterCount)
    }

    @Test
    fun failure_registrationRetriesExactlyTwiceAfterExactlyFiveSeconds_thenCleansUp() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()
        fixture.nsd.failCurrent()

        // Then
        assertEquals(listOf(5_000L), fixture.scheduler.scheduledDueTimes())
        fixture.scheduler.runNext()
        fixture.nsd.failCurrent()
        assertEquals(listOf(5_000L, 5_000L), fixture.scheduler.scheduledDueTimes())
        fixture.scheduler.runNext()
        fixture.nsd.failCurrent()

        // Then
        assertEquals(3, fixture.nsd.registrations.size)
        assertEquals(2, fixture.scheduler.scheduleCount)
        assertEquals(1, fixture.lock.releaseCount)
        assertEquals(0, fixture.network.unregisterCount)
        assertTrue(fixture.scheduler.isShutdown)
        advertiser.stop()
        assertEquals(1, fixture.network.unregisterCount)
    }

    @Test
    fun success_stopThenStartCreatesFreshScheduler_andRetriesAgain() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        fixture.nsd.succeedCurrent()

        // When
        advertiser.stop()
        advertiser.start()
        fixture.nsd.failCurrent()

        // Then
        assertEquals(2, fixture.schedulers.size)
        assertTrue(fixture.schedulers.first().isShutdown)
        assertEquals(listOf(5_000L), fixture.scheduler.scheduledDueTimes())
        fixture.scheduler.runNext()
        assertEquals(3, fixture.nsd.registrations.size)
        advertiser.stop()
    }

    @Test
    fun exception_retrySchedulerFactoryFailure_isTransactional_andLaterStartCanRetry() {
        // Given
        val fixture = Fixture()
        fixture.schedulerFactoryFailuresRemaining = 1
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()
        advertiser.start()

        // Then
        assertEquals(1, fixture.schedulers.size)
        assertEquals(1, fixture.nsd.registrations.size)
        assertEquals(1, fixture.network.registerCount)
        assertEquals(1, fixture.lock.acquireCount)
        advertiser.stop()
    }

    @Test
    fun exception_synchronousRegisterFailureCountsAsAttempt_andStillStopsAfterThirdAttempt() {
        // Given
        val fixture = Fixture()
        fixture.nsd.synchronousFailuresRemaining = 3
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()
        fixture.scheduler.runNext()
        fixture.scheduler.runNext()

        // Then
        assertEquals(3, fixture.nsd.registrations.size)
        assertEquals(2, fixture.scheduler.scheduleCount)
        assertEquals(1, fixture.lock.releaseCount)
        assertTrue(fixture.scheduler.isShutdown)
    }

    @Test
    fun success_registrationCancelsPendingRetry_andLateTimerDoesNotRegisterAgain() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        fixture.nsd.failCurrent()

        // When
        fixture.network.available(Any())
        fixture.nsd.succeedCurrent()
        fixture.scheduler.runCancelled()

        // Then
        assertEquals(2, fixture.nsd.registrations.size)
        assertEquals(1, fixture.scheduler.cancelCount)
        advertiser.stop()
    }

    @Test
    fun success_duplicateFailureCallbacksScheduleOnlyOneRetry_andHandleRemainsCancellable() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()

        // When
        fixture.nsd.failCurrent()
        fixture.nsd.failCurrent()

        // Then
        assertEquals(1, fixture.scheduler.scheduleCount)
        fixture.scheduler.runNext()
        fixture.nsd.failCurrent()
        fixture.nsd.failCurrent()
        assertEquals(2, fixture.scheduler.scheduleCount)
        advertiser.stop()
        assertEquals(1, fixture.scheduler.cancelCount)
    }

    @Test
    fun success_unexpectedServiceUnregistered_recoversOnlyWhileCurrentGenerationIsActive() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        fixture.nsd.succeedCurrent()
        val oldListener = fixture.nsd.registrations.last().listener

        // When
        oldListener.onServiceUnregistered(fixture.nsd.registrations.last().serviceInfo)

        // Then
        assertEquals(2, fixture.nsd.registrations.size)
        assertEquals(0, fixture.nsd.unregisterCount)
        fixture.nsd.succeedCurrent()
        advertiser.stop()
        assertEquals(1, fixture.nsd.unregisterCount)
    }

    @Test
    fun success_unexpectedUnregistrationFailure_recovers_andOwnedCleanupDoesNotResurrect() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        fixture.nsd.succeedCurrent()
        val oldListener = fixture.nsd.registrations.last().listener

        // When
        oldListener.onUnregistrationFailed(fixture.nsd.registrations.last().serviceInfo, 7)
        val recoveredListener = fixture.nsd.registrations.last().listener
        advertiser.stop()
        recoveredListener.onServiceUnregistered(NsdServiceInfo())

        // Then
        assertEquals(2, fixture.nsd.registrations.size)
        assertEquals(1, fixture.nsd.unregisterCount)
        assertEquals(1, fixture.lock.releaseCount)
    }

    @Test
    fun success_ownedUnregisterCallbackIsIgnored_andDoesNotCreateAnotherGeneration() {
        // Given
        val fixture = Fixture()
        fixture.nsd.callbackOnUnregister = true
        val advertiser = fixture.advertiser()
        advertiser.start()
        fixture.nsd.succeedCurrent()

        // When
        advertiser.stop()

        // Then
        assertEquals(1, fixture.nsd.registrations.size)
        assertEquals(1, fixture.nsd.unregisterCount)
        assertEquals(1, fixture.lock.releaseCount)
    }

    @Test
    fun success_stopUnregistersPendingListener_andLateFailureDoesNotLeaveRegistrationEntry() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        val pendingListener = fixture.nsd.registrations.single().listener

        // When
        advertiser.stop()
        pendingListener.onRegistrationFailed(NsdServiceInfo(), 2)

        // Then
        assertEquals(1, fixture.nsd.unregisterCount)
        assertEquals(1, fixture.nsd.registrations.size)
        assertTrue(fixture.nsd.activeListeners.isEmpty())
        assertFalse(fixture.scheduler.hasRunnableTasks())
    }

    @Test
    fun exception_networkCallbackRegistrationFailure_isTransactionalAndCleanupSafe() {
        // Given
        val fixture = Fixture()
        fixture.network.failRegistration = true
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()
        advertiser.stop()

        // Then
        assertEquals(1, fixture.network.registerCount)
        assertEquals(0, fixture.network.unregisterCount)
        assertEquals(1, fixture.lock.releaseCount)
        assertFalse(fixture.network.hasRetainedCallback)
    }

    @Test
    fun success_networkCallbackRegistrationRetriesWithBoundedFiveSecondDelay_andStopCancelsRecovery() {
        // Given
        val fixture = Fixture()
        fixture.network.registrationFailuresRemaining = 2
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()

        // Then
        assertEquals(listOf(5_000L), fixture.scheduler.scheduledDueTimes())
        fixture.scheduler.runNext()
        assertEquals(listOf(5_000L, 5_000L), fixture.scheduler.scheduledDueTimes())
        fixture.scheduler.runNext()
        assertEquals(3, fixture.network.registerCount)
        advertiser.stop()
        assertEquals(1, fixture.network.unregisterCount)
    }

    @Test
    fun success_stopCancelsBoundedNetworkCallbackRecovery_andLateRetryCannotRegister() {
        // Given
        val fixture = Fixture()
        fixture.network.registrationFailuresRemaining = NsdAdvertiser.MAX_NETWORK_CALLBACK_ATTEMPTS
        val advertiser = fixture.advertiser()
        advertiser.start()

        // When
        advertiser.stop()
        fixture.scheduler.runAll()

        // Then
        assertEquals(1, fixture.network.registerCount)
        assertFalse(fixture.scheduler.hasRunnableTasks())
    }

    @Test
    fun exception_nsdCallbackBeforeRegisterThrows_keepsCleanupStateConsistent() {
        // Given
        val fixture = Fixture()
        fixture.nsd.callbackBeforeThrow = true
        val advertiser = fixture.advertiser()

        // When
        advertiser.start()
        advertiser.stop()

        // Then
        assertEquals(1, fixture.nsd.registrations.size)
        assertEquals(1, fixture.nsd.unregisterCount)
        assertTrue(fixture.nsd.activeListeners.isEmpty())
        assertEquals(0, fixture.scheduler.scheduleCount)
    }

    @Test
    fun success_networkChangeInvalidatesOldGeneration_andRegistersOncePerNewNetwork() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        val firstNetwork = Any()
        val secondNetwork = Any()
        advertiser.start()
        fixture.nsd.succeedCurrent()

        // When
        fixture.network.available(firstNetwork)
        fixture.network.available(firstNetwork)
        fixture.nsd.succeedCurrent()
        fixture.network.available(secondNetwork)
        fixture.nsd.succeedCurrent()

        // Then
        assertEquals(3, fixture.nsd.registrations.size)
        assertEquals(2, fixture.nsd.unregisterCount)
        advertiser.stop()
        assertEquals(3, fixture.nsd.unregisterCount)
    }

    @Test
    fun success_staleNetworkCallbackCannotInvalidateCurrentRegistrationOrUnregisterCurrentToken() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        advertiser.stop()
        advertiser.start()
        val registrationCount = fixture.nsd.registrations.size

        // When
        fixture.network.staleAvailable(0, Any())

        // Then
        assertEquals(registrationCount, fixture.nsd.registrations.size)
        advertiser.stop()
        assertEquals(2, fixture.network.unregisterCount)
    }

    @Test
    fun success_finalFailureRetainsNetworkMonitoring_andLaterNetworkCanRecoverAdvertising() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        val recoveredNetwork = Any()
        advertiser.start()
        fixture.nsd.failCurrent()
        fixture.scheduler.runNext()
        fixture.nsd.failCurrent()
        fixture.scheduler.runNext()
        fixture.nsd.failCurrent()

        // When
        fixture.network.available(recoveredNetwork)

        // Then
        assertEquals(4, fixture.nsd.registrations.size)
        assertEquals(2, fixture.lock.acquireCount)
        fixture.nsd.succeedCurrent()
        advertiser.stop()
        assertEquals(1, fixture.network.unregisterCount)
    }

    @Test
    fun success_stopInvalidatesGeneration_andLateCallbacksCannotResurrectAdvertising() {
        // Given
        val fixture = Fixture()
        val advertiser = fixture.advertiser()
        advertiser.start()
        val oldListener = fixture.nsd.registrations.single().listener

        // When
        advertiser.stop()
        oldListener.onServiceRegistered(NsdServiceInfo())
        oldListener.onRegistrationFailed(NsdServiceInfo(), 1)
        fixture.scheduler.runAll()

        // Then
        assertEquals(1, fixture.nsd.registrations.size)
        assertEquals(1, fixture.lock.releaseCount)
        assertFalse(fixture.scheduler.hasRunnableTasks())
    }

    private class Fixture {
        val nsd = FakeNsdManager()
        val lock = FakeMulticastLock()
        val network = FakeNetworkCallbackRegistrar()
        val clock = FakeClock()
        val schedulers = mutableListOf<FakeRetryScheduler>()
        var schedulerFactoryFailuresRemaining = 0
        val scheduler: FakeRetryScheduler
            get() = schedulers.last()

        fun advertiser(): NsdAdvertiser = NsdAdvertiser(
            nsdManager = nsd,
            multicastLock = lock,
            networkCallbackRegistrar = network,
            retrySchedulerFactory = RetrySchedulerFactory {
                if (schedulerFactoryFailuresRemaining > 0) {
                    schedulerFactoryFailuresRemaining -= 1
                    throw IllegalStateException("fake scheduler factory failure")
                }
                FakeRetryScheduler().also(schedulers::add)
            },
            clock = clock,
            serviceName = "Pixel",
            servicePort = PairingServerService.SERVER_PORT,
        )
    }

    private class FakeNsdManager : NsdManagerFacade {
        data class Registration(
            val serviceInfo: NsdServiceInfo,
            val listener: NsdRegistrationListener,
        )

        val registrations = mutableListOf<Registration>()
        val activeListeners = mutableSetOf<NsdRegistrationListener>()
        var synchronousFailuresRemaining = 0
        var callbackBeforeThrow = false
        var callbackOnUnregister = false
        var unregisterCount = 0
            private set

        override fun registerService(serviceInfo: NsdServiceInfo, listener: NsdRegistrationListener) {
            registrations += Registration(serviceInfo, listener)
            activeListeners += listener
            if (callbackBeforeThrow) {
                listener.onServiceRegistered(serviceInfo)
                throw IllegalStateException("fake callback-before-throw")
            }
            if (synchronousFailuresRemaining > 0) {
                synchronousFailuresRemaining -= 1
                activeListeners -= listener
                throw IllegalStateException("fake synchronous register failure")
            }
        }

        override fun unregisterService(listener: NsdRegistrationListener) {
            activeListeners -= listener
            unregisterCount += 1
            if (callbackOnUnregister) {
                listener.onServiceUnregistered(NsdServiceInfo())
            }
        }

        fun succeedCurrent() {
            registrations.last().listener.onServiceRegistered(registrations.last().serviceInfo)
        }

        fun failCurrent() {
            val registration = registrations.last()
            activeListeners -= registration.listener
            registration.listener.onRegistrationFailed(registration.serviceInfo, 2)
        }
    }

    private class FakeMulticastLock : MulticastLockFacade {
        var acquireCount = 0
            private set
        var releaseCount = 0
            private set

        override fun acquire() {
            acquireCount += 1
        }

        override fun release() {
            releaseCount += 1
        }
    }

    private class FakeNetworkCallbackRegistrar : NetworkCallbackRegistrar {
        private val callbacks = mutableListOf<NetworkChangeCallback>()
        private var currentCallback: NetworkChangeCallback? = null
        var registerCount = 0
            private set
        var unregisterCount = 0
            private set
        var failRegistration = false
        var registrationFailuresRemaining = 0
        var hasRetainedCallback = false
            private set

        override fun register(callback: NetworkChangeCallback): NetworkCallbackRegistration {
            callbacks += callback
            currentCallback = callback
            registerCount += 1
            val shouldFail = failRegistration || registrationFailuresRemaining > 0
            if (registrationFailuresRemaining > 0) {
                registrationFailuresRemaining -= 1
            }
            hasRetainedCallback = !shouldFail
            if (shouldFail) {
                currentCallback = null
                hasRetainedCallback = false
                throw IllegalStateException("fake network callback registration failure")
            }
            hasRetainedCallback = true
            return object : NetworkCallbackRegistration {
                private var isUnregistered = false

                override fun unregister() {
                    if (isUnregistered) {
                        return
                    }
                    isUnregistered = true
                    unregisterCount += 1
                    if (currentCallback === callback) {
                        currentCallback = null
                        hasRetainedCallback = false
                    }
                }
            }
        }

        fun available(network: Any) = currentCallback?.onAvailable(network)

        fun staleAvailable(index: Int, network: Any) = callbacks[index].onAvailable(network)
    }

    private class FakeRetryScheduler : RetryScheduler {
        private data class Task(
            val dueAtMillis: Long,
            val action: () -> Unit,
            var isCancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()
        var scheduleCount = 0
            private set
        var cancelCount = 0
            private set
        var isShutdown = false
            private set

        override fun scheduleAt(dueAtMillis: Long, task: () -> Unit): RetryHandle {
            val scheduledTask = Task(dueAtMillis, task)
            tasks += scheduledTask
            scheduleCount += 1
            return RetryHandle {
                if (!scheduledTask.isCancelled) {
                    scheduledTask.isCancelled = true
                    cancelCount += 1
                }
            }
        }

        override fun shutdown() {
            isShutdown = true
        }

        fun scheduledDueTimes(): List<Long> = tasks.map { it.dueAtMillis }

        fun runNext() {
            tasks.first { !it.isCancelled }.also { it.action() }.isCancelled = true
        }

        fun runCancelled() {
            tasks.filter { it.isCancelled }.forEach { it.action() }
        }

        fun runAll() {
            tasks.filter { !it.isCancelled }.forEach { it.action() }
        }

        fun hasRunnableTasks(): Boolean = tasks.any { !it.isCancelled }
    }

    private class FakeClock : AdvertiserClock {
        override fun nowMillis(): Long = 0L
    }
}
