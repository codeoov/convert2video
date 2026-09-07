package com.example.convert2video.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails as GoogleProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Adapter seam around the final BillingClient API. It keeps provider callbacks testable. */
internal interface GoogleBillingClientAdapter {
    val isReady: Boolean

    fun setPurchaseUpdateListener(listener: (BillingResult, List<Purchase>) -> Unit)

    fun startConnection(
        onSetupFinished: (BillingResult) -> Unit,
        onServiceDisconnected: () -> Unit,
    )

    fun queryProductDetails(
        onResponse: (BillingResult, List<GoogleProductDetails>) -> Unit,
    )

    fun queryPurchases(
        onResponse: (BillingResult, List<Purchase>) -> Unit,
    )

    fun launchBillingFlow(
        activity: Activity,
        productDetails: GoogleProductDetails,
    ): BillingResult

    fun endConnection()
}

internal data class GoogleBillingTiming(
    val connectionTimeoutMillis: Long = GoogleBillingTimeouts.CONNECTION_SETUP_MILLIS,
    val queryTimeoutMillis: Long = GoogleBillingTimeouts.QUERY_CALLBACK_MILLIS,
    val launchWatchdogMillis: Long = GoogleBillingTimeouts.LAUNCH_WATCHDOG_MILLIS,
    val retryDelay: suspend (Long) -> Unit = { delay(it) },
    val launchWatchdogDelay: suspend (Long) -> Unit = { delay(it) },
)

/** Billing callback timeout SSOT for the local 7.1.1 client. */
internal object GoogleBillingTimeouts {
    const val CONNECTION_SETUP_MILLIS = 5_000L
    const val QUERY_CALLBACK_MILLIS = 10_000L
    /** Clears a launch guard when neither callback nor activity result arrives. */
    const val LAUNCH_WATCHDOG_MILLIS = 5 * 60 * 1000L
}

