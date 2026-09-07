package com.example.convert2video.ui.screens.options

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.example.convert2video.billing.BillingFailureKind
import com.example.convert2video.billing.BillingOperationId
import com.example.convert2video.billing.BillingGateway
import com.example.convert2video.billing.BillingGatewayResult
import com.example.convert2video.billing.EntitlementApiResult
import com.example.convert2video.billing.EntitlementRepository
import com.example.convert2video.billing.EntitlementStore
import com.example.convert2video.billing.EntitlementFailureKind
import com.example.convert2video.billing.OwnedPurchase
import com.example.convert2video.billing.PendingPurchase
import com.example.convert2video.billing.ProductDetails
import com.example.convert2video.billing.Snapshot
import com.example.convert2video.billing.PRO_PRODUCT_ID
import com.example.convert2video.billing.PurchaseCompletionResult
import com.example.convert2video.billing.PurchaseResult
import com.example.convert2video.billing.PurchaseState
import com.example.convert2video.billing.RestoreObservation
import com.example.convert2video.billing.RestoreResult
import com.example.convert2video.billing.restoreProStatusFor
import com.example.convert2video.billing.restoreFailureObservationFor
import com.example.convert2video.billing.restoreResultFor
import com.example.convert2video.store.StoreCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.activity.result.IntentSenderRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout

private fun successfulVerifyResult() = EntitlementApiResult.Success(
    jwt = "verified-test-jwt",
    expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600L,
)

private class OptionsBillingTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(application = OptionsBillingTestApplication::class, sdk = [34])
class OptionsViewModelBillingTest {

    private val repositories = mutableListOf<EntitlementRepository>()
    private val verifiers = mutableListOf<TestEntitlementVerifier>()

    @After
    fun tearDown() {
        verifiers.forEach(TestEntitlementVerifier::closeForTest)
        repositories.forEach(EntitlementRepository::closeForTest)
        EntitlementRepository.closeInstanceForTest()
        verifiers.clear()
        repositories.clear()
    }

    @Test
    fun success_activityResultMappingKeepsPendingPurchasedCancelledAndFailedDistinct() {
        // Given
        val purchase = OwnedPurchase(
            store = StoreCapabilities.GOOGLE_PLAY_STORE_ID,
            productId = PRO_PRODUCT_ID,
            purchaseToken = "token",
            state = PurchaseState.Purchased,
            isAcknowledged = true,
        )

        // When / Then
        assertEquals(
            BillingPurchaseUiState.Pending,
            billingPurchaseUiStateFor(
                PurchaseResult.Pending(purchase.copy(state = PurchaseState.Pending)),
            ),
        )
        assertEquals(
            BillingPurchaseUiState.Preparing,
            billingPurchaseUiStateFor(PurchaseResult.Purchased(purchase)),
        )
        assertEquals(
            BillingPurchaseUiState.Cancelled,
            billingPurchaseUiStateFor(PurchaseResult.UserCancelled),
        )
        assertEquals(
            BillingPurchaseUiState.Cancelled,
            billingPurchaseUiStateFor(PurchaseResult.Cancelled),
        )
        assertEquals(
            BillingPurchaseUiState.Failed(BillingFailureKind.ProviderError),
            billingPurchaseUiStateFor(PurchaseResult.Duplicate),
        )
        assertEquals(
            BillingPurchaseUiState.Failed(BillingFailureKind.InvalidResponse),
            billingPurchaseUiStateFor(PurchaseResult.Malformed),
        )
        assertEquals(
            BillingPurchaseUiState.Failed(BillingFailureKind.InvalidResponse),
            billingPurchaseUiStateFor(PurchaseResult.ProductMismatch),
        )
        assertEquals(
            BillingPurchaseUiState.Failed(BillingFailureKind.InvalidResponse),
            billingPurchaseUiStateFor(PurchaseResult.Failed(BillingFailureKind.InvalidResponse)),
        )
    }

    @Test
    fun success_purchaseCompletionUiState_purchasedOnlyRepresentsVerifiedCommit() {
        // Given / When / Then
        assertEquals(
            BillingPurchaseUiState.Purchased,
            billingPurchaseUiStateFor(PurchaseCompletionResult.Purchased),
        )
        assertEquals(
            BillingPurchaseUiState.Pending,
            billingPurchaseUiStateFor(PurchaseCompletionResult.Pending),
        )
        assertEquals(
            BillingPurchaseUiState.Cancelled,
            billingPurchaseUiStateFor(PurchaseCompletionResult.Cancelled),
        )
        assertEquals(
            BillingPurchaseUiState.Failed(BillingFailureKind.Unavailable),
            billingPurchaseUiStateFor(PurchaseCompletionResult.Unavailable),
        )
    }

