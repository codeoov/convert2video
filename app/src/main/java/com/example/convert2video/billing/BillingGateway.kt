package com.example.convert2video.billing

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.example.convert2video.store.StoreCapabilities
import android.util.Base64
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/** The single non-consumable Pro SKU shared by supported billing providers. */
internal const val PRO_PRODUCT_ID = "pro_lifetime_unlock"

/** Provider purchase states that can be represented by an owned purchase. */
internal enum class PurchaseState {
    Purchased,
    Pending,
}

/** Provider-neutral failure categories; provider SDK error codes must not cross this boundary. */
internal enum class BillingFailureKind {
    Unavailable,
    InvalidProduct,
    Network,
    LaunchFailed,
    InvalidResponse,
    ProviderError,
}

/** Provider-neutral product metadata for the single Pro purchase. */
internal data class ProductDetails(
    val productId: String,
    val title: String?,
    val description: String?,
    val formattedPrice: String?,
) {
    init {
        require(productId == PRO_PRODUCT_ID) { "Unsupported billing product" }
    }
}

internal fun isCanonicalBillingStore(store: String): Boolean =
    store == StoreCapabilities.GOOGLE_PLAY_STORE_ID ||
        store == StoreCapabilities.HUAWEI_STORE_ID

/** Provider-neutral owned purchase; only Purchased and Pending are valid states. */
internal data class OwnedPurchase(
    val store: String,
    val productId: String,
    val purchaseToken: String,
    val state: PurchaseState,
    val isAcknowledged: Boolean,
) {
    init {
        require(isCanonicalBillingStore(store)) { "Unsupported billing store" }
        require(productId == PRO_PRODUCT_ID) { "Unsupported billing product" }
        require(purchaseToken.isNotBlank()) { "Purchase token must not be blank" }
    }
}

/** A normalized purchase event from a provider callback or activity result. */
internal sealed interface PurchaseResult {
    data class Purchased(
        val purchase: OwnedPurchase,
    ) : PurchaseResult {
        init {
            require(purchase.state == PurchaseState.Purchased) {
                "Purchased result requires a Purchased purchase state"
            }
        }
    }

    data class Pending(
        val purchase: OwnedPurchase,
    ) : PurchaseResult {
        init {
            require(purchase.state == PurchaseState.Pending) {
                "Pending result requires a Pending purchase state"
            }
        }
    }

    data object UserCancelled : PurchaseResult

    data object AlreadyOwned : PurchaseResult

    /** Explicit provider result distinctions; none is an entitlement grant. */
    data object Cancelled : PurchaseResult

    data object Duplicate : PurchaseResult

    data object Malformed : PurchaseResult

    data object ProductMismatch : PurchaseResult

    data class Failed(
        val kind: BillingFailureKind,
    ) : PurchaseResult

    data class NeedsResolution(
        val pendingIntent: PendingIntent,
    ) : PurchaseResult
}

/** Type-safe result for gateway operations, including provider resolution hand-off. */
internal sealed interface BillingGatewayResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : BillingGatewayResult<T>

    data class NeedsResolution(
        val pendingIntent: PendingIntent,
    ) : BillingGatewayResult<Nothing>

    data class Failure(
        val kind: BillingFailureKind,
    ) : BillingGatewayResult<Nothing>
}

/** Store-neutral billing boundary; provider implementations live in flavor source sets later. */
internal interface BillingGateway {
    val store: String

    /**
     * Hot provider callback stream. It is not a direct UI or entitlement authority; callers must
     * reconcile it with owned purchases, backend verification, and EntitlementStore. If there is
     * no collector, a slow collector, or an update is dropped, P5 must recover through
     * [queryOwnedPurchases] at startup/onResume and after observer lifecycle changes. This is an
     * advisory notification stream only; entitlement decisions never rely on one emission.
     */
    val purchaseUpdates: Flow<PurchaseResult>

    suspend fun queryProductDetails(): BillingGatewayResult<ProductDetails>

