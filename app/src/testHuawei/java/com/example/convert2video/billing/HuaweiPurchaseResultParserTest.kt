package com.example.convert2video.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiPurchaseResultParserTest {

    @Test
    fun success_purchasedAndPendingResultsPreserveProviderState() {
        // Given
        val purchased = """{"productId":"$PRO_PRODUCT_ID","purchaseToken":"token-1","purchaseState":0}"""
        val pending = """{"productId":"$PRO_PRODUCT_ID","purchaseToken":"token-2","purchaseState":3}"""

        // When
        val purchasedResult = parseHuaweiPurchaseResult(-1, 0, purchased)
        val pendingResult = parseHuaweiPurchaseResult(-1, 0, pending)

        // Then
        assertTrue(purchasedResult is PurchaseResult.Purchased)
        assertTrue(pendingResult is PurchaseResult.Pending)
    }

    @Test
    fun success_cancelledAndDuplicateResultsRemainDistinct() {
        // Given / When
        val cancelled = parseHuaweiPurchaseResult(0, 0, null)
        val duplicate = parseHuaweiPurchaseResult(-1, 60051, null)

        // Then
        assertEquals(PurchaseResult.Cancelled, cancelled)
        assertEquals(PurchaseResult.Duplicate, duplicate)
    }

    @Test
    fun failure_malformedBlankTokenAndProductMismatchNeverBecomeOwned() {
        // Given
        val malformed = parseHuaweiPurchaseData("not-json")
        val blankToken = parseHuaweiPurchaseData(
            """{"productId":"$PRO_PRODUCT_ID","purchaseToken":"","purchaseState":0}""",
        )
        val mismatch = parseHuaweiPurchaseData(
            """{"productId":"other","purchaseToken":"token","purchaseState":0}""",
        )

        // Then
        assertEquals(HuaweiPurchaseParseResult.Malformed, malformed)
        assertEquals(HuaweiPurchaseParseResult.Malformed, blankToken)
        assertEquals(HuaweiPurchaseParseResult.ProductMismatch, mismatch)
    }

    @Test
    fun success_duplicateInputUsesSeenTokenSemantics() {
        // Given
        val purchase = """{"productId":"$PRO_PRODUCT_ID","purchaseToken":"same-token","purchaseState":0}"""
        val first = parseHuaweiPurchaseData(purchase)

        // When
        val duplicate = parseHuaweiPurchaseData(
            purchaseData = purchase,
            seenPurchaseTokens = setOf("same-token"),
        )

        // Then
        assertTrue(first is HuaweiPurchaseParseResult.Owned)
        assertEquals(HuaweiPurchaseParseResult.Duplicate, duplicate)
    }
}