/** Google Play Billing implementation of the store-neutral gateway. */
internal class GoogleBillingGateway(
    context: Context,
    adapterOverride: GoogleBillingClientAdapter? = null,
    private val timing: GoogleBillingTiming = GoogleBillingTiming(),
) : CallbackBillingGateway(StoreCapabilities.GOOGLE_PLAY_STORE_ID) {

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val launchInFlight = AtomicBoolean(false)
    private val recoveryInFlight = AtomicBoolean(false)
    private val alreadyOwnedRecoveryRequested = AtomicBoolean(false)
    private val endConnectionStarted = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0L)
    private val launchAttemptId = AtomicLong(0L)
    private val cachedProduct = AtomicReference<CachedProduct?>(null)
    private val cacheLock = Any()
    private val connectionMutex = Mutex()
    private val queryMutex = Mutex()
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeWaiters = ConcurrentHashMap.newKeySet<ActiveWaiter>()
    private val client = adapterOverride ?: AndroidGoogleBillingClientAdapter(appContext)

    init {
        client.setPurchaseUpdateListener(::handlePurchaseUpdate)
    }

    override suspend fun queryProductDetails(): BillingGatewayResult<ProductDetails> =
        queryMutex.withLock {
            if (closed.get()) return@withLock BillingGatewayResult.Failure(BillingFailureKind.Unavailable)

            synchronized(cacheLock) {
                val existing = cachedProduct.get()
                if (existing?.productId == PRO_PRODUCT_ID && isConnected()) {
                    return@withLock BillingGatewayResult.Success(existing.value)
                }
                if (existing != null) cachedProduct.set(null)
            }

            queryProductDetailsLocked()
        }

    override fun launchPurchase(activity: Activity): BillingGatewayResult<Unit> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return BillingGatewayResult.Failure(BillingFailureKind.LaunchFailed)
        }
        return synchronized(cacheLock) {
            if (closed.get()) return@synchronized BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            if (launchInFlight.get()) return@synchronized BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            if (!isConnected()) {
                cachedProduct.set(null)
                return@synchronized BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            }
            val product = cachedProduct.get()
                ?.takeIf { it.productId == PRO_PRODUCT_ID }
                ?: return@synchronized BillingGatewayResult.Failure(BillingFailureKind.InvalidProduct)
            val generation = lifecycleGeneration.get()
            if (!isGenerationActive(generation) || !isConnected()) {
                cachedProduct.set(null)
                return@synchronized BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            }
            if (!launchInFlight.compareAndSet(false, true)) {
                return@synchronized BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            }
            alreadyOwnedRecoveryRequested.set(false)

            val result = try {
                // Hold validation and the SDK call under the same lock as cache invalidation.
                client.launchBillingFlow(activity, product.actual)
            } catch (error: Exception) {
                clearLaunchInFlight()
                AppLogger.e(TAG, "launchBillingFlow failed: ${error.javaClass.simpleName}")
                return@synchronized BillingGatewayResult.Failure(BillingFailureKind.LaunchFailed)
            }

            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    armLaunchWatchdog()
                    BillingGatewayResult.Success(Unit)
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    clearLaunchInFlight()
                    scheduleAlreadyOwnedRecovery()
                    BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
                }
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                    clearLaunchInFlight()
                    handleServiceDisconnected()
                    BillingGatewayResult.Failure(BillingFailureKind.Network)
                }
                else -> {
                    clearLaunchInFlight()
                    BillingGatewayResult.Failure(mapFailureKind(result.responseCode))
                }
            }
        }
    }

    override suspend fun queryOwnedPurchases(): BillingGatewayResult<List<OwnedPurchase>> =
        queryMutex.withLock { queryOwnedPurchasesLocked() }

    override fun resultFromActivityResult(resultCode: Int, intent: Intent?): PurchaseResult =
        run {
            // Activity results are terminal for the one-shot launch guard. Billing state is not
            // encoded in this Intent; purchase callbacks and P5 owned reconciliation are the
            // entitlement authorities.
            clearLaunchInFlight()
            if (resultCode == Activity.RESULT_CANCELED) {
            PurchaseResult.UserCancelled
            } else {
                PurchaseResult.Failed(BillingFailureKind.InvalidResponse)
            }
        }

    override fun onClosed() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleGeneration.incrementAndGet()
        ready.set(false)
        clearLaunchInFlight()
        recoveryInFlight.set(false)
        alreadyOwnedRecoveryRequested.set(false)
        synchronized(cacheLock) { cachedProduct.set(null) }
        activeWaiters.toList().forEach(ActiveWaiter::close)
        activeWaiters.clear()
        gatewayScope.cancel()
        endConnectionOnMainOnce()
    }

    private suspend fun queryOwnedPurchasesLocked(): BillingGatewayResult<List<OwnedPurchase>> {
        if (closed.get()) return BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
        val budget = ConnectionBudget()
        var disconnectedRetryUsed = false
        while (true) {
            when (val connection = ensureConnected(budget)) {
                is BillingGatewayResult.Failure -> return connection
                is BillingGatewayResult.NeedsResolution -> return connection
                is BillingGatewayResult.Success -> Unit
            }

            when (val response = queryOwnedPurchasesCallback()) {
                null -> return BillingGatewayResult.Failure(
                    if (closed.get()) BillingFailureKind.Unavailable else BillingFailureKind.Network,
                ).also { clearCachedProduct() }
                is OwnedQueryResponse.Disconnected -> {
                    clearCachedProduct()
                    if (disconnectedRetryUsed) return BillingGatewayResult.Failure(BillingFailureKind.Network)
                    disconnectedRetryUsed = true
                }
                is OwnedQueryResponse.Result -> {
                    if (!isGenerationActive(response.generation)) {
                        return BillingGatewayResult.Failure(
                            if (closed.get()) BillingFailureKind.Unavailable else BillingFailureKind.Network,
                        )
                    }
                    if (response.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        clearCachedProduct()
                        return BillingGatewayResult.Failure(mapFailureKind(response.billingResult.responseCode))
                    }
                    return normalizeOwnedPurchases(response.purchases)
                }
            }
        }
    }

    private suspend fun queryProductDetailsLocked(): BillingGatewayResult<ProductDetails> {
        val budget = ConnectionBudget()
        var disconnectedRetryUsed = false
        while (true) {
            when (val connection = ensureConnected(budget)) {
                is BillingGatewayResult.Failure -> return connection
                is BillingGatewayResult.NeedsResolution -> return connection
                is BillingGatewayResult.Success -> Unit
            }

            when (val response = queryProductDetailsCallback()) {
                null -> return BillingGatewayResult.Failure(
                    if (closed.get()) BillingFailureKind.Unavailable else BillingFailureKind.Network,
                ).also { clearCachedProduct() }
                is ProductQueryResponse.Disconnected -> {
                    clearCachedProduct()
                    if (disconnectedRetryUsed) return BillingGatewayResult.Failure(BillingFailureKind.Network)
                    disconnectedRetryUsed = true
                }
                is ProductQueryResponse.Result -> {
                    if (!isGenerationActive(response.generation)) {
                        clearCachedProduct()
                        return BillingGatewayResult.Failure(
                            if (closed.get()) BillingFailureKind.Unavailable else BillingFailureKind.Network,
                        )
                    }
                    return mapProductResponse(response)
                }
            }
        }
    }

    private suspend fun ensureConnected(budget: ConnectionBudget): BillingGatewayResult<Unit> {
        if (closed.get()) return BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
        if (isConnected()) return BillingGatewayResult.Success(Unit)

        return connectionMutex.withLock {
            if (closed.get()) return@withLock BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            if (isConnected()) return@withLock BillingGatewayResult.Success(Unit)

            var lastFailure = BillingFailureKind.Network
            while (budget.attemptsUsed < MAX_CONNECTION_ATTEMPTS) {
                budget.attemptsUsed += 1
                val connectionAttempt = budget.attemptsUsed
                val generation = lifecycleGeneration.get()
                if (connectionAttempt > 1) timing.retryDelay(RETRY_DELAYS_MILLIS[connectionAttempt - 2])
                if (closed.get()) return@withLock BillingGatewayResult.Failure(BillingFailureKind.Unavailable)

                when (val response = connectOnce(generation)) {
                    ConnectionResponse.Connected -> {
                        if (!isGenerationActive(generation)) {
                            ready.set(false)
                            if (closed.get()) {
                                return@withLock BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
                            }
                            lastFailure = BillingFailureKind.Network
                            continue
                        }
                        ready.set(true)
                        clearCachedProduct()
                        // A successful provider connection resets the provider sequence; the
                        // operation budget remains consumed and cannot be reset by this query.
                        return@withLock BillingGatewayResult.Success(Unit)
                    }
                    ConnectionResponse.Disconnected -> lastFailure = BillingFailureKind.Network
                    is ConnectionResponse.Failed -> lastFailure = mapFailureKind(response.billingResult.responseCode)
                    ConnectionResponse.Timeout -> lastFailure = BillingFailureKind.Network
                    ConnectionResponse.Closed ->
                        return@withLock BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
                }
            }
            BillingGatewayResult.Failure(lastFailure)
        }
    }

    private suspend fun connectOnce(generation: Long): ConnectionResponse {
        val response = try {
            withTimeoutOrNull(timing.connectionTimeoutMillis) {
                awaitCallback<ConnectionResponse>(
                    onDisconnected = { ConnectionResponse.Disconnected },
                ) { complete ->
                    withContext(Dispatchers.Main.immediate) {
                        if (!isGenerationActive(generation)) return@withContext
                        client.startConnection(
                            onSetupFinished = { result ->
                                if (isGenerationActive(generation)) {
                                    if (result.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                                        if (complete(ConnectionResponse.Disconnected)) handleServiceDisconnected()
                                    } else {
                                        complete(ConnectionResponse.from(result))
                                    }
                                }
                            },
                            onServiceDisconnected = {
                                if (isGenerationActive(generation)) {
                                    if (complete(ConnectionResponse.Disconnected)) handleServiceDisconnected()
                                }
                            },
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "startConnection failed: ${error.javaClass.simpleName}")
            ConnectionResponse.Failed(providerErrorResult())
        }
        return response ?: if (closed.get()) ConnectionResponse.Closed else ConnectionResponse.Timeout
    }

    private suspend fun queryProductDetailsCallback(): ProductQueryResponse? {
        val generation = lifecycleGeneration.get()
        return try {
            withTimeoutOrNull(timing.queryTimeoutMillis) {
                awaitCallback<ProductQueryResponse>(
                    onDisconnected = { ProductQueryResponse.Disconnected },
                ) { complete ->
                    withContext(Dispatchers.Main.immediate) {
                        if (!isGenerationActive(generation)) {
                            complete(ProductQueryResponse.Disconnected)
                            return@withContext
                        }
                        client.queryProductDetails { billingResult, products ->
                            if (billingResult.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                                if (complete(ProductQueryResponse.Disconnected)) handleServiceDisconnected()
                            } else {
                                complete(ProductQueryResponse.Result(generation, billingResult, products))
                            }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "queryProductDetails failed: ${error.javaClass.simpleName}")
            ProductQueryResponse.Result(generation, providerErrorResult(), emptyList())
        }
    }

    private suspend fun queryOwnedPurchasesCallback(): OwnedQueryResponse? {
        val generation = lifecycleGeneration.get()
        return try {
            withTimeoutOrNull(timing.queryTimeoutMillis) {
                awaitCallback<OwnedQueryResponse>(
                    onDisconnected = { OwnedQueryResponse.Disconnected },
                ) { complete ->
                    withContext(Dispatchers.Main.immediate) {
                        if (!isGenerationActive(generation)) {
                            complete(OwnedQueryResponse.Disconnected)
                            return@withContext
                        }
                        client.queryPurchases { billingResult, purchases ->
                            if (billingResult.responseCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                                if (complete(OwnedQueryResponse.Disconnected)) handleServiceDisconnected()
                            } else {
                                complete(OwnedQueryResponse.Result(generation, billingResult, purchases))
                            }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "queryPurchases failed: ${error.javaClass.simpleName}")
            OwnedQueryResponse.Result(generation, providerErrorResult(), emptyList())
        }
    }

    private suspend fun <T> awaitCallback(
        onDisconnected: () -> T,
        register: suspend (complete: (T) -> Boolean) -> Unit,
    ): T? {
        val waiter = CallbackWaiter(onDisconnected)
        activeWaiters += waiter
        try {
            if (closed.get()) {
                waiter.close()
            } else {
                register(waiter::complete)
                if (closed.get()) waiter.close()
            }
            return when (val signal = waiter.result.await()) {
                is CallbackSignal.Value -> signal.value
                CallbackSignal.Closed -> null
            }
        } finally {
            activeWaiters.remove(waiter)
            waiter.cancel()
        }
    }

    private fun mapProductResponse(response: ProductQueryResponse.Result): BillingGatewayResult<ProductDetails> {
        synchronized(cacheLock) {
            if (!isGenerationActive(response.generation)) {
                cachedProduct.set(null)
                return BillingGatewayResult.Failure(
                    if (closed.get()) BillingFailureKind.Unavailable else BillingFailureKind.Network,
                )
            }
            if (response.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                cachedProduct.set(null)
                return BillingGatewayResult.Failure(mapFailureKind(response.billingResult.responseCode))
            }
            val actual = response.products.singleOrNull()
            val offer = actual?.takeIf { it.productId == PRO_PRODUCT_ID }
                ?.takeIf { it.productType == BillingClient.ProductType.INAPP }
                ?.getOneTimePurchaseOfferDetails()
            if (actual == null || offer == null) {
                cachedProduct.set(null)
                return BillingGatewayResult.Failure(BillingFailureKind.InvalidResponse)
            }

            val value = ProductDetails(
                productId = actual.productId,
                title = actual.title,
                description = actual.description,
                formattedPrice = offer.formattedPrice,
            )
            cachedProduct.set(
                CachedProduct(
                    productId = PRO_PRODUCT_ID,
                    actual = actual,
                    value = value,
                ),
            )
            return BillingGatewayResult.Success(value)
        }
    }

    private fun normalizeOwnedPurchases(
        purchases: List<Purchase>,
    ): BillingGatewayResult<List<OwnedPurchase>> {
        val normalized = ArrayList<OwnedPurchase>(purchases.size)
        for (purchase in purchases) {
            if (!purchase.products.contains(PRO_PRODUCT_ID)) continue
            val owned = normalizePurchase(purchase)
                ?: return BillingGatewayResult.Failure(BillingFailureKind.InvalidResponse)
            normalized += owned
        }
        return BillingGatewayResult.Success(normalized)
    }

    private fun normalizePurchase(purchase: Purchase): OwnedPurchase? {
        if (purchase.purchaseToken.isBlank()) return null
        val state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.Purchased
            Purchase.PurchaseState.PENDING -> PurchaseState.Pending
            else -> return null
        }
        return OwnedPurchase(
            store = StoreCapabilities.GOOGLE_PLAY_STORE_ID,
            productId = PRO_PRODUCT_ID,
            purchaseToken = purchase.purchaseToken,
            state = state,
            isAcknowledged = purchase.isAcknowledged,
        )
    }

    private fun handlePurchaseUpdate(
        billingResult: BillingResult,
        purchases: List<Purchase>,
    ) {
        if (closed.get()) return
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                if (launchInFlight.get()) {
                    clearLaunchInFlight()
                    scheduleAlreadyOwnedRecovery()
                } else if (recoveryInFlight.get()) {
                    scheduleAlreadyOwnedRecovery()
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                clearLaunchInFlight()
                emitPurchaseUpdate(PurchaseResult.UserCancelled)
            }
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isEmpty()) {
                    if (launchInFlight.get()) clearLaunchInFlight()
                    emitPurchaseUpdate(PurchaseResult.Failed(BillingFailureKind.InvalidResponse))
                    return
                }
                var hadMatchingPurchase = false
                for (purchase in purchases) {
                    if (!purchase.products.contains(PRO_PRODUCT_ID)) continue
                    hadMatchingPurchase = true
                    val owned = normalizePurchase(purchase)
                    if (owned == null) {
                        if (launchInFlight.get()) clearLaunchInFlight()
                        emitPurchaseUpdate(PurchaseResult.Failed(BillingFailureKind.InvalidResponse))
                        return
                    }
                    emitPurchaseUpdate(
                        when (owned.state) {
                            PurchaseState.Purchased -> PurchaseResult.Purchased(owned)
                            PurchaseState.Pending -> PurchaseResult.Pending(owned)
                        },
                    )
                }
                if (!hadMatchingPurchase) {
                    emitPurchaseUpdate(PurchaseResult.ProductMismatch)
                    return
                }
                if (launchInFlight.get()) clearLaunchInFlight()
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                if (launchInFlight.get()) clearLaunchInFlight()
                handleServiceDisconnected()
                emitPurchaseUpdate(PurchaseResult.Failed(BillingFailureKind.Network))
            }
            else -> {
                if (launchInFlight.get()) clearLaunchInFlight()
                emitPurchaseUpdate(PurchaseResult.Failed(mapFailureKind(billingResult.responseCode)))
            }
        }
    }

    private fun clearLaunchInFlight() {
        launchInFlight.set(false)
        launchAttemptId.incrementAndGet()
    }

    private fun armLaunchWatchdog() {
        val attemptId = launchAttemptId.incrementAndGet()
        gatewayScope.launch {
            try {
                timing.launchWatchdogDelay(timing.launchWatchdogMillis)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.e(TAG, "launch watchdog failed: ${error.javaClass.simpleName}")
            }
            if (!closed.get() &&
                launchAttemptId.get() == attemptId &&
                launchInFlight.compareAndSet(true, false)
            ) {
                launchAttemptId.compareAndSet(attemptId, attemptId + 1L)
            }
        }
    }

    private fun scheduleAlreadyOwnedRecovery() {
        if (closed.get() || alreadyOwnedRecoveryRequested.get()) return
        if (!recoveryInFlight.compareAndSet(false, true)) return
        if (!alreadyOwnedRecoveryRequested.compareAndSet(false, true)) {
            recoveryInFlight.set(false)
            return
        }
        gatewayScope.launch {
            try {
                when (val result = queryMutex.withLock { queryOwnedPurchasesLocked() }) {
                    is BillingGatewayResult.Success -> {
                        val matching = result.value
                        if (matching.isEmpty()) {
                            emitPurchaseUpdate(PurchaseResult.AlreadyOwned)
                        } else {
                            matching.forEach { purchase ->
                                emitPurchaseUpdate(
                                    when (purchase.state) {
                                        PurchaseState.Purchased -> PurchaseResult.Purchased(purchase)
                                        PurchaseState.Pending -> PurchaseResult.Pending(purchase)
                                    },
                                )
                            }
                        }
                    }
                    is BillingGatewayResult.Failure ->
                        emitPurchaseUpdate(PurchaseResult.Failed(result.kind))
                    is BillingGatewayResult.NeedsResolution ->
                        emitPurchaseUpdate(PurchaseResult.Failed(BillingFailureKind.ProviderError))
                }
            } finally {
                recoveryInFlight.set(false)
            }
        }
    }

    private fun handleServiceDisconnected() {
        if (closed.get()) return
        synchronized(cacheLock) {
            if (closed.get()) return
            lifecycleGeneration.incrementAndGet()
            ready.set(false)
            cachedProduct.set(null)
        }
        activeWaiters.toList().forEach(ActiveWaiter::serviceDisconnected)
    }

    private fun clearCachedProduct() {
        synchronized(cacheLock) { cachedProduct.set(null) }
    }

    private fun isConnected(): Boolean =
        !closed.get() && ready.get() && runCatching { client.isReady }.getOrDefault(false)

    private fun isGenerationActive(generation: Long): Boolean =
        !closed.get() && lifecycleGeneration.get() == generation

    private fun endConnectionOnMainOnce() {
        if (!endConnectionStarted.compareAndSet(false, true)) return
        val end = {
            try {
                client.endConnection()
            } catch (error: Exception) {
                AppLogger.e(TAG, "endConnection failed: ${error.javaClass.simpleName}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            end()
        } else {
            Handler(Looper.getMainLooper()).post(end)
        }
    }

    // TODO(2026-09-06): Billing 7.1.1 deprecates this response constant, but the provider
    // timeout must remain mapped to the common Network failure category.
    @Suppress("DEPRECATION")
    private fun mapFailureKind(responseCode: Int): BillingFailureKind = when (responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> BillingFailureKind.Unavailable
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        -> BillingFailureKind.Network
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingFailureKind.InvalidProduct
        BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        BillingClient.BillingResponseCode.ERROR,
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
        BillingClient.BillingResponseCode.USER_CANCELED -> BillingFailureKind.ProviderError
        else -> BillingFailureKind.ProviderError
    }

    private fun providerErrorResult(): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.ERROR)
            .build()

    private data class CachedProduct(
        val productId: String,
        val actual: GoogleProductDetails,
        val value: ProductDetails,
    )

    private sealed interface ConnectionResponse {
        data object Connected : ConnectionResponse
        data object Disconnected : ConnectionResponse
        data object Timeout : ConnectionResponse
        data object Closed : ConnectionResponse
        data class Failed(val billingResult: BillingResult) : ConnectionResponse

        companion object {
            fun from(result: BillingResult): ConnectionResponse = if (
                result.responseCode == BillingClient.BillingResponseCode.OK
            ) {
                Connected
            } else {
                Failed(result)
            }
        }
    }

    private sealed interface ProductQueryResponse {
        data object Disconnected : ProductQueryResponse
        data class Result(
            val generation: Long,
            val billingResult: BillingResult,
            val products: List<GoogleProductDetails>,
        ) : ProductQueryResponse
    }

    private sealed interface OwnedQueryResponse {
        data object Disconnected : OwnedQueryResponse
        data class Result(
            val generation: Long,
            val billingResult: BillingResult,
            val purchases: List<Purchase>,
        ) : OwnedQueryResponse
    }

    private data class ConnectionBudget(var attemptsUsed: Int = 0)

    private sealed interface CallbackSignal<out T> {
        data class Value<T>(val value: T) : CallbackSignal<T>
        data object Closed : CallbackSignal<Nothing>
    }

    private interface ActiveWaiter {
        fun close()
        fun serviceDisconnected(): Boolean
    }

    private class CallbackWaiter<T>(
        private val disconnectedValue: () -> T,
    ) : ActiveWaiter {
        val result = CompletableDeferred<CallbackSignal<T>>()
        private val completed = AtomicBoolean(false)

        fun complete(value: T): Boolean {
            if (completed.compareAndSet(false, true)) {
                result.complete(CallbackSignal.Value(value))
                return true
            }
            return false
        }

        override fun close() {
            if (completed.compareAndSet(false, true)) result.complete(CallbackSignal.Closed)
        }

        override fun serviceDisconnected(): Boolean = complete(disconnectedValue())

        fun cancel() {
            if (completed.compareAndSet(false, true)) result.cancel()
        }
    }

    private class AndroidGoogleBillingClientAdapter(
        context: Context,
    ) : GoogleBillingClientAdapter {
        private var purchaseUpdateListener: ((BillingResult, List<Purchase>) -> Unit)? = null
        private val client = BillingClient.newBuilder(context)
            .setListener(PurchasesUpdatedListener { billingResult, purchases ->
                purchaseUpdateListener?.invoke(billingResult, purchases ?: emptyList())
            })
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build(),
            )
            .build()

        override val isReady: Boolean
            get() = client.isReady

        override fun setPurchaseUpdateListener(listener: (BillingResult, List<Purchase>) -> Unit) {
            purchaseUpdateListener = listener
        }

        override fun startConnection(
            onSetupFinished: (BillingResult) -> Unit,
            onServiceDisconnected: () -> Unit,
        ) {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    onSetupFinished(billingResult)
                }

                override fun onBillingServiceDisconnected() {
                    onServiceDisconnected()
                }
            })
        }

        override fun queryProductDetails(
            onResponse: (BillingResult, List<GoogleProductDetails>) -> Unit,
        ) {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRO_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            client.queryProductDetailsAsync(
                params,
                ProductDetailsResponseListener { billingResult, products ->
                    onResponse(billingResult, products)
                },
            )
        }

        override fun queryPurchases(onResponse: (BillingResult, List<Purchase>) -> Unit) {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            client.queryPurchasesAsync(
                params,
                PurchasesResponseListener { billingResult, purchases ->
                    onResponse(billingResult, purchases)
                },
            )
        }

        override fun launchBillingFlow(
            activity: Activity,
            productDetails: GoogleProductDetails,
        ): BillingResult {
            val flowProduct = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(flowProduct))
                .build()
            return client.launchBillingFlow(activity, flowParams)
        }

        override fun endConnection() = client.endConnection()
    }

    private companion object {
        const val TAG = "GoogleBillingGateway"
        const val MAX_CONNECTION_ATTEMPTS = 3
        val RETRY_DELAYS_MILLIS = longArrayOf(250L, 500L)
    }
}