    @Test
    fun success_restoreFold_verifiedWinsPendingAndPendingWinsUnavailable() {
        // Given / When / Then
        assertEquals(
            RestoreResult.Restored,
            restoreResultFor(
                hasOwnedPro = true,
                observations = listOf(
                    RestoreObservation.Pending,
                    RestoreObservation.Unavailable,
                    RestoreObservation.Verified,
                ),
            ),
        )
        assertEquals(
            RestoreResult.Pending,
            restoreResultFor(
                hasOwnedPro = true,
                observations = listOf(RestoreObservation.Unavailable, RestoreObservation.Pending),
            ),
        )
    }

    @Test
    fun failure_restoreFold_noOwnedProIsUnavailable_and503IsFailed() {
        // Given / When / Then
        assertEquals(
            RestoreResult.Unavailable,
            restoreResultFor(hasOwnedPro = false, observations = emptyList()),
        )
        assertEquals(
            RestoreResult.Failed(BillingFailureKind.Network),
            restoreResultFor(
                hasOwnedPro = true,
                observations = listOf(RestoreObservation.Failed(BillingFailureKind.Network)),
            ),
        )
        assertEquals(
            RestoreResult.Failed(BillingFailureKind.Network),
            restoreResultFor(
                hasOwnedPro = false,
                observations = listOf(RestoreObservation.Failed(BillingFailureKind.Network)),
            ),
        )
        assertEquals(
            RestoreObservation.Unavailable,
            restoreFailureObservationFor(EntitlementFailureKind.Revoked, 403),
        )
        assertEquals(
            RestoreObservation.Unavailable,
            restoreFailureObservationFor(EntitlementFailureKind.Conflict, 409),
        )
        assertEquals(
            RestoreObservation.Failed(BillingFailureKind.Network),
            restoreFailureObservationFor(EntitlementFailureKind.Unavailable, 503),
        )
    }

    @Test
    fun success_restoreNonSuccess_keepsValidCachedProFor409And503() {
        // Given / When / Then
        assertTrue(restoreProStatusFor(true, RestoreResult.Unavailable))
        assertTrue(
            restoreProStatusFor(true, RestoreResult.Failed(BillingFailureKind.Network)),
        )
        assertFalse(
            restoreProStatusFor(true, RestoreResult.Failed(BillingFailureKind.ProviderError)),
        )
        assertFalse(restoreProStatusFor(false, RestoreResult.Unavailable))
    }

    @Test
    fun success_purchaseAndRestoreShareAdmission_andDuplicateCallsAreIgnored() = runBlocking {
        // Given
        val admission = BillingOperationAdmission()

        // When / Then
        assertTrue(admission.tryAcquire(BillingOperation.Purchase))
        assertFalse(admission.tryAcquire(BillingOperation.Purchase))
        assertFalse(admission.tryAcquire(BillingOperation.Restore))
        admission.release(BillingOperation.Purchase)
        assertTrue(admission.tryAcquire(BillingOperation.Restore))
        admission.release(BillingOperation.Restore)
        assertTrue(admission.tryAcquire(BillingOperation.Purchase))
        admission.release(BillingOperation.Purchase)
    }

    @Test
    fun success_resolutionGate_acceptsOneActivityResult_andRejectsDuplicateOrEarlyCallbacks() {
        // Given
        val operationId = BillingOperationId.issue()
        val gate = BillingActivityResultGate(operationId)

        // When / Then
        assertFalse(gate.acceptActivityResult(operationId, 0, null))
        gate.beginAwaitingActivityResult()
        assertTrue(gate.acceptActivityResult(operationId, 0, null))
        assertFalse(gate.acceptActivityResult(operationId, 0, null))
        assertFalse(gate.acceptLaunchFailure(operationId))
    }

    @Test
    fun failure_resolutionGate_rejectsMismatchedAndIdentitylessCallbacksWithoutTerminating() {
        // Given
        val operationId = BillingOperationId.issue()
        val otherOperationId = BillingOperationId.issue()
        val gate = BillingActivityResultGate(operationId)
        gate.beginAwaitingActivityResult()

        // When / Then
        assertFalse(gate.acceptActivityResult(null, Activity.RESULT_OK, Intent()))
        assertFalse(gate.acceptActivityResult(otherOperationId, Activity.RESULT_OK, Intent()))
        assertFalse(gate.acceptLaunchFailure(otherOperationId))
        assertTrue(gate.acceptLaunchFailure(operationId))
    }

