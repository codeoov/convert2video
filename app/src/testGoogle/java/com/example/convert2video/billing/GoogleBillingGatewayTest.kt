package com.example.convert2video.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails as GoogleProductDetails
import com.android.billingclient.api.Purchase
import com.example.convert2video.store.StoreCapabilities
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleBillingGatewayTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun success_resultFromActivityResultMapsOnlyCancelledToUserCancelledAndReleasesLaunch() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        gateway.launchPurchase(activity)

        // When
        val cancelled = gateway.resultFromActivityResult(Activity.RESULT_CANCELED, null)
        val completed = gateway.resultFromActivityResult(Activity.RESULT_OK, Intent("result"))
        val relaunch = gateway.launchPurchase(activity)

        // Then
        assertEquals(PurchaseResult.UserCancelled, cancelled)
        assertEquals(PurchaseResult.Failed(BillingFailureKind.InvalidResponse), completed)
        assertTrue(relaunch is BillingGatewayResult.Success)
        assertEquals(2, adapter.launchCalls)
        gateway.close()
    }

    @Test
    fun success_preparePurchaseIntentUsesProductCacheOnSecondCallAndLaunchRemainsCompatible() =
        runBlocking {
            // Given
            val adapter = FakeGoogleBillingClientAdapter()
            val gateway = GoogleBillingGateway(context, adapter)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

            // When
            val first = gateway.preparePurchaseIntent()
            val second = gateway.preparePurchaseIntent()
            val launch = gateway.launchPurchase(activity)

            // Then
            assertTrue(first is BillingGatewayResult.Success)
            assertTrue(second is BillingGatewayResult.Success)
            assertTrue(launch is BillingGatewayResult.Success)
            assertEquals(1, adapter.queryProductDetailsCalls)
            assertEquals(1, adapter.launchCalls)
            gateway.close()
        }

    @Test
    fun failure_launchPurchaseOffMainDoesNotCallSdk() {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(isReady = true)
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        // When
        val result = runBlocking(Dispatchers.Default) { gateway.launchPurchase(activity) }

        // Then
        assertEquals(BillingFailureKind.LaunchFailed, (result as BillingGatewayResult.Failure).kind)
        assertEquals(0, adapter.launchCalls)
        gateway.close()
    }

    @Test
    fun success_queryOwnedPurchasesMapsOkEmptyToEmptyOwnedList() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)

        // When
        val result = gateway.queryOwnedPurchases()

        // Then
        assertEquals(BillingGatewayResult.Success(emptyList<OwnedPurchase>()), result)
        assertEquals(1, adapter.queryPurchasesCalls)
        gateway.close()
    }

    @Test
    fun success_queryOwnedPurchasesFiltersSkuAndPreservesPendingState() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueOwnedResponse(
            response(BillingClient.BillingResponseCode.OK),
            listOf(
                purchase(Purchase.PurchaseState.PURCHASED, "unrelated-token", products = listOf("other")),
                purchase(Purchase.PurchaseState.PENDING, "owned-pending-token"),
            ),
        )
        val gateway = GoogleBillingGateway(context, adapter)

        // When
        val result = gateway.queryOwnedPurchases()

        // Then
        val owned = (result as BillingGatewayResult.Success).value
        assertEquals(1, owned.size)
        assertEquals(PurchaseState.Pending, owned.single().state)
        gateway.close()
    }

    @Test
    fun failure_queryOwnedPurchasesBlankTokenMapsInvalidResponse() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueOwnedResponse(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PURCHASED, "  ")),
        )
        val gateway = GoogleBillingGateway(context, adapter)

        // When
        val result = gateway.queryOwnedPurchases()

        // Then
        assertEquals(BillingFailureKind.InvalidResponse, (result as BillingGatewayResult.Failure).kind)
        gateway.close()
    }

    @Test
    fun failure_queryProductDetailsMapsOkEmptyToInvalidResponse() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueProductResponse(response(BillingClient.BillingResponseCode.OK), emptyList())
        val gateway = GoogleBillingGateway(context, adapter)

        // When
        val result = gateway.queryProductDetails()

        // Then
        assertEquals(BillingFailureKind.InvalidResponse, (result as BillingGatewayResult.Failure).kind)
        assertEquals(1, adapter.queryProductDetailsCalls)
        gateway.close()
    }

    @Test
    fun success_purchaseCallbackMapsPurchasedAndPendingAndPreservesAcknowledged() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val updates = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.purchaseUpdates.take(2).toList()
        }

        // When
        adapter.emitPurchaseUpdate(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PURCHASED, "purchased-token", acknowledged = true)),
        )
        adapter.emitPurchaseUpdate(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PENDING, "pending-token")),
        )

        // Then
        val values = updates.await()
        assertTrue(values[0] is PurchaseResult.Purchased)
        assertTrue(values[1] is PurchaseResult.Pending)
        assertTrue((values[0] as PurchaseResult.Purchased).purchase.isAcknowledged)
        gateway.close()
    }

    @Test
    fun failure_purchaseCallbackOkEmptyMapsInvalidResponse() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }

        // When
        adapter.emitPurchaseUpdate(response(BillingClient.BillingResponseCode.OK))

        // Then
        assertEquals(PurchaseResult.Failed(BillingFailureKind.InvalidResponse), update.await())
        gateway.close()
    }

    @Test
    fun failure_purchaseCallbackServiceDisconnectedMapsNetwork() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }

        // When
        adapter.emitPurchaseUpdate(response(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED))

        // Then
        assertEquals(PurchaseResult.Failed(BillingFailureKind.Network), update.await())
        gateway.close()
    }

    @Test
    fun failure_purchaseCallbackFiltersSkuAndRejectsBlankToken() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }

        // When
        adapter.emitPurchaseUpdate(
            response(BillingClient.BillingResponseCode.OK),
            listOf(
                purchase(Purchase.PurchaseState.PURCHASED, "other-token", products = listOf("other")),
                purchase(Purchase.PurchaseState.PURCHASED, " "),
            ),
        )

        // Then
        assertEquals(PurchaseResult.Failed(BillingFailureKind.InvalidResponse), update.await())
        gateway.close()
    }

    @Test
    fun failure_purchaseCallbackUnknownStateMapsInvalidResponse() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }

        // When
        adapter.emitPurchaseUpdate(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(99, "unknown-state-token")),
        )

        // Then
        assertEquals(PurchaseResult.Failed(BillingFailureKind.InvalidResponse), update.await())
        gateway.close()
    }

    @Test
    fun failure_launchAlreadyOwnedReturnsProviderErrorBeforeRecoveryEmitsPurchased() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueOwnedResponse(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PURCHASED, "owned-token")),
        )
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }
        adapter.launchResponse = response(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)

        // When
        val launchResult = gateway.launchPurchase(activity)

        // Then: synchronous caller result has precedence; recovery is advisory.
        assertEquals(BillingFailureKind.ProviderError, (launchResult as BillingGatewayResult.Failure).kind)
        assertEquals(
            PurchaseResult.Purchased(
                OwnedPurchase(
                    store = StoreCapabilities.GOOGLE_PLAY_STORE_ID,
                    productId = PRO_PRODUCT_ID,
                    purchaseToken = "owned-token",
                    state = PurchaseState.Purchased,
                    isAcknowledged = false,
                ),
            ),
            update.await(),
        )
        gateway.close()
    }

    @Test
    fun success_launchAlreadyOwnedRecoveryEmitsPending() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueOwnedResponse(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PENDING, "pending-owned-token")),
        )
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }
        adapter.launchResponse = response(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)

        // When
        gateway.launchPurchase(activity)

        // Then
        assertTrue(update.await() is PurchaseResult.Pending)
        gateway.close()
    }

    @Test
    fun success_launchAlreadyOwnedRecoveryEmitsAlreadyOwnedWhenNoMatchingOwnedPurchase() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueOwnedResponse(response(BillingClient.BillingResponseCode.OK), emptyList())
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }
        adapter.launchResponse = response(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)

        // When
        gateway.launchPurchase(activity)

        // Then
        assertEquals(PurchaseResult.AlreadyOwned, update.await())
        gateway.close()
    }

    @Test
    fun failure_launchAlreadyOwnedRecoveryEmitsMappedQueryFailure() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueOwnedResponse(
            response(BillingClient.BillingResponseCode.NETWORK_ERROR),
            emptyList(),
        )
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        val update = async(start = CoroutineStart.UNDISPATCHED) { gateway.purchaseUpdates.first() }
        adapter.launchResponse = response(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)

        // When
        gateway.launchPurchase(activity)

        // Then
        assertEquals(PurchaseResult.Failed(BillingFailureKind.Network), update.await())
        gateway.close()
    }

    @Test
    fun success_launchAlreadyOwnedSyncAndCallbackRecoverOnlyOnce() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(autoOwnedResponse = false)
        adapter.enqueueOwnedResponse(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PURCHASED, "once-token")),
        )
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        val updates = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.purchaseUpdates.take(1).toList()
        }
        adapter.launchResponse = response(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)

        // When
        val launchResult = gateway.launchPurchase(activity)
        adapter.emitPurchaseUpdate(response(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED))
        while (adapter.queryPurchasesCalls == 0) delay(1)
        adapter.emitOwnedResponse()

        // Then
        assertEquals(BillingFailureKind.ProviderError, (launchResult as BillingGatewayResult.Failure).kind)
        assertEquals(1, updates.await().size)
        assertEquals(1, adapter.queryPurchasesCalls)
        gateway.close()
    }

    @Test
    fun success_connectionRetriesThreeAttemptsWithRequiredDelays() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueSetupResponse(response(BillingClient.BillingResponseCode.NETWORK_ERROR))
        adapter.enqueueSetupResponse(response(BillingClient.BillingResponseCode.NETWORK_ERROR))
        adapter.enqueueSetupResponse(response(BillingClient.BillingResponseCode.OK))
        val delays = CopyOnWriteArrayList<Long>()
        val gateway = GoogleBillingGateway(
            context,
            adapter,
            GoogleBillingTiming(retryDelay = { delays += it }),
        )

        // When
        val result = gateway.queryProductDetails()

        // Then
        assertTrue(result is BillingGatewayResult.Success)
        assertEquals(3, adapter.startConnectionCalls)
        assertEquals(listOf(250L, 500L), delays.toList())
        gateway.close()
    }

    @Test
    fun success_activeQueryDisconnectReconnectsAndRetriesOnce() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueProductResponse(
            response(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED),
            emptyList(),
        )
        adapter.enqueueProductResponse(response(BillingClient.BillingResponseCode.OK), listOf(product("reconnected")))
        val gateway = GoogleBillingGateway(context, adapter)

        // When
        val result = gateway.queryProductDetails()

        // Then
        assertEquals("reconnected", (result as BillingGatewayResult.Success).value.title)
        assertEquals(2, adapter.startConnectionCalls)
        assertEquals(2, adapter.queryProductDetailsCalls)
        gateway.close()
    }

    @Test
    fun failure_activeQueryDisconnectExhaustsSingleThreeAttemptOperationBudget() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueSetupResponse(response(BillingClient.BillingResponseCode.OK))
        adapter.enqueueSetupResponse(response(BillingClient.BillingResponseCode.NETWORK_ERROR))
        adapter.enqueueSetupResponse(response(BillingClient.BillingResponseCode.NETWORK_ERROR))
        adapter.enqueueProductResponse(
            response(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED),
            emptyList(),
        )
        val delays = CopyOnWriteArrayList<Long>()
        val gateway = GoogleBillingGateway(
            context,
            adapter,
            GoogleBillingTiming(retryDelay = { delays += it }),
        )

        // When
        val result = gateway.queryProductDetails()

        // Then: initial connection + only two reconnect attempts.
        assertEquals(BillingFailureKind.Network, (result as BillingGatewayResult.Failure).kind)
        assertEquals(3, adapter.startConnectionCalls)
        assertEquals(1, adapter.queryProductDetailsCalls)
        assertEquals(listOf(250L, 500L), delays.toList())
        gateway.close()
    }

    @Test
    fun failure_queryTimeoutIgnoresLateCallbackAndLeavesCacheInvalid() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(autoProductResponse = false)
        val gateway = GoogleBillingGateway(
            context,
            adapter,
            GoogleBillingTiming(queryTimeoutMillis = 20L),
        )

        // When
        val result = gateway.queryProductDetails()
        adapter.emitProductResponse(response(BillingClient.BillingResponseCode.OK), listOf(product("late")))

        // Then
        assertEquals(BillingFailureKind.Network, (result as BillingGatewayResult.Failure).kind)
        assertEquals(
            BillingFailureKind.InvalidProduct,
            (gateway.launchPurchase(Robolectric.buildActivity(Activity::class.java).setup().get())
                as BillingGatewayResult.Failure).kind,
        )
        gateway.close()
    }

    @Test
    fun failure_lateQueryDisconnectAfterTimeoutHasNoStateSideEffects() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(autoOwnedResponse = false)
        val gateway = GoogleBillingGateway(
            context,
            adapter,
            GoogleBillingTiming(queryTimeoutMillis = 20L),
        )
        gateway.queryProductDetails()

        // When
        val timedOut = gateway.queryOwnedPurchases()
        gateway.queryProductDetails()
        adapter.emitOwnedResponse(
            response(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED),
            updateReadiness = false,
        )

        // Then: the late callback cannot invalidate the newer product cache.
        assertEquals(BillingFailureKind.Network, (timedOut as BillingGatewayResult.Failure).kind)
        assertTrue(gateway.launchPurchase(Robolectric.buildActivity(Activity::class.java).setup().get())
            is BillingGatewayResult.Success)
        gateway.close()
    }

    @Test
    fun failure_lateSetupDisconnectAfterTimeoutHasNoStateSideEffects() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(autoSetup = false)
        val gateway = GoogleBillingGateway(
            context,
            adapter,
            GoogleBillingTiming(connectionTimeoutMillis = 20L),
        )
        val timedOut = gateway.queryProductDetails()
        assertEquals(BillingFailureKind.Network, (timedOut as BillingGatewayResult.Failure).kind)
        adapter.autoSetup = true

        // When
        val recovered = gateway.queryProductDetails()
        adapter.emitServiceDisconnected(setupIndex = 0, updateReadiness = false)

        // Then: the timed-out setup callback cannot disconnect the recovered generation.
        assertTrue(recovered is BillingGatewayResult.Success)
        assertTrue(gateway.launchPurchase(Robolectric.buildActivity(Activity::class.java).setup().get())
            is BillingGatewayResult.Success)
        gateway.close()
    }

    @Test
    fun success_cacheIsReplacedAfterDisconnectAndSuccessfulValidatedQuery() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        adapter.enqueueProductResponse(response(BillingClient.BillingResponseCode.OK), listOf(product("first")))
        adapter.enqueueProductResponse(response(BillingClient.BillingResponseCode.OK), listOf(product("second")))
        val gateway = GoogleBillingGateway(context, adapter)
        val first = gateway.queryProductDetails()
        adapter.emitServiceDisconnected()

        // When
        val second = gateway.queryProductDetails()

        // Then
        assertEquals("first", (first as BillingGatewayResult.Success).value.title)
        assertEquals("second", (second as BillingGatewayResult.Success).value.title)
        assertEquals(2, adapter.queryProductDetailsCalls)
        gateway.close()
    }

    @Test
    fun success_queryMutexSerializesProductAndOwnedQueries() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(autoProductResponse = false)
        val gateway = GoogleBillingGateway(context, adapter)
        val productQuery = async(start = CoroutineStart.UNDISPATCHED) { gateway.queryProductDetails() }
        val ownedQuery = async(start = CoroutineStart.UNDISPATCHED) { gateway.queryOwnedPurchases() }
        delay(20)

        // When
        assertEquals(0, adapter.queryPurchasesCalls)
        adapter.emitProductResponse(response(BillingClient.BillingResponseCode.OK), listOf(product()))
        assertTrue(productQuery.await() is BillingGatewayResult.Success)
        assertTrue(withTimeoutOrNull(500L) {
            while (adapter.queryPurchasesCalls == 0) delay(1)
            true
        } == true)
        adapter.emitOwnedResponse()

        // Then
        assertTrue(ownedQuery.await() is BillingGatewayResult.Success)
        gateway.close()
    }

    @Test
    fun failure_duplicateLaunchIsRejectedWithoutSecondSdkLaunch() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()

        // When
        val first = gateway.launchPurchase(activity)
        val second = gateway.launchPurchase(activity)

        // Then
        assertTrue(first is BillingGatewayResult.Success)
        assertEquals(BillingFailureKind.Unavailable, (second as BillingGatewayResult.Failure).kind)
        assertEquals(1, adapter.launchCalls)
        gateway.close()
    }

    @Test
    fun failure_unrelatedPurchaseCallbackDoesNotReleaseActiveLaunchGuard() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()
        assertTrue(gateway.launchPurchase(activity) is BillingGatewayResult.Success)

        // When
        adapter.emitPurchaseUpdate(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PURCHASED, "irrelevant", products = listOf("other"))),
        )
        val duplicate = gateway.launchPurchase(activity)

        // Then
        assertEquals(BillingFailureKind.Unavailable, (duplicate as BillingGatewayResult.Failure).kind)
        assertEquals(1, adapter.launchCalls)
        gateway.close()
    }

    @Test
    fun success_launchWatchdogReleasesGuardWithoutRelaunching() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val watchdogStarted = CompletableDeferred<Unit>()
        val releaseWatchdog = CompletableDeferred<Unit>()
        val gateway = GoogleBillingGateway(
            context,
            adapter,
            GoogleBillingTiming(
                launchWatchdogDelay = {
                    watchdogStarted.complete(Unit)
                    releaseWatchdog.await()
                },
            ),
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()

        // When
        assertTrue(gateway.launchPurchase(activity) is BillingGatewayResult.Success)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(watchdogStarted.isCompleted)
        assertEquals(
            BillingFailureKind.Unavailable,
            (gateway.launchPurchase(activity) as BillingGatewayResult.Failure).kind,
        )
        releaseWatchdog.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val afterWatchdog = gateway.launchPurchase(activity)

        // Then
        assertTrue(afterWatchdog is BillingGatewayResult.Success)
        assertEquals(2, adapter.launchCalls)
        gateway.close()
    }

    @Test
    fun failure_cacheInvalidationBeforeLaunchRejectsWithoutSdkLaunch() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        gateway.queryProductDetails()

        // When
        adapter.emitServiceDisconnected()
        val result = gateway.launchPurchase(activity)

        // Then: invalidation wins before the main-thread launch validation.
        assertEquals(BillingFailureKind.Unavailable, (result as BillingGatewayResult.Failure).kind)
        assertEquals(0, adapter.launchCalls)
        gateway.close()
    }

    @Test
    fun success_closeDuringSetupDoesNotRestoreReadyAndEndsConnectionOnce() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter(autoSetup = false)
        val gateway = GoogleBillingGateway(context, adapter)
        val query = async(start = CoroutineStart.UNDISPATCHED) { gateway.queryProductDetails() }
        assertEquals(1, adapter.startConnectionCalls)

        // When
        gateway.close()
        adapter.completeSetup(response(BillingClient.BillingResponseCode.OK))
        val result = query.await()

        // Then
        assertEquals(BillingFailureKind.Unavailable, (result as BillingGatewayResult.Failure).kind)
        assertEquals(BillingFailureKind.Unavailable, (gateway.queryOwnedPurchases()
            as BillingGatewayResult.Failure).kind)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, adapter.endConnectionCalls)
    }

    @Test
    fun success_closeIsIdempotentAndSuppressesPostCloseCallbacks() = runBlocking {
        // Given
        val adapter = FakeGoogleBillingClientAdapter()
        val gateway = GoogleBillingGateway(context, adapter)
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(100L) { gateway.purchaseUpdates.first() }
        }

        // When
        async(Dispatchers.Default) {
            gateway.close()
            gateway.close()
        }.await()
        adapter.emitPurchaseUpdate(
            response(BillingClient.BillingResponseCode.OK),
            listOf(purchase(Purchase.PurchaseState.PURCHASED, "late-token")),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then
        assertEquals(null, update.await())
        assertEquals(1, adapter.endConnectionCalls)
    }

    @Test
    @Suppress("DEPRECATION")
    fun failure_queryMapsProviderResponseCodesToCommonKinds() = runBlocking {
        // Given / When / Then
        val cases = listOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE to BillingFailureKind.Unavailable,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE to BillingFailureKind.Unavailable,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED to BillingFailureKind.Unavailable,
            BillingClient.BillingResponseCode.NETWORK_ERROR to BillingFailureKind.Network,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE to BillingFailureKind.InvalidProduct,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR to BillingFailureKind.ProviderError,
            BillingClient.BillingResponseCode.ERROR to BillingFailureKind.ProviderError,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED to BillingFailureKind.ProviderError,
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT to BillingFailureKind.Network,
            987654 to BillingFailureKind.ProviderError,
        )
        cases.forEach { (code, expected) ->
            val adapter = FakeGoogleBillingClientAdapter()
            adapter.enqueueOwnedResponse(response(code), emptyList())
            val gateway = GoogleBillingGateway(context, adapter)
            val result = gateway.queryOwnedPurchases()
            assertEquals(expected, (result as BillingGatewayResult.Failure).kind)
            gateway.close()
        }
    }

    private fun purchase(
        state: Int,
        token: String,
        acknowledged: Boolean = false,
        products: List<String> = listOf(PRO_PRODUCT_ID),
    ): Purchase = FakePurchase(state, token, acknowledged, products)

    private class FakeGoogleBillingClientAdapter(
        var autoSetup: Boolean = true,
        private val autoProductResponse: Boolean = true,
        private val autoOwnedResponse: Boolean = true,
        override var isReady: Boolean = false,
    ) : GoogleBillingClientAdapter {
        private var purchaseUpdateListener: ((BillingResult, List<Purchase>) -> Unit)? = null
        private val setupFinishedCallbacks = mutableListOf<(BillingResult) -> Unit>()
        private val disconnectedCallbacks = mutableListOf<() -> Unit>()
        private var productCallback: ((BillingResult, List<GoogleProductDetails>) -> Unit)? = null
        private var ownedCallback: ((BillingResult, List<Purchase>) -> Unit)? = null
        private val setupResponses = ArrayDeque<BillingResult>()
        private val productResponses = ArrayDeque<Pair<BillingResult, List<GoogleProductDetails>>>()
        private val ownedResponses = ArrayDeque<Pair<BillingResult, List<Purchase>>>()
        var startConnectionCalls = 0
            private set
        var queryProductDetailsCalls = 0
            private set
        var queryPurchasesCalls = 0
            private set
        var launchCalls = 0
            private set
        var endConnectionCalls = 0
            private set
        var launchResponse: BillingResult = response(BillingClient.BillingResponseCode.OK)

        override fun setPurchaseUpdateListener(listener: (BillingResult, List<Purchase>) -> Unit) {
            purchaseUpdateListener = listener
        }

        override fun startConnection(
            onSetupFinished: (BillingResult) -> Unit,
            onServiceDisconnected: () -> Unit,
        ) {
            startConnectionCalls += 1
            setupFinishedCallbacks += onSetupFinished
            disconnectedCallbacks += onServiceDisconnected
            if (autoSetup) completeSetup()
        }

        override fun queryProductDetails(
            onResponse: (BillingResult, List<GoogleProductDetails>) -> Unit,
        ) {
            queryProductDetailsCalls += 1
            productCallback = onResponse
            if (autoProductResponse) emitProductResponse()
        }

        override fun queryPurchases(onResponse: (BillingResult, List<Purchase>) -> Unit) {
            queryPurchasesCalls += 1
            ownedCallback = onResponse
            if (autoOwnedResponse) emitOwnedResponse()
        }

        override fun launchBillingFlow(
            activity: Activity,
            productDetails: GoogleProductDetails,
        ): BillingResult {
            launchCalls += 1
            return launchResponse
        }

        override fun endConnection() {
            endConnectionCalls += 1
            isReady = false
        }

        fun enqueueSetupResponse(result: BillingResult) = setupResponses.addLast(result)

        fun enqueueProductResponse(result: BillingResult, products: List<GoogleProductDetails>) =
            productResponses.addLast(result to products)

        fun enqueueOwnedResponse(result: BillingResult, purchases: List<Purchase>) =
            ownedResponses.addLast(result to purchases)

        fun completeSetup(
            result: BillingResult = if (setupResponses.isEmpty()) {
                response(BillingClient.BillingResponseCode.OK)
            } else {
                setupResponses.removeFirst()
            },
        ) {
            if (result.responseCode == BillingClient.BillingResponseCode.OK) isReady = true
            setupFinishedCallbacks.lastOrNull()?.invoke(result)
        }

        fun emitProductResponse(
            result: BillingResult = productResponses.firstOrNull()?.first
                ?: response(BillingClient.BillingResponseCode.OK),
            products: List<GoogleProductDetails> = productResponses.firstOrNull()?.second
                ?: listOf(product()),
        ) {
            if (productResponses.isNotEmpty() && result == productResponses.first().first && products == productResponses.first().second) {
                productResponses.removeFirst()
            }
            val callback = productCallback ?: return
            productCallback = null
            if (result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) isReady = false
            callback(result, products)
        }

        fun emitOwnedResponse(
            result: BillingResult = ownedResponses.firstOrNull()?.first
                ?: response(BillingClient.BillingResponseCode.OK),
            purchases: List<Purchase> = ownedResponses.firstOrNull()?.second ?: emptyList(),
            updateReadiness: Boolean = true,
        ) {
            if (ownedResponses.isNotEmpty() && result == ownedResponses.first().first && purchases == ownedResponses.first().second) {
                ownedResponses.removeFirst()
            }
            val callback = ownedCallback ?: return
            ownedCallback = null
            if (updateReadiness && result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                isReady = false
            }
            callback(result, purchases)
        }

        fun emitPurchaseUpdate(result: BillingResult, purchases: List<Purchase> = emptyList()) {
            purchaseUpdateListener?.invoke(result, purchases)
        }

        fun emitServiceDisconnected(
            setupIndex: Int = disconnectedCallbacks.lastIndex,
            updateReadiness: Boolean = true,
        ) {
            if (updateReadiness) isReady = false
            disconnectedCallbacks.getOrNull(setupIndex)?.invoke()
        }
    }

    private class FakePurchase(
        private val state: Int,
        private val token: String,
        private val acknowledged: Boolean,
        private val products: List<String>,
    ) : Purchase("{}", "signature") {
        override fun getProducts(): List<String> = products
        override fun getPurchaseToken(): String = token
        override fun getPurchaseState(): Int = state
        override fun isAcknowledged(): Boolean = acknowledged
    }

    private companion object {
        fun product(title: String = "Pro"): GoogleProductDetails = newProductDetails(
            """
            {
              "productId":"$PRO_PRODUCT_ID",
              "type":"inapp",
              "title":"$title",
              "name":"$title",
              "description":"Pro access",
              "oneTimePurchaseOfferDetails":{
                "priceAmountMicros":1000000,
                "priceCurrencyCode":"USD",
                "formattedPrice":"$1.00"
              }
            }
            """.trimIndent(),
        )

        private fun newProductDetails(json: String): GoogleProductDetails {
            val constructor = GoogleProductDetails::class.java
                .getDeclaredConstructor(String::class.java)
                .apply { isAccessible = true }
            return constructor.newInstance(json)
        }

        fun response(code: Int): BillingResult = BillingResult.newBuilder()
            .setResponseCode(code)
            .build()
    }
}