    /**
     * Suspends while any provider query/preparation I/O completes without launching UI. The
     * synchronous [launchPurchase] boundary must never await a provider Task or start a detached
     * coroutine; it may only consume the cache produced here.
     */
    suspend fun preparePurchaseIntent(): BillingGatewayResult<Unit> =
        when (val result = queryProductDetails()) {
            is BillingGatewayResult.Success -> BillingGatewayResult.Success(Unit)
            is BillingGatewayResult.NeedsResolution -> result
            is BillingGatewayResult.Failure -> result
        }

    /** Returns only launch acceptance; purchase state arrives asynchronously on [purchaseUpdates]. */
    fun launchPurchase(activity: Activity): BillingGatewayResult<Unit>

    suspend fun queryOwnedPurchases(): BillingGatewayResult<List<OwnedPurchase>>

    fun resultFromActivityResult(resultCode: Int, intent: Intent?): PurchaseResult

    /** Implementations must be process-wide owners, suppress post-close emissions, and be idempotent. */
    fun close()
}

/** Process-wide owner used by flavor factories; callers must not construct gateways directly. */
internal object BillingGatewayOwner {
    private val current = java.util.concurrent.atomic.AtomicReference<BillingGateway?>(null)

    fun getOrCreate(factory: () -> BillingGateway): BillingGateway {
        current.get()?.let { return it }
        val candidate = factory()
        current.get()?.let {
            candidate.close()
            return it
        }
        return if (current.compareAndSet(null, candidate)) {
            candidate
        } else {
            candidate.close()
            current.get() ?: error("Billing gateway ownership was lost")
        }
    }

    internal fun release(gateway: BillingGateway) {
        current.compareAndSet(gateway, null)
    }
}

/** Callback adapter support for flavor implementations. */
internal abstract class CallbackBillingGateway(
    final override val store: String,
) : BillingGateway {
    private val updateLock = Any()
    private val closed = AtomicBoolean(false)
    private val emittedUpdateKeys = LinkedHashMap<String, Long>()
    private val updates = MutableSharedFlow<PurchaseResult>(
        replay = 0,
        extraBufferCapacity = MAX_UPDATE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        require(isCanonicalBillingStore(store)) { "Unsupported billing store" }
    }

    final override val purchaseUpdates: Flow<PurchaseResult> = updates.asSharedFlow()

    /**
     * Provider callbacks must call this exactly once after normalization. DROP_OLDEST is bounded
     * by design; P5 always reconciles owned purchases so a dropped terminal event cannot grant or
     * remove entitlement by itself.
     */
    protected fun emitPurchaseUpdate(result: PurchaseResult) {
        synchronized(updateLock) {
            if (closed.get()) return
            val now = System.currentTimeMillis()
            evictExpiredUpdateKeys(now)
            val dedupKey = result.dedupKeyOrNull()
            if (dedupKey != null && emittedUpdateKeys.put(dedupKey, now) != null) return
            updates.tryEmit(result)
        }
    }

    final override fun close() {
        synchronized(updateLock) {
            if (!closed.compareAndSet(false, true)) return
            emittedUpdateKeys.clear()
            onClosed()
            BillingGatewayOwner.release(this)
        }
    }

    protected open fun onClosed() = Unit

    private fun evictExpiredUpdateKeys(now: Long) {
        val expired = emittedUpdateKeys.filterValues { now - it >= UPDATE_DEDUP_WINDOW_MILLIS }.keys
        expired.forEach(emittedUpdateKeys::remove)
        while (emittedUpdateKeys.size > MAX_UPDATE_KEYS) {
            emittedUpdateKeys.remove(emittedUpdateKeys.entries.first().key)
        }
    }

    private fun PurchaseResult.dedupKeyOrNull(): String? {
        val purchase = when (this) {
            is PurchaseResult.Purchased -> purchase
            is PurchaseResult.Pending -> purchase
            else -> return null
        }
        val identity = "${purchase.store}\u0000${purchase.purchaseToken}\u0000${purchase.state.name}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_UPDATE_BUFFER = 64
        const val MAX_UPDATE_KEYS = 128
        const val UPDATE_DEDUP_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