    @Test
    fun success_screenCallbackBinding_timeoutAbortQuarantinesB_untilLateACallback_thenAllowsB() {
        // Given: the launcher callback has one captured request identity and no Android callback
        // identity of its own.
        val firstOperationId = BillingOperationId.issue()
        val secondOperationId = BillingOperationId.issue()
        val binding = BillingResolutionCallbackBinding()
        val first = binding.capture(firstOperationId)
        assertTrue(first != null)
        var providerWasLaunched = false
        assertTrue(binding.handoffToProviderIfCurrent(first!!) { providerWasLaunched = true })
        assertTrue(providerWasLaunched)

        // When / Then: A times out/aborts without releasing its screen binding. B is rejected,
        // the late A callback consumes only A, and B may then be admitted as the next flight.
        assertNull(binding.capture(secondOperationId))
        assertFalse(binding.clear())
        assertFalse(binding.accept(first!!, secondOperationId) { error("mismatch accepted") })
        assertTrue(binding.isBound(firstOperationId))
        assertTrue(binding.acceptCurrent { accepted -> assertEquals(firstOperationId, accepted) })
        assertTrue(binding.capture(secondOperationId) != null)
    }

    @Test
    fun failure_screenCallbackBinding_unlaunchedCaptureRejectsIdentitylessCallback() {
        // Given
        val binding = BillingResolutionCallbackBinding()
        val operationId = BillingOperationId.issue()
        binding.capture(operationId)

        // Then: an ActivityResult is not a valid callback until Android has received the launch.
        assertFalse(binding.acceptCurrent { error("unlaunched callback accepted") })
    }

