package com.example.convert2video.billing

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.huawei.hmf.tasks.Task
import com.huawei.hms.iap.Iap
import com.huawei.hms.iap.IapClient
import com.huawei.hms.iap.entity.OrderStatusCode
import com.huawei.hms.iap.entity.OwnedPurchasesReq
import com.huawei.hms.iap.entity.OwnedPurchasesResult
import com.huawei.hms.iap.entity.ProductInfo
import com.huawei.hms.iap.entity.ProductInfoReq
import com.huawei.hms.iap.entity.ProductInfoResult
import com.huawei.hms.iap.entity.PurchaseIntentReq
import com.huawei.hms.iap.entity.PurchaseIntentResult
import com.huawei.hms.iap.entity.PurchaseResultInfo
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.utils.AppLogger
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface HuaweiIapClientAdapter {
    fun obtainProductInfo(request: ProductInfoReq): Task<ProductInfoResult>
    fun createPurchaseIntent(request: PurchaseIntentReq): Task<PurchaseIntentResult>
    fun obtainOwnedPurchaseRecord(request: OwnedPurchasesReq): Task<OwnedPurchasesResult>
    fun parsePurchaseResultInfo(intent: Intent): PurchaseResultInfo
}

internal class HuaweiBillingGateway(
    context: Context,
    private val adapter: HuaweiIapClientAdapter =
        AndroidHuaweiIapClientAdapter(Iap.getIapClient(context.applicationContext)),
) : CallbackBillingGateway(StoreCapabilities.HUAWEI_STORE_ID) {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val queryMutex = Mutex()
    private val cacheLock = Any()
    private val launchInFlight = AtomicBoolean(false)
    private var cachedProduct: CachedProduct? = null
    private var cachedPurchaseIntent: CachedPurchaseIntent? = null

    override suspend fun queryProductDetails(): BillingGatewayResult<ProductDetails> =
        queryMutex.withLock {
            val currentGeneration = generation.get()
            synchronized(cacheLock) {
                cachedProduct?.takeIf { it.generation == currentGeneration }?.let {
                    return@withLock BillingGatewayResult.Success(it.value)
                }
            }
            val request = ProductInfoReq().apply {
                productIds = listOf(PRO_PRODUCT_ID)
                priceType = IapClient.PriceType.IN_APP_NONCONSUMABLE
            }
            val response = try {
                awaitTask(adapter.obtainProductInfo(request))
            } catch (error: CancellationException) {
                invalidateGeneration(currentGeneration)
                throw error
            } catch (error: TimeoutException) {
                invalidateGeneration(currentGeneration)
                return@withLock BillingGatewayResult.Failure(BillingFailureKind.Network)
            } catch (error: Exception) {
                invalidateGeneration(currentGeneration)
                AppLogger.e(TAG, "Huawei product query failed: ${error.javaClass.simpleName}")
                return@withLock BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
            }
            if (!isGenerationActive(currentGeneration)) {
                return@withLock BillingGatewayResult.Failure(BillingFailureKind.Network)
            }
            if (response.returnCode != OrderStatusCode.ORDER_STATE_SUCCESS) {
                invalidateGeneration(currentGeneration)
                return@withLock BillingGatewayResult.Failure(
                    failureKindForHuaweiCode(response.returnCode),
                )
            }
            val product = response.productInfoList.orEmpty()
                .singleOrNull { it.productId == PRO_PRODUCT_ID }
                ?.takeIf { it.priceType == IapClient.PriceType.IN_APP_NONCONSUMABLE }
                ?: run {
                    invalidateGeneration(currentGeneration)
                    return@withLock BillingGatewayResult.Failure(BillingFailureKind.InvalidProduct)
                }
            val details = product.toProductDetails()
            synchronized(cacheLock) {
                if (!isGenerationActive(currentGeneration)) {
                    return@withLock BillingGatewayResult.Failure(BillingFailureKind.Network)
                }
                cachedProduct = CachedProduct(currentGeneration, details)
            }
            BillingGatewayResult.Success(details)
        }

    override suspend fun preparePurchaseIntent(): BillingGatewayResult<Unit> {
        val currentGeneration = generation.get()
        synchronized(cacheLock) {
            if (!isGenerationActive(currentGeneration)) {
                return BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            }
            if (cachedPurchaseIntent?.generation == currentGeneration) {
                return BillingGatewayResult.Success(Unit)
            }
        }
        when (val productResult = queryProductDetails()) {
            is BillingGatewayResult.Failure -> return productResult
            is BillingGatewayResult.NeedsResolution -> return productResult
            is BillingGatewayResult.Success -> Unit
        }
        val request = PurchaseIntentReq().apply {
            productId = PRO_PRODUCT_ID
            priceType = IapClient.PriceType.IN_APP_NONCONSUMABLE
        }
        val response = try {
            // Preparation is a suspend boundary: the provider Task is bridged here and its
            // result is cached. launchPurchase is synchronous and never awaits a Task.
            awaitTask(adapter.createPurchaseIntent(request))
        } catch (error: CancellationException) {
            invalidateGeneration(currentGeneration)
            throw error
        } catch (error: TimeoutException) {
            invalidateGeneration(currentGeneration)
            return BillingGatewayResult.Failure(BillingFailureKind.Network)
        } catch (error: Exception) {
            invalidateGeneration(currentGeneration)
            AppLogger.e(TAG, "Huawei purchase preparation failed: ${error.javaClass.simpleName}")
            return BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
        }
        if (!isGenerationActive(currentGeneration)) {
            return BillingGatewayResult.Failure(BillingFailureKind.Network)
        }
        val status = response.status
        val pendingIntent = status?.resolution
        if (response.returnCode != OrderStatusCode.ORDER_STATE_SUCCESS ||
            status == null ||
            pendingIntent == null
        ) {
            invalidateGeneration(currentGeneration)
            return BillingGatewayResult.Failure(
                failureKindForHuaweiCode(response.returnCode),
            )
        }
        synchronized(cacheLock) {
            if (!isGenerationActive(currentGeneration)) {
                return BillingGatewayResult.Failure(BillingFailureKind.Network)
            }
            cachedPurchaseIntent = CachedPurchaseIntent(currentGeneration, pendingIntent)
        }
        return BillingGatewayResult.Success(Unit)
    }

    override suspend fun queryOwnedPurchases(): BillingGatewayResult<List<OwnedPurchase>> {
        val currentGeneration = generation.get()
        val request = OwnedPurchasesReq().apply {
            priceType = IapClient.PriceType.IN_APP_NONCONSUMABLE
        }
        val response = try {
            awaitTask(adapter.obtainOwnedPurchaseRecord(request))
        } catch (error: CancellationException) {
            invalidateGeneration(currentGeneration)
            throw error
        } catch (error: TimeoutException) {
            invalidateGeneration(currentGeneration)
            return BillingGatewayResult.Failure(BillingFailureKind.Network)
        } catch (error: Exception) {
            invalidateGeneration(currentGeneration)
            AppLogger.e(TAG, "Huawei owned purchase query failed: ${error.javaClass.simpleName}")
            return BillingGatewayResult.Failure(BillingFailureKind.ProviderError)
        }
        if (!isGenerationActive(currentGeneration)) {
            return BillingGatewayResult.Failure(BillingFailureKind.Network)
        }
        if (response.returnCode != OrderStatusCode.ORDER_STATE_SUCCESS) {
            return BillingGatewayResult.Failure(failureKindForHuaweiCode(response.returnCode))
        }
        val purchases = ArrayList<OwnedPurchase>()
        val seenPurchaseTokens = LinkedHashSet<String>()
        response.inAppPurchaseDataList.orEmpty().forEach { purchaseData ->
            when (val parsed = parseHuaweiPurchaseData(
                purchaseData = purchaseData,
                seenPurchaseTokens = seenPurchaseTokens,
            )) {
                is HuaweiPurchaseParseResult.Owned -> {
                    purchases += parsed.purchase
                    seenPurchaseTokens += parsed.purchase.purchaseToken
                }
                HuaweiPurchaseParseResult.ProductMismatch ->
                    emitPurchaseUpdate(PurchaseResult.ProductMismatch)
                HuaweiPurchaseParseResult.Cancelled ->
                    emitPurchaseUpdate(PurchaseResult.Cancelled)
                HuaweiPurchaseParseResult.Duplicate ->
                    emitPurchaseUpdate(PurchaseResult.Duplicate)
                HuaweiPurchaseParseResult.Malformed -> {
                    emitPurchaseUpdate(PurchaseResult.Malformed)
                    return BillingGatewayResult.Failure(BillingFailureKind.InvalidResponse)
                }
            }
        }
        return BillingGatewayResult.Success(purchases)
    }

    override fun launchPurchase(activity: Activity): BillingGatewayResult<Unit> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return BillingGatewayResult.Failure(BillingFailureKind.LaunchFailed)
        }
        if (closed.get() || launchInFlight.get()) {
            return BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
        }
        val currentGeneration = generation.get()
        synchronized(cacheLock) {
            val pendingIntent = cachedPurchaseIntent
                ?.takeIf { it.generation == currentGeneration }
                ?.pendingIntent
                ?: return BillingGatewayResult.Failure(BillingFailureKind.InvalidProduct)
            if (!launchInFlight.compareAndSet(false, true)) {
                return BillingGatewayResult.Failure(BillingFailureKind.Unavailable)
            }
            // ActivityResultLauncher owns the actual IntentSender launch; no provider Task runs here.
            return BillingGatewayResult.NeedsResolution(pendingIntent)
        }
    }

    override fun resultFromActivityResult(resultCode: Int, intent: Intent?): PurchaseResult {
        launchInFlight.set(false)
        synchronized(cacheLock) { cachedPurchaseIntent = null }
        if (closed.get()) return PurchaseResult.Failed(BillingFailureKind.Unavailable)
        if (intent == null) {
            return if (resultCode == Activity.RESULT_CANCELED) {
                PurchaseResult.Cancelled
            } else {
                PurchaseResult.Failed(BillingFailureKind.InvalidResponse)
            }
        }
        return try {
            val info = adapter.parsePurchaseResultInfo(intent)
            parseHuaweiPurchaseResult(resultCode, info.returnCode, info.inAppPurchaseData)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Huawei purchase result parsing failed: ${error.javaClass.simpleName}")
            PurchaseResult.Failed(BillingFailureKind.InvalidResponse)
        }
    }

    override fun onClosed() {
        closed.set(true)
        generation.incrementAndGet()
        launchInFlight.set(false)
        synchronized(cacheLock) {
            cachedProduct = null
            cachedPurchaseIntent = null
        }
    }

    /**
     * Non-blocking Huawei Task bridge for suspend-only query/prepare APIs. Cancellation makes the
     * continuation inactive; a provider callback that arrives afterward is ignored. The caller
     * separately invalidates its generation on cancellation/timeout, so a late callback cannot
     * repopulate cache. launchPurchase never calls this bridge.
     */
    private suspend fun <T> awaitTask(task: Task<T>): T = withTimeout(TASK_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            // Use a direct executor: the bridge must not enqueue onto Android main, where a
            // caller may be suspending from a main-owned coroutine. This remains non-blocking;
            // the provider invokes the listener when the Task completes.
            task.addOnCompleteListener(DIRECT_EXECUTOR) { completed ->
                if (!continuation.isActive) return@addOnCompleteListener
                when {
                    completed.isCanceled -> continuation.cancel(
                        CancellationException("Huawei Task was cancelled"),
                    )
                    completed.isSuccessful -> continuation.resume(completed.result)
                    else -> continuation.resumeWithException(
                        completed.exception ?: IllegalStateException("Huawei Task failed"),
                    )
                }
            }
        }
    }

    private fun invalidateGeneration(expectedGeneration: Long) {
        if (generation.compareAndSet(expectedGeneration, expectedGeneration + 1L)) {
            synchronized(cacheLock) {
                cachedProduct = null
                cachedPurchaseIntent = null
            }
            launchInFlight.set(false)
        }
    }

    private fun isGenerationActive(expectedGeneration: Long): Boolean =
        !closed.get() && generation.get() == expectedGeneration

    private fun ProductInfo.toProductDetails(): ProductDetails = ProductDetails(
        productId = productId,
        title = productName,
        description = productDesc,
        formattedPrice = price,
    )

    private data class CachedProduct(
        val generation: Long,
        val value: ProductDetails,
    )

    private data class CachedPurchaseIntent(
        val generation: Long,
        val pendingIntent: PendingIntent,
    )

    private class AndroidHuaweiIapClientAdapter(
        private val client: IapClient,
    ) : HuaweiIapClientAdapter {
        override fun obtainProductInfo(request: ProductInfoReq): Task<ProductInfoResult> =
            client.obtainProductInfo(request)

        override fun createPurchaseIntent(request: PurchaseIntentReq): Task<PurchaseIntentResult> =
            client.createPurchaseIntent(request)

        override fun obtainOwnedPurchaseRecord(request: OwnedPurchasesReq): Task<OwnedPurchasesResult> =
            client.obtainOwnedPurchaseRecord(request)

        override fun parsePurchaseResultInfo(intent: Intent): PurchaseResultInfo =
            client.parsePurchaseResultInfoFromIntent(intent)
    }

    private companion object {
        const val TAG = "HuaweiBillingGateway"
        const val TASK_TIMEOUT_MILLIS = 15_000L
        val DIRECT_EXECUTOR = java.util.concurrent.Executor { command -> command.run() }

        fun failureKindForHuaweiCode(code: Int): BillingFailureKind = when (code) {
            OrderStatusCode.ORDER_STATE_PRODUCT_INVALID -> BillingFailureKind.InvalidProduct
            OrderStatusCode.ORDER_STATE_NET_ERROR -> BillingFailureKind.Network
            OrderStatusCode.ORDER_STATE_IAP_NOT_ACTIVATED,
            OrderStatusCode.NOT_SUPPORT,
            -> BillingFailureKind.Unavailable
            else -> BillingFailureKind.ProviderError
        }
    }
}
