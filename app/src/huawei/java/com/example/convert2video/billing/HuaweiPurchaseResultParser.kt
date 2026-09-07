package com.example.convert2video.billing

import org.json.JSONObject

/** Pure, SDK-free normalization result used by the Huawei gateway and its unit tests. */
internal sealed interface HuaweiPurchaseParseResult {
    data class Owned(val purchase: OwnedPurchase) : HuaweiPurchaseParseResult
    data object Cancelled : HuaweiPurchaseParseResult
    data object Duplicate : HuaweiPurchaseParseResult
    data object Malformed : HuaweiPurchaseParseResult
    data object ProductMismatch : HuaweiPurchaseParseResult
}

internal fun parseHuaweiPurchaseData(
    purchaseData: String?,
    expectedProductId: String = PRO_PRODUCT_ID,
    seenPurchaseTokens: Set<String> = emptySet(),
): HuaweiPurchaseParseResult {
    if (purchaseData.isNullOrBlank()) return HuaweiPurchaseParseResult.Malformed
    return try {
        val json = JSONObject(purchaseData)
        if (!json.has("productId") || !json.has("purchaseToken") || !json.has("purchaseState")) {
            return HuaweiPurchaseParseResult.Malformed
        }
        val productId = json.get("productId") as? String
        val token = json.get("purchaseToken") as? String
        val state = (json.get("purchaseState") as? Number)?.toInt()
        if (productId == null || token == null || state == null) {
            return HuaweiPurchaseParseResult.Malformed
        }
        if (productId != expectedProductId) return HuaweiPurchaseParseResult.ProductMismatch
        if (token.isBlank()) return HuaweiPurchaseParseResult.Malformed
        if (token in seenPurchaseTokens) return HuaweiPurchaseParseResult.Duplicate
        val purchaseState = when (state) {
            HUAWEI_PURCHASED -> PurchaseState.Purchased
            HUAWEI_PENDING -> PurchaseState.Pending
            HUAWEI_CANCELLED,
            HUAWEI_REFUNDED,
            -> return HuaweiPurchaseParseResult.Cancelled
            else -> return HuaweiPurchaseParseResult.Malformed
        }
        HuaweiPurchaseParseResult.Owned(
            OwnedPurchase(
                store = com.example.convert2video.store.StoreCapabilities.HUAWEI_STORE_ID,
                productId = expectedProductId,
                purchaseToken = token,
                state = purchaseState,
                isAcknowledged = true,
            ),
        )
    } catch (_: Exception) {
        HuaweiPurchaseParseResult.Malformed
    }
}

internal fun parseHuaweiPurchaseResult(
    activityResultCode: Int,
    returnCode: Int,
    purchaseData: String?,
    expectedProductId: String = PRO_PRODUCT_ID,
): PurchaseResult {
    if (activityResultCode == ACTIVITY_RESULT_CANCELED ||
        returnCode == HUAWEI_ORDER_CANCELLED
    ) {
        return PurchaseResult.Cancelled
    }
    if (returnCode == HUAWEI_ORDER_ALREADY_OWNED) return PurchaseResult.Duplicate
    if (returnCode != HUAWEI_ORDER_SUCCESS && purchaseData.isNullOrBlank()) {
        return PurchaseResult.Failed(failureKindForHuaweiCode(returnCode))
    }
    return when (val parsed = parseHuaweiPurchaseData(purchaseData, expectedProductId)) {
        is HuaweiPurchaseParseResult.Owned -> when (parsed.purchase.state) {
            PurchaseState.Purchased -> PurchaseResult.Purchased(parsed.purchase)
            PurchaseState.Pending -> PurchaseResult.Pending(parsed.purchase)
        }
        HuaweiPurchaseParseResult.Cancelled -> PurchaseResult.Cancelled
        HuaweiPurchaseParseResult.Duplicate -> PurchaseResult.Duplicate
        HuaweiPurchaseParseResult.Malformed,
        -> PurchaseResult.Malformed
        HuaweiPurchaseParseResult.ProductMismatch -> PurchaseResult.ProductMismatch
    }
}

private fun failureKindForHuaweiCode(returnCode: Int): BillingFailureKind = when (returnCode) {
    HUAWEI_ORDER_PRODUCT_INVALID -> BillingFailureKind.InvalidProduct
    HUAWEI_ORDER_NETWORK_ERROR -> BillingFailureKind.Network
    HUAWEI_ORDER_IAP_UNAVAILABLE,
    HUAWEI_ORDER_NOT_SUPPORTED,
    -> BillingFailureKind.Unavailable
    else -> BillingFailureKind.ProviderError
}

private const val ACTIVITY_RESULT_CANCELED = 0
private const val HUAWEI_ORDER_SUCCESS = 0
private const val HUAWEI_ORDER_CANCELLED = 60000
private const val HUAWEI_ORDER_PRODUCT_INVALID = 60003
private const val HUAWEI_ORDER_NETWORK_ERROR = 60005
private const val HUAWEI_ORDER_IAP_UNAVAILABLE = 60002
private const val HUAWEI_ORDER_ALREADY_OWNED = 60051
private const val HUAWEI_ORDER_NOT_SUPPORTED = 70000
private const val HUAWEI_PURCHASED = 0
private const val HUAWEI_CANCELLED = 1
private const val HUAWEI_REFUNDED = 2
private const val HUAWEI_PENDING = 3