    @Test
    fun failure_screenCallbackBinding_timeoutBetweenClaimAndLaunchRejectsStaleAAndBlocksB() =
        runBlocking {
        // Given: A was captured and claimed, but its collector has not reached provider launch.
        val firstOperationId = BillingOperationId.issue()
        val secondOperationId = BillingOperationId.issue()
        val replay = BillingResolutionReplay()
        val binding = BillingResolutionCallbackBinding()
        val request = IntentSenderRequest.Builder(
            PendingIntent.getActivity(
                ApplicationProvider.getApplicationContext<Application>(),
                704,
                Intent(ApplicationProvider.getApplicationContext<Application>(), Activity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        assertTrue(replay.publish(BillingResolutionRequest(firstOperationId, request)))
        // This mirrors the collector boundary: replay delivery, capture, then launch claim.
        val deliveredRequest = replay.flow.first()
        val first = binding.capture(deliveredRequest.operationId)!!
        assertTrue(replay.claimLaunch(deliveredRequest.operationId))

        // When: the purchase workflow times out before mark/launcher handoff.
        assertTrue(binding.invalidate(firstOperationId))
        replay.clear()

        // Then: B is rejected by quarantine and the collector's atomic launch seam cannot hand
        // stale A to Android after the timeout invalidates its captured generation.
        assertTrue(binding.hasActiveBinding())
        assertNull(binding.capture(secondOperationId))
        var staleProviderWasLaunched = false
        assertFalse(binding.handoffToProviderIfCurrent(first) { staleProviderWasLaunched = true })
        assertFalse(staleProviderWasLaunched)
        assertFalse(binding.release(first))

        // The identity-less late A callback only drains its own quarantine, after which B may run.
        assertTrue(binding.acceptCurrent { accepted -> assertEquals(firstOperationId, accepted) })
        assertFalse(binding.hasActiveBinding())
        assertTrue(binding.capture(secondOperationId) != null)
        }

    @Test
    fun success_screenCallbackBinding_lifecycleResetIsAllowedOnlyBeforeProviderLaunch() {
        // Given: a captured request has not yet been handed to a provider Activity.
        val firstOperationId = BillingOperationId.issue()
        val secondOperationId = BillingOperationId.issue()
        val binding = BillingResolutionCallbackBinding()
        assertTrue(binding.capture(firstOperationId) != null)

        // When / Then: recreation may discard an unlaunched capture, but not a live provider launch.
        assertTrue(binding.clear())
        assertTrue(binding.capture(secondOperationId) != null)
    }

    @Test
    fun success_resolutionReplay_claimRequiresPublishedIdentityAndClearRemovesEnvelope() {
        // Given
        val replay = BillingResolutionReplay()
        val firstOperationId = BillingOperationId.issue()
        val secondOperationId = BillingOperationId.issue()
        val request = IntentSenderRequest.Builder(
            PendingIntent.getActivity(
                ApplicationProvider.getApplicationContext<Application>(),
                702,
                Intent(ApplicationProvider.getApplicationContext<Application>(), Activity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

        // When / Then
        assertTrue(replay.publish(BillingResolutionRequest(firstOperationId, request)))
        assertFalse(replay.claimLaunch(secondOperationId))
        assertTrue(replay.claimLaunch(firstOperationId))
        replay.clear()
        assertFalse(replay.claimLaunch(firstOperationId))
    }

    @Test
    fun success_resolutionGate_acceptsCancellationAsSingleTerminalPath() {
        // Given
        val operationId = BillingOperationId.issue()
        val gate = BillingActivityResultGate(operationId)
        gate.beginAwaitingActivityResult()

        // When / Then
        assertTrue(gate.acceptLaunchFailure(operationId))
        assertFalse(gate.acceptLaunchFailure(operationId))
        assertFalse(gate.acceptActivityResult(operationId, -1, null))
    }

    @Test
    fun success_resolutionGate_timeoutIsTerminalAndRejectsLateResult() = runBlocking {
        // Given
        val operationId = BillingOperationId.issue()
        val gate = BillingActivityResultGate(operationId)
        gate.beginAwaitingActivityResult()

        // When / Then
        assertTrue(gate.acceptTimeout(operationId))
        assertEquals(BillingPurchaseTerminal.TimedOut, gate.awaitActivityResult().await())
        assertFalse(gate.acceptActivityResult(operationId, Activity.RESULT_OK, Intent()))
        assertFalse(gate.acceptLaunchFailure(operationId))
    }

    @Test
    fun success_googlePurchaseUpdateCompletesAdmittedOperationOnlyAfterVerifyAndCommit() =
        runBlocking {
            // Given
            val store = TestEntitlementStore()
            val verifier = TestEntitlementVerifier()
            val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
            val repository = newRepository(store, verifier)
            assertTrue(repository.attachGateway(gateway))
            awaitCondition { gateway.queryCalls.get() > 0 }
            val session = repository.beginPurchaseSession()
            assertTrue(session != null)

            // When
            gateway.emit(PurchaseResult.Purchased(purchased("google-operation-token")))
            awaitCondition { verifier.verifyCalls.get() == 1 }

            // Then: provider PURCHASED is not UI success before backend verify+commit.
            assertEquals(0, store.verifiedCommits)
            verifier.verifyGate.complete(successfulVerifyResult())
            assertEquals(
                PurchaseCompletionResult.Purchased,
                withTimeout(2_000L) { repository.awaitPurchaseCompletion(session!!) },
            )
            assertEquals(1, store.verifiedCommits)
        }

    @Test
    fun failure_googlePurchaseUpdateForPreLaunchTokenCannotCompleteCurrentOperation() =
        runBlocking {
            // Given: the token was already owned before this launch was admitted.
            val store = TestEntitlementStore()
            val verifier = TestEntitlementVerifier(immediate = true)
            val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
            val repository = newRepository(store, verifier)
            assertTrue(repository.attachGateway(gateway))
            awaitCondition { gateway.queryCalls.get() > 0 }
            gateway.ownedPurchases = listOf(purchased("pre-launch-token"))
            val session = repository.beginPurchaseSession()!!

            // When: a late/replayed provider update carries the baseline token.
            gateway.emit(PurchaseResult.Purchased(purchased("pre-launch-token")))
            delay(100L)

            // Then: reconciliation may inspect it, but it cannot terminate this launch session.
            assertFalse(session.completion.isCompleted)
            repository.abortPurchase(session, PurchaseCompletionResult.Cancelled)
            assertEquals(PurchaseCompletionResult.Cancelled, session.completion.await())
        }

    @Test
    fun failure_googleIdentitylessProviderSignalCannotCompleteCurrentOperation() = runBlocking {
        // Given
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(TestEntitlementStore(), TestEntitlementVerifier(immediate = true))
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!

        // When: Google cancellation has no purchase token or operation id.
        gateway.emit(PurchaseResult.UserCancelled)
        delay(100L)

        // Then: an uncorrelatable signal cannot complete the active purchase.
        assertFalse(session.completion.isCompleted)
        repository.abortPurchase(session, PurchaseCompletionResult.Cancelled)
        assertEquals(PurchaseCompletionResult.Cancelled, session.completion.await())
    }

    @Test
    fun success_abortedPurchaseRejectsLateGoogleUpdateWithoutVerification() = runBlocking {
        // Given
        val store = TestEntitlementStore()
        val verifier = TestEntitlementVerifier()
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!

        // When
        repository.abortPurchase(session, PurchaseCompletionResult.Cancelled)
        gateway.emit(PurchaseResult.Purchased(purchased("late-google-token")))

        // Then
        assertEquals(PurchaseCompletionResult.Cancelled, repository.awaitPurchaseCompletion(session))
        delay(100L)
        assertEquals(0, verifier.verifyCalls.get())
        assertEquals(0, store.verifiedCommits)
    }

    @Test
    fun success_huaweiActivityResultUsesSeparateAdmittedSessionVerificationPath() = runBlocking {
        // Given
        val store = TestEntitlementStore()
        val verifier = TestEntitlementVerifier(immediate = true)
        val gateway = TestBillingGateway(StoreCapabilities.HUAWEI_STORE_ID)
        gateway.activityResult = PurchaseResult.Purchased(
            purchased("huawei-resolution-token", StoreCapabilities.HUAWEI_STORE_ID),
        )
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!

        // When
        val result = repository.handlePurchaseActivityResult(
            session,
            session.operationId,
            Activity.RESULT_OK,
            Intent(),
        )

        // Then
        assertEquals(PurchaseCompletionResult.Purchased, result)
        assertEquals(PurchaseCompletionResult.Purchased, repository.awaitPurchaseCompletion(session))
        assertEquals(1, store.verifiedCommits)
    }

    @Test
    fun success_huaweiResolutionReplay_survivesNoSubscriberAndRotation() = runBlocking {
        // Given: the provider publishes while no ActivityResult collector is attached.
        val replay = BillingResolutionReplay()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val request = IntentSenderRequest.Builder(
            PendingIntent.getActivity(
                context,
                701,
                Intent(context, Activity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

        // When
        val operationId = BillingOperationId.issue()
        assertTrue(replay.publish(BillingResolutionRequest(operationId, request)))

        // Then: a recreated collector receives the still-admitted resolution request.
        assertEquals(BillingResolutionRequest(operationId, request), replay.flow.first())
    }

    @Test
    fun success_purchaseActivityResult_rejectsStaleCrossOperationCallback() = runBlocking {
        // Given
        val store = TestEntitlementStore()
        val verifier = TestEntitlementVerifier(immediate = true)
        val gateway = TestBillingGateway(StoreCapabilities.HUAWEI_STORE_ID)
        gateway.activityResult = PurchaseResult.Purchased(
            purchased("current-operation-token", StoreCapabilities.HUAWEI_STORE_ID),
        )
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val staleSession = repository.beginPurchaseSession()!!
        repository.abortPurchase(staleSession, PurchaseCompletionResult.Cancelled)
        val currentSession = repository.beginPurchaseSession()!!

        // When: a callback carrying the prior operation identity arrives first.
        val staleResult = repository.handlePurchaseActivityResult(
            staleSession,
            staleSession.operationId,
            Activity.RESULT_OK,
            Intent(),
        )

        // Then: it cannot terminate or complete the current operation.
        assertEquals(PurchaseCompletionResult.Unavailable, staleResult)
        assertFalse(currentSession.completion.isCompleted)
        assertEquals(
            PurchaseCompletionResult.Purchased,
            repository.handlePurchaseActivityResult(
                currentSession,
                currentSession.operationId,
                Activity.RESULT_OK,
                Intent(),
            ),
        )
    }

    @Test
    fun failure_purchaseLaunchFailure_isActorTerminalAndCleanupIsIdempotent() = runBlocking {
        // Given
        val store = TestEntitlementStore()
        val verifier = TestEntitlementVerifier(immediate = true)
        val gateway = TestBillingGateway(StoreCapabilities.HUAWEI_STORE_ID).apply {
            launchResult = BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
        }
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java)
            .setup()
            .get()

        // When
        assertEquals(
            BillingGatewayResult.Failure(BillingFailureKind.ProviderError),
            repository.launchPreparedPurchase(session, activity),
        )
        repository.abortPurchase(
            session,
            PurchaseCompletionResult.Failed(BillingFailureKind.LaunchFailed),
        )
        repository.abortPurchase(session, PurchaseCompletionResult.Cancelled)

        // Then
        assertEquals(
            PurchaseCompletionResult.Failed(BillingFailureKind.LaunchFailed),
            repository.awaitPurchaseCompletion(session),
        )
    }

    @Test
    fun failure_queuedPurchaseLaunch_afterAbortDoesNotReachGateway() = runBlocking {
        // Given: the actor has accepted a launch command but its main-thread handoff is queued.
        val queuedMainDispatcher = StandardTestDispatcher()
        val gateway = TestBillingGateway(StoreCapabilities.HUAWEI_STORE_ID)
        val repository = newRepository(
            TestEntitlementStore(),
            TestEntitlementVerifier(immediate = true),
            mainDispatcher = queuedMainDispatcher,
        )
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java)
            .setup()
            .get()

        // When: caller cancellation aborts the admitted session before its queued handoff runs.
        val launchJob = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.launchPreparedPurchase(session, activity)
        }
        launchJob.cancelAndJoin()
        queuedMainDispatcher.scheduler.runCurrent()

        // Then: the lock-protected active-session guard rejects the stale queued command.
        assertEquals(0, gateway.launchCalls.get())
        assertEquals(PurchaseCompletionResult.Cancelled, session.completion.await())
    }

    @Test
    fun failure_purchaseTimeout_isTerminalAndRejectsLateProviderSignal() = runBlocking {
        // Given
        val store = TestEntitlementStore()
        val verifier = TestEntitlementVerifier()
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!

        // When
        repository.abortPurchase(session, PurchaseCompletionResult.Failed(BillingFailureKind.Network))
        gateway.emit(PurchaseResult.Purchased(purchased("late-timeout-token")))

        // Then
        assertEquals(
            PurchaseCompletionResult.Failed(BillingFailureKind.Network),
            repository.awaitPurchaseCompletion(session),
        )
        delay(100L)
        assertEquals(0, verifier.verifyCalls.get())
    }

    @Test
    fun failure_blockingProviderLaunch_timeoutCleanupInvalidatesActorBeforeGatewayClose() =
        runBlocking {
            // Given: the provider call blocks after the actor has admitted this operation.
            val gateway = TestBillingGateway(StoreCapabilities.HUAWEI_STORE_ID).apply {
                blockLaunch = true
            }
            val repository = newRepository(
                TestEntitlementStore(),
                TestEntitlementVerifier(immediate = true),
                mainDispatcher = Dispatchers.Default,
            )
            assertTrue(repository.attachGateway(gateway))
            awaitCondition { gateway.queryCalls.get() > 0 }
            val session = repository.beginPurchaseSession()!!
            val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java)
                .setup()
                .get()
            val launchJob = launch {
                repository.launchPreparedPurchase(session, activity)
            }

            try {
                // When: teardown must fall back while launchPurchase is still blocking.
                awaitCondition { gateway.launchEntered.count == 0L }
                assertTrue(repository.abortPurchaseImmediately(session))

                // Then: fallback does not wait on the provider-call mutex and closes ownership.
                assertEquals(PurchaseCompletionResult.Cancelled, session.completion.await())
                assertEquals(1, gateway.closeCalls.get())

                gateway.releaseLaunch.countDown()
                launchJob.join()
                assertNull(repository.beginPurchaseSession())
            } finally {
                gateway.releaseLaunch.countDown()
                launchJob.join()
            }
        }

    @Test
    fun success_immediateAbortWaitsForActorCleanupAndIsIdempotent() = runBlocking {
        // Given
        val gateway = TestBillingGateway(StoreCapabilities.HUAWEI_STORE_ID)
        val repository = newRepository(TestEntitlementStore(), TestEntitlementVerifier(immediate = true))
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        val session = repository.beginPurchaseSession()!!

        // When: teardown uses the non-suspending seam twice.
        assertTrue(repository.abortPurchaseImmediately(session))
        assertTrue(repository.abortPurchaseImmediately(session))

        // Then: the actor has completed the abort before the seam returns.
        assertEquals(PurchaseCompletionResult.Cancelled, session.completion.await())
    }

    @Test
    fun success_restoreActor_403And409PreserveValidCachedPro() = runBlocking {
        assertRestoreFailurePreservesCache(EntitlementFailureKind.Revoked, 403)
        assertRestoreFailurePreservesCache(EntitlementFailureKind.Conflict, 409)
    }

    @Test
    fun success_restoreActor_503AndNetworkPreserveValidCachedProAsNetworkFailure() = runBlocking {
        assertRestoreFailurePreservesCache(EntitlementFailureKind.Unavailable, 503)
        assertRestoreFailurePreservesCache(EntitlementFailureKind.Network, null)
    }

    @Test
    fun success_restoreActor_bindingMismatchIsUnavailableAndDoesNotRevokeCache() = runBlocking {
        // Given
        val store = TestEntitlementStore(cachedSnapshot())
        val verifier = TestEntitlementVerifier(immediate = true).apply {
            verifyResult = EntitlementApiResult.Failure(EntitlementFailureKind.Conflict, 409)
        }
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        gateway.ownedPurchases = listOf(purchased("binding-mismatch-token"))

        // When / Then
        assertEquals(RestoreResult.Unavailable, repository.restorePurchase())
        assertTrue(repository.isProSnapshot())
    }

    @Test
    fun success_restoreActor_mixedPrecedenceVerifiedWinsPendingAndPendingWinsUnavailable() =
        runBlocking {
            // Given
            val store = TestEntitlementStore(cachedSnapshot())
            val verifier = TestEntitlementVerifier(immediate = true)
            val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
            val repository = newRepository(store, verifier)
            assertTrue(repository.attachGateway(gateway))
            awaitCondition { gateway.queryCalls.get() > 0 }
            gateway.ownedPurchases = listOf(
                purchased("verified-mixed-token"),
                purchased("pending-mixed-token").copy(state = PurchaseState.Pending),
            )

            // When / Then
            assertEquals(RestoreResult.Restored, repository.restorePurchase())
            assertTrue(repository.isProSnapshot())

            verifier.verifyResult = EntitlementApiResult.Failure(
                EntitlementFailureKind.Conflict,
                409,
            )
            gateway.ownedPurchases = listOf(
                purchased("unavailable-mixed-token"),
                purchased("pending-only-mixed-token").copy(state = PurchaseState.Pending),
            )
            assertEquals(RestoreResult.Pending, repository.restorePurchase())
            assertTrue(repository.isProSnapshot())
        }

    @Test
    fun failure_restoreActor_expiredVerificationDoesNotRestoreOrActivateEntitlement() = runBlocking {
        // Given
        val store = TestEntitlementStore()
        val verifier = TestEntitlementVerifier(immediate = true).apply {
            verifyResult = EntitlementApiResult.Success(
                jwt = "expired-test-jwt",
                expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L - 1L,
            )
        }
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        gateway.ownedPurchases = listOf(purchased("expired-token"))

        // When
        val result = repository.restorePurchase()

        // Then
        assertEquals(RestoreResult.Failed(BillingFailureKind.InvalidResponse), result)
        assertFalse(repository.isProSnapshot())
        assertEquals(0, store.verifiedCommits)
    }

    @Test
    fun success_refreshRevocation_stillClearsExpiredEntitlement() = runBlocking {
        // Given
        val store = TestEntitlementStore(cachedSnapshot())
        val verifier = TestEntitlementVerifier(immediate = true).apply {
            refreshResult = EntitlementApiResult.Failure(EntitlementFailureKind.Revoked, 403)
        }
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { verifier.refreshCalls.get() > 0 }

        // Then
        assertFalse(repository.isProSnapshot())
        assertFalse(store.snapshot.isActive)
    }

    @Test
    fun failure_refreshExpiredSuccess_persistsInactiveSnapshot() = runBlocking {
        // Given: refresh succeeds too late with an already-expired entitlement.
        val store = TestEntitlementStore(cachedSnapshot())
        val verifier = TestEntitlementVerifier(immediate = true).apply {
            refreshResult = EntitlementApiResult.Success(
                jwt = "expired-refresh-jwt",
                expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L - 1L,
            )
        }
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)))
        awaitCondition { verifier.refreshCalls.get() > 0 }

        // Then: a later snapshot read cannot reactivate the previous cached entitlement.
        assertFalse(repository.isProSnapshot())
        assertFalse(store.snapshot.isActive)
        assertEquals("expired-refresh-jwt", store.snapshot.jwt)
    }

    @Test
    fun success_optionsViewModelOnCleared_isSafeWithoutActivePurchase() {
        // Given / When
        val viewModel = OptionsViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
        )
        viewModel.clearForTest()

        // Then
        assertFalse(viewModel.isPurchaseInFlight.value)
    }

    private suspend fun assertRestoreFailurePreservesCache(
        failureKind: EntitlementFailureKind,
        httpCode: Int?,
    ) {
        // Given
        val store = TestEntitlementStore(cachedSnapshot())
        val verifier = TestEntitlementVerifier(immediate = true).apply {
            verifyResult = EntitlementApiResult.Failure(failureKind, httpCode)
        }
        val gateway = TestBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID)
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { gateway.queryCalls.get() > 0 }
        gateway.ownedPurchases = listOf(purchased("restore-${httpCode ?: "network"}"))

        // When
        val result = repository.restorePurchase()

        // Then
        val expected = if (httpCode == 403 || httpCode == 409) {
            RestoreResult.Unavailable
        } else {
            RestoreResult.Failed(BillingFailureKind.Network)
        }
        assertEquals(expected, result)
        assertTrue(repository.isProSnapshot())
        assertTrue(store.snapshot.isActive)
    }

