package com.example.convert2video.billing

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.store.StoreCapabilities
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun successfulVerifyResult() = EntitlementApiResult.Success(
    jwt = "verified-jwt",
    expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600L,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementRepositoryBillingLifecycleTest {

    private val repositories = mutableListOf<EntitlementRepository>()

    @After
    fun tearDown() {
        repositories.forEach(EntitlementRepository::closeForTest)
    }

    @Test
    fun success_verifyMustCompleteBeforeProIsExposed() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier()
        val gateway = FakeGateway(owned = listOf(purchased("verify-order-token")))
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { verifier.verifyCalls.get() == 1 }

        // Then: staging a purchase does not grant Pro before backend verification.
        assertFalse(repository.isProHot.value == true)
        assertEquals(0, store.verifiedCommits)

        // When
        verifier.verifyGate.complete(successfulVerifyResult())
        awaitCondition { store.verifiedCommits == 1 }

        // Then
        assertEquals(1, store.verifiedCommits)
        assertTrue(repository.isProHot.value == true)
    }

    @Test
    fun success_ownedRestoreUsesTheSameBackendVerifyPath() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier(immediate = true)
        val gateway = FakeGateway(owned = listOf(purchased("restore-token")))
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { store.verifiedCommits == 1 }

        // Then
        assertEquals(1, verifier.verifyCalls.get())
        assertEquals(1, store.verifiedCommits)
        assertEquals("restore-token", verifier.lastToken)
        assertTrue(verifier.lastInstallId?.let { runCatching { UUID.fromString(it) }.isSuccess } == true)
    }

    @Test
    fun failure_invalidPurchaseResultsNeverReachVerifyOrPro() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier(immediate = true)
        val gateway = FakeGateway()
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitRepositoryRead(repository)

        // When: parser outcomes are advisory failures, not owned purchases.
        gateway.emit(PurchaseResult.Malformed)
        gateway.emit(PurchaseResult.ProductMismatch)
        gateway.emit(PurchaseResult.Duplicate)
        gateway.emit(PurchaseResult.Cancelled)
        gateway.emit(PurchaseResult.UserCancelled)
        gateway.emit(PurchaseResult.AlreadyOwned)

        // Then
        kotlinx.coroutines.yield()
        assertEquals(0, verifier.verifyCalls.get())
        assertFalse(repository.isProHot.value == true)
        assertEquals(0, store.verifiedCommits)
    }

    @Test
    fun success_ownedQueryReconcileUsesUpdatedOwnedStateWithoutGatewayRebind() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier(immediate = true)
        val gateway = FakeGateway()
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        assertFalse(awaitRepositoryRead(repository))

        // When: the next owned query observes a purchase without rebinding the gateway.
        gateway.setOwned(listOf(purchased("new-token")))
        repository.onStart(ProcessLifecycleOwner.get())
        awaitRepositoryRead(repository)

        // Then
        assertEquals(1, verifier.verifyCalls.get())
        assertEquals("new-token", verifier.lastToken)
        assertEquals(1, store.verifiedCommits)
    }

    @Test
    fun success_pendingOwnedPurchase_staysPendingPreservesTokenAndDoesNotGrantPro() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier(immediate = true)
        val gateway = FakeGateway(owned = listOf(pending("pending-token")))
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(gateway))
        awaitCondition {
            store.snapshot.pendingPurchases.any { it.purchaseToken == "pending-token" }
        }

        // Then
        assertFalse(awaitRepositoryRead(repository))
        assertFalse(repository.isProHot.value == true)
        assertEquals(0, verifier.verifyCalls.get())
        assertEquals("pending-token", store.snapshot.pendingPurchases.single().purchaseToken)
    }

    @Test
    fun failure_userCancelledPurchase_isCancelledWithoutPendingOrPro() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier(immediate = true)
        val gateway = FakeGateway()
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitRepositoryRead(repository)

        // When
        gateway.emit(PurchaseResult.UserCancelled)
        kotlinx.coroutines.yield()

        // Then
        assertFalse(awaitRepositoryRead(repository))
        assertFalse(repository.isProHot.value == true)
        assertTrue(store.snapshot.pendingPurchases.isEmpty())
        assertEquals(0, verifier.verifyCalls.get())
    }

    @Test
    fun failure_verify409_preservesExistingValidSnapshotAndRetainsRetryToken() = runTest {
        // Given
        val existing = activeSnapshot(jwt = "existing-jwt", expiryOffsetSeconds = 7L * 86_400L)
        val store = FakeEntitlementStore(existing)
        val gateway = FakeGateway(owned = listOf(purchased("conflict-token")))
        val repository = newRepository(
            store,
            entitlementApiClientReturning(409, "{not-json"),
        )

        // When
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { store.snapshot.pendingPurchases.any { it.purchaseToken == "conflict-token" } }

        // Then: a conflict is a restore conflict, not permission to erase a valid snapshot.
        assertEquals(existing.jwt, store.snapshot.jwt)
        assertEquals(existing.expiresAtEpochSeconds, store.snapshot.expiresAtEpochSeconds)
        assertTrue(store.snapshot.isActive)
        assertTrue(awaitRepositoryRead(repository))
        assertTrue(repository.isProHot.value == true)
        assertEquals("conflict-token", store.snapshot.pendingPurchases.single().purchaseToken)
    }

    @Test
    fun failure_refresh403_revokesSnapshotAndRemovesPro() = runTest {
        // Given
        val store = FakeEntitlementStore(activeSnapshot("revoked-jwt", expiryOffsetSeconds = 3_600L))
        val verifier = CountingEntitlementVerifier(
            entitlementApiClientReturning(
                code = 403,
                body = "{\"error\":\"revoked\"}",
            ),
        )
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(FakeGateway()))
        awaitCondition { verifier.refreshCalls.get() == 1 }
        awaitCondition { !store.snapshot.isActive }

        // Then
        assertFalse(awaitRepositoryRead(repository))
        assertFalse(repository.isProHot.value == true)
        assertFalse(store.snapshot.isActive)
        assertEquals("revoked-jwt", store.snapshot.jwt)
    }

    @Test
    fun success_refresh503BeforeExpiry_keepsCachedActiveSnapshot() = runTest {
        // Given
        val existing = activeSnapshot("cached-jwt", expiryOffsetSeconds = 3_600L)
        val store = FakeEntitlementStore(existing)
        val verifier = FakeEntitlementVerifier(
            refreshResult = EntitlementApiResult.Failure(EntitlementFailureKind.Unavailable, 503),
        )
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(FakeGateway()))
        awaitCondition { verifier.refreshCalls.get() == 1 }

        // Then
        assertTrue(awaitRepositoryRead(repository))
        assertTrue(repository.isProHot.value == true)
        assertEquals(existing, store.snapshot)
    }

    @Test
    fun failure_refresh503AfterJwtExpiry_isExpiredAndNotPro() = runTest {
        // Given: refresh starts while the JWT is valid, then its 503 arrives after expiry.
        // Production owns the wall clock and exposes no clock override; the refresh gate makes
        // the response ordering deterministic while the one-second boundary keeps this test short.
        val expiry = System.currentTimeMillis() / 1_000L + 1L
        val store = FakeEntitlementStore(activeSnapshotAtEpoch("expiring-jwt", expiry))
        val verifier = FakeEntitlementVerifier()
        val refreshGate = CompletableDeferred<EntitlementApiResult>()
        verifier.refreshGate = refreshGate
        val repository = newRepository(store, verifier)

        // When
        assertTrue(repository.attachGateway(FakeGateway()))
        awaitCondition { verifier.refreshCalls.get() == 1 }
        awaitCondition { System.currentTimeMillis() / 1_000L >= expiry }
        refreshGate.complete(EntitlementApiResult.Failure(EntitlementFailureKind.Unavailable, 503))

        // Then
        assertFalse(awaitRepositoryRead(repository))
        assertFalse(repository.isProHot.value == true)
        assertEquals(expiry, store.snapshot.expiresAtEpochSeconds)
    }

    @Test
    fun success_verify503_stagesPendingThenNextOwnedQueryUsesActualApiAndCommits() = runTest {
        // Given: the real API client maps the first HTTP 503 to retry-pending.
        val store = FakeEntitlementStore()
        val gateway = FakeGateway(owned = listOf(purchased("new-token", isAcknowledged = false)))
        val repository = newRepository(
            store,
            entitlementApiClientSequence(
                HttpResponse(503, "service unavailable"),
                HttpResponse(
                    200,
                    "{\"ok\":true,\"data\":{\"jwt\":\"verified-jwt\",\"expires_at\":4102444800}}",
                ),
            ),
        )

        // When: first owned query reaches the actual API and receives HTTP 503.
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { store.snapshot.pendingPurchases.any { it.purchaseToken == "new-token" } }

        // Then: pending is retained and no unverified entitlement is committed.
        assertFalse(awaitRepositoryRead(repository))
        assertEquals(0, store.verifiedCommits)
        assertEquals("new-token", store.snapshot.pendingPurchases.single().purchaseToken)

        // When: the next owned query reaches the same actual API and succeeds.
        repository.onStart(ProcessLifecycleOwner.get())
        awaitRepositoryRead(repository)

        // Then: the pending purchase is committed only after the successful API response.
        assertTrue(awaitRepositoryRead(repository))
        assertTrue(repository.isProHot.value == true)
        assertTrue(store.snapshot.pendingPurchases.isEmpty())
        assertEquals("new-token", store.committedPurchases.single().purchaseToken)
    }

    @Test
    fun failure_networkPurchaseResult_isNotReportedAsUserCancelled() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier(immediate = true)
        val gateway = FakeGateway()
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitRepositoryRead(repository)

        // When
        gateway.emit(PurchaseResult.Failed(BillingFailureKind.Network))
        gateway.emit(PurchaseResult.UserCancelled)
        kotlinx.coroutines.yield()

        // Then
        assertTrue(store.snapshot.pendingPurchases.isEmpty())
        assertEquals(0, verifier.verifyCalls.get())
        assertFalse(repository.isProHot.value == true)
    }

    @Test
    fun exception_parentCancellationPropagatesInsteadOfMappingToNetworkOrUserCancelled() = runTest {
        // Given: verification is suspended inside a repository whose parent owns its process scope.
        val parentJob = Job()
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier()
        val gateway = FakeGateway(owned = listOf(purchased("cancelled-verify-token")))
        val repository = newRepository(
            store = store,
            verifier = verifier,
            dispatcher = Dispatchers.Default,
            parentJob = parentJob,
        )
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { verifier.verifyCalls.get() == 1 }

        // When: the actual parent scope is cancelled while verify is suspended.
        parentJob.cancel()
        val cancellationCause = verifier.verifyCancellationCause.await()
        verifier.verifyCancellation.await()

        // Then: cancellation is propagated; no network/cancelled purchase result or commit appears.
        assertTrue(cancellationCause is kotlinx.coroutines.CancellationException)
        assertTrue(
            gateway.emittedResults.none {
                it == PurchaseResult.UserCancelled ||
                    it == PurchaseResult.Failed(BillingFailureKind.Network)
            },
        )
        assertEquals(0, store.verifiedCommits)
        assertEquals("cancelled-verify-token", store.snapshot.pendingPurchases.single().purchaseToken)
    }

    @Test
    fun success_unacknowledgedPurchase_isRetriedByNextOwnedQuery() = runTest {
        // Given
        val store = FakeEntitlementStore()
        val verifier = FakeEntitlementVerifier()
        verifier.enqueueVerifyResult(EntitlementApiResult.Failure(EntitlementFailureKind.Unavailable, 503))
        verifier.enqueueVerifyResult(successfulVerifyResult())
        val gateway = FakeGateway(owned = listOf(purchased("unack-token", isAcknowledged = false)))
        val repository = newRepository(store, verifier)
        assertTrue(repository.attachGateway(gateway))
        awaitCondition { verifier.verifyCalls.get() == 1 }
        assertEquals("unack-token", store.snapshot.pendingPurchases.single().purchaseToken)

        // When: a later owned-purchase query is requested through the lifecycle seam.
        repository.onStart(ProcessLifecycleOwner.get())
        awaitRepositoryRead(repository)

        // Then
        assertTrue(awaitRepositoryRead(repository))
        assertTrue(repository.isProHot.value == true)
        assertTrue(store.snapshot.pendingPurchases.isEmpty())
        assertEquals("unack-token", verifier.lastToken)
        assertTrue(store.pendingWrites.size >= 2)
        assertTrue(store.pendingWrites.all { it.purchaseToken == "unack-token" })
        assertEquals(false, store.committedPurchases.single().isAcknowledged)

        // When: the same owned purchase is observed again after it was already committed.
        repository.onStart(ProcessLifecycleOwner.get())
        assertTrue(awaitRepositoryRead(repository))

        // Then: repository idempotency prevents a duplicate verify or commit.
        assertEquals(2, verifier.verifyCalls.get())
        assertEquals(1, store.verifiedCommits)
        assertEquals(1, store.committedPurchases.size)
    }

    private fun newRepository(
        store: FakeEntitlementStore,
        verifier: EntitlementVerifier,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        parentJob: Job? = null,
    ): EntitlementRepository = EntitlementRepository(
        context = ApplicationProvider.getApplicationContext<Application>(),
        storeOverride = store,
        apiClientOverride = verifier,
        processScopeOverride = CoroutineScope(SupervisorJob(parentJob) + dispatcher),
        observeProcessLifecycle = false,
    ).also(repositories::add)

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                while (!condition()) delay(1L)
            }
        }
    }

    private suspend fun awaitRepositoryRead(repository: EntitlementRepository): Boolean =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(1_000L) { repository.isProSnapshot() }
        }

    private fun purchased(token: String, isAcknowledged: Boolean = true) = OwnedPurchase(
        store = StoreCapabilities.GOOGLE_PLAY_STORE_ID,
        productId = PRO_PRODUCT_ID,
        purchaseToken = token,
        state = PurchaseState.Purchased,
        isAcknowledged = isAcknowledged,
    )

    private fun pending(token: String) = OwnedPurchase(
        store = StoreCapabilities.GOOGLE_PLAY_STORE_ID,
        productId = PRO_PRODUCT_ID,
        purchaseToken = token,
        state = PurchaseState.Pending,
        isAcknowledged = false,
    )

    private fun activeSnapshot(jwt: String, expiryOffsetSeconds: Long) = Snapshot(
        jwt = jwt,
        expiresAtEpochSeconds = System.currentTimeMillis() / 1_000L + expiryOffsetSeconds,
        isActive = true,
        pendingPurchases = emptyList(),
    )

    private fun activeSnapshotAtEpoch(jwt: String, expiryEpochSeconds: Long) = Snapshot(
        jwt = jwt,
        expiresAtEpochSeconds = expiryEpochSeconds,
        isActive = true,
        pendingPurchases = emptyList(),
    )

    private fun entitlementApiClientReturning(code: Int, body: String): EntitlementApiClient =
        EntitlementApiClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            baseUrl = "https://billing.test",
        )

    private fun entitlementApiClientSequence(vararg responses: HttpResponse): EntitlementApiClient {
        val remaining = ArrayDeque(responses.toList())
        return EntitlementApiClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val response = synchronized(remaining) {
                        check(remaining.isNotEmpty()) { "Unexpected extra entitlement request" }
                        remaining.removeFirst()
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(response.code)
                        .message("test")
                        .body(response.body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            baseUrl = "https://billing.test",
        )
    }

    private data class HttpResponse(
        val code: Int,
        val body: String,
    )

    private class CountingEntitlementVerifier(
        private val delegate: EntitlementVerifier,
    ) : EntitlementVerifier {
        val refreshCalls = AtomicInteger()

        override suspend fun verify(
            installId: String,
            store: String,
            productId: String,
            purchaseToken: String,
        ): EntitlementApiResult = delegate.verify(installId, store, productId, purchaseToken)

        override suspend fun refresh(
            jwt: String,
            installId: String,
        ): EntitlementApiResult {
            refreshCalls.incrementAndGet()
            return delegate.refresh(jwt, installId)
        }
    }

    private class FakeGateway(
        private var owned: List<OwnedPurchase> = emptyList(),
    ) : BillingGateway {
        override val store = StoreCapabilities.GOOGLE_PLAY_STORE_ID
        private val ownedLock = Any()
        val emittedResults = Collections.synchronizedList(mutableListOf<PurchaseResult>())
        private val updates = MutableSharedFlow<PurchaseResult>(replay = 1, extraBufferCapacity = 32)
        override val purchaseUpdates: Flow<PurchaseResult> = updates

        override suspend fun queryProductDetails() = BillingGatewayResult.Success(
            ProductDetails(PRO_PRODUCT_ID, null, null, null),
        )

        override fun launchPurchase(activity: Activity) = BillingGatewayResult.Success(Unit)

        override suspend fun queryOwnedPurchases() = BillingGatewayResult.Success(
            synchronized(ownedLock) { owned.toList() },
        )

        override fun resultFromActivityResult(resultCode: Int, intent: Intent?) = PurchaseResult.Cancelled

        override fun close() = Unit

        fun emit(result: PurchaseResult) {
            emittedResults += result
            updates.tryEmit(result)
        }

        fun setOwned(purchases: List<OwnedPurchase>) {
            synchronized(ownedLock) { owned = purchases.toList() }
        }
    }

    private class FakeEntitlementVerifier(
        private val immediate: Boolean = false,
        var refreshResult: EntitlementApiResult =
            EntitlementApiResult.Failure(EntitlementFailureKind.NotConfigured),
    ) : EntitlementVerifier {
        val verifyGate = CompletableDeferred<EntitlementApiResult>()
        val verifyCancellation = CompletableDeferred<Unit>()
        val verifyCancellationCause = CompletableDeferred<kotlinx.coroutines.CancellationException>()
        val verifyCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        private val queuedVerifyResults = ArrayDeque<EntitlementApiResult>()
        var lastToken: String? = null
        var lastInstallId: String? = null
        var refreshGate: CompletableDeferred<EntitlementApiResult>? = null

        fun enqueueVerifyResult(result: EntitlementApiResult) {
            synchronized(queuedVerifyResults) { queuedVerifyResults.addLast(result) }
        }

        override suspend fun verify(
            installId: String,
            store: String,
            productId: String,
            purchaseToken: String,
        ): EntitlementApiResult {
            verifyCalls.incrementAndGet()
            lastToken = purchaseToken
            lastInstallId = installId
            val queuedResult = synchronized(queuedVerifyResults) {
                queuedVerifyResults.pollFirst()
            }
            return try {
                queuedResult ?: if (immediate) successfulVerifyResult() else verifyGate.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                verifyCancellationCause.complete(error)
                verifyCancellation.complete(Unit)
                throw error
            }
        }

        override suspend fun refresh(jwt: String, installId: String): EntitlementApiResult {
            refreshCalls.incrementAndGet()
            return refreshGate?.await() ?: refreshResult
        }
    }

    private class FakeEntitlementStore(
        initialSnapshot: Snapshot = Snapshot(null, null, false, emptyList()),
    ) : EntitlementStore {
        private val stateLock = Any()
        private var storedSnapshot = initialSnapshot
        private var storedVerifiedCommits = 0
        var snapshot: Snapshot
            get() = synchronized(stateLock) { storedSnapshot }
            set(value) {
                synchronized(stateLock) { storedSnapshot = value }
            }
        var verifiedCommits: Int
            get() = synchronized(stateLock) { storedVerifiedCommits }
            set(value) {
                synchronized(stateLock) { storedVerifiedCommits = value }
            }
        val pendingWrites = Collections.synchronizedList(mutableListOf<PendingPurchase>())
        val committedPurchases = Collections.synchronizedList(mutableListOf<OwnedPurchase>())

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
            committedPurchases += processedPurchase
            snapshot = snapshot.copy(
                jwt = jwt,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                isActive = isActive,
                pendingPurchases = snapshot.pendingPurchases.filterNot {
                    it.store == processedPurchase.store &&
                        it.purchaseToken == processedPurchase.purchaseToken
                },
            )
        }

        override suspend fun upsertPendingPurchase(purchase: PendingPurchase) {
            pendingWrites += purchase
            if (snapshot.pendingPurchases.none { it.store == purchase.store && it.purchaseToken == purchase.purchaseToken }) {
                snapshot = snapshot.copy(pendingPurchases = snapshot.pendingPurchases + purchase)
            }
        }

        override suspend fun removePendingPurchase(purchase: PendingPurchase) {
            snapshot = snapshot.copy(
                pendingPurchases = snapshot.pendingPurchases.filterNot {
                    it.store == purchase.store && it.purchaseToken == purchase.purchaseToken
                },
            )
        }
    }
}