    private fun cachedSnapshot() = Snapshot(
        jwt = "cached-test-jwt",
        expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600L,
        isActive = true,
        pendingPurchases = emptyList(),
    )

    private fun newRepository(
        store: TestEntitlementStore,
        verifier: TestEntitlementVerifier,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): EntitlementRepository {
        verifiers += verifier
        return EntitlementRepository(
            context = ApplicationProvider.getApplicationContext<Application>(),
            storeOverride = store,
            apiClientOverride = verifier,
            processScopeOverride = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            observeProcessLifecycle = false,
            mainDispatcherOverride = mainDispatcher,
        ).also {
            repositories += it
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000L) {
            while (!condition()) delay(10L)
        }
    }

    private fun purchased(
        token: String,
        store: String = StoreCapabilities.GOOGLE_PLAY_STORE_ID,
    ) = OwnedPurchase(
        store = store,
        productId = PRO_PRODUCT_ID,
        purchaseToken = token,
        state = PurchaseState.Purchased,
        isAcknowledged = true,
    )

    private class TestBillingGateway(
        override val store: String,
    ) : BillingGateway {
        private val updates = MutableSharedFlow<PurchaseResult>(replay = 1, extraBufferCapacity = 16)
        private val closed = AtomicBoolean(false)
        override val purchaseUpdates: Flow<PurchaseResult> = updates
        val queryCalls = AtomicInteger()
        val launchCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val launchEntered = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        var blockLaunch = false
        var activityResult: PurchaseResult = PurchaseResult.Cancelled
        var ownedPurchases: List<OwnedPurchase> = emptyList()
        var launchResult: BillingGatewayResult<Unit> = BillingGatewayResult.Success(Unit)

        override suspend fun queryProductDetails() = BillingGatewayResult.Success(
            ProductDetails(PRO_PRODUCT_ID, null, null, null),
        )

        override fun launchPurchase(activity: Activity): BillingGatewayResult<Unit> {
            launchCalls.incrementAndGet()
            if (blockLaunch) {
                launchEntered.countDown()
                releaseLaunch.await(5L, TimeUnit.SECONDS)
            }
            return launchResult
        }

        override suspend fun queryOwnedPurchases(): BillingGatewayResult<List<OwnedPurchase>> {
            queryCalls.incrementAndGet()
            return BillingGatewayResult.Success(ownedPurchases)
        }

        override fun resultFromActivityResult(resultCode: Int, intent: Intent?): PurchaseResult =
            activityResult

        override fun close() {
            closed.set(true)
            closeCalls.incrementAndGet()
        }

        fun emit(result: PurchaseResult) {
            if (!closed.get()) updates.tryEmit(result)
        }
    }

    private class TestEntitlementVerifier(
        private val immediate: Boolean = false,
    ) : com.example.convert2video.billing.EntitlementVerifier {
        val verifyCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val verifyGate = CompletableDeferred<EntitlementApiResult>()
        var verifyResult: EntitlementApiResult? = null
        var refreshResult: EntitlementApiResult =
            EntitlementApiResult.Failure(EntitlementFailureKind.NotConfigured)

        override suspend fun verify(
            installId: String,
            store: String,
            productId: String,
            purchaseToken: String,
        ): EntitlementApiResult {
            verifyCalls.incrementAndGet()
            return verifyResult ?: if (immediate) successfulVerifyResult() else verifyGate.await()
        }

        override suspend fun refresh(jwt: String, installId: String): EntitlementApiResult {
            refreshCalls.incrementAndGet()
            return refreshResult
        }

        fun closeForTest() {
            verifyGate.complete(EntitlementApiResult.Failure(EntitlementFailureKind.Network))
        }
    }

    private class TestEntitlementStore(
        initialSnapshot: Snapshot = Snapshot(null, null, false, emptyList()),
    ) : EntitlementStore {
        var snapshot = initialSnapshot
        var verifiedCommits = 0

        override suspend fun read(): Snapshot = snapshot

        override suspend fun commit(jwt: String?, expiresAtEpochSeconds: Long?, isActive: Boolean) {
            snapshot = snapshot.copy(jwt = jwt, expiresAtEpochSeconds = expiresAtEpochSeconds, isActive = isActive)
        }

        override suspend fun commitVerifiedEntitlement(
            jwt: String,
            expiresAtEpochSeconds: Long,
            isActive: Boolean,
            processedPurchase: OwnedPurchase,
        ) {
            verifiedCommits += 1
            snapshot = snapshot.copy(
                jwt = jwt,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                isActive = isActive,
                pendingPurchases = emptyList(),
            )
        }

        override suspend fun upsertPendingPurchase(purchase: PendingPurchase) {
            snapshot = snapshot.copy(pendingPurchases = listOf(purchase))
        }

        override suspend fun removePendingPurchase(purchase: PendingPurchase) {
            snapshot = snapshot.copy(pendingPurchases = emptyList())
        }
    }
}
