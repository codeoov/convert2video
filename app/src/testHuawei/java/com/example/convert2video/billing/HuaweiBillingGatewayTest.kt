package com.example.convert2video.billing

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.huawei.hmf.tasks.Task
import com.huawei.hmf.tasks.Tasks
import com.huawei.hms.iap.IapClient
import com.huawei.hms.iap.entity.OwnedPurchasesReq
import com.huawei.hms.iap.entity.OwnedPurchasesResult
import com.huawei.hms.iap.entity.ProductInfo
import com.huawei.hms.iap.entity.ProductInfoReq
import com.huawei.hms.iap.entity.ProductInfoResult
import com.huawei.hms.iap.entity.PurchaseIntentReq
import com.huawei.hms.iap.entity.PurchaseIntentResult
import com.huawei.hms.iap.entity.PurchaseResultInfo
import com.huawei.hms.support.api.client.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuaweiBillingGatewayTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun success_prepareCacheHitThenLaunchReadsOnlyCachedResolution() = runBlocking {
        // Given
        val adapter = FakeHuaweiIapClientAdapter(context)
        val gateway = HuaweiBillingGateway(context, adapter)

        // When
        assertTrue(gateway.preparePurchaseIntent() is BillingGatewayResult.Success)
        assertTrue(gateway.preparePurchaseIntent() is BillingGatewayResult.Success)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val launch = gateway.launchPurchase(activity)
        val duplicateLaunch = gateway.launchPurchase(activity)

        // Then
        assertTrue(launch is BillingGatewayResult.NeedsResolution)
        assertEquals(BillingFailureKind.Unavailable, (duplicateLaunch as BillingGatewayResult.Failure).kind)
        assertEquals(1, adapter.productQueries)
        assertEquals(1, adapter.purchaseIntentQueries)
        gateway.close()
    }

    @Test
    fun success_ownedRestoreDeduplicatesSameProductTokenAndSkipsMismatch() = runBlocking {
        // Given
        val adapter = FakeHuaweiIapClientAdapter(context).apply {
            ownedResult = ownedResult(
                """{"productId":"$PRO_PRODUCT_ID","purchaseToken":"token","purchaseState":0}""",
                """{"productId":"$PRO_PRODUCT_ID","purchaseToken":"token","purchaseState":0}""",
                """{"productId":"other","purchaseToken":"other-token","purchaseState":0}""",
            )
        }
        val gateway = HuaweiBillingGateway(context, adapter)

        // When
        val result = gateway.queryOwnedPurchases()

        // Then
        val purchases = (result as BillingGatewayResult.Success).value
        assertEquals(1, purchases.size)
        assertEquals("token", purchases.single().purchaseToken)
        gateway.close()
    }

    @Test
    fun failure_prepareCancellationInvalidatesPreparedCache() = runBlocking {
        // Given
        val adapter = FakeHuaweiIapClientAdapter(context)
        val gateway = HuaweiBillingGateway(context, adapter)
        adapter.cancelNextPurchaseIntent = true

        // When
        var cancelled = false
        try {
            gateway.preparePurchaseIntent()
        } catch (_: CancellationException) {
            cancelled = true
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val launch = gateway.launchPurchase(activity)

        // Then
        assertTrue(cancelled)
        assertEquals(BillingFailureKind.InvalidProduct, (launch as BillingGatewayResult.Failure).kind)
        gateway.close()
    }

    private fun ownedResult(vararg values: String): OwnedPurchasesResult =
        OwnedPurchasesResult().apply {
            returnCode = 0
            inAppPurchaseDataList = values.toList()
        }

    private class FakeHuaweiIapClientAdapter(
        private val context: Context,
    ) : HuaweiIapClientAdapter {
        var productQueries = 0
        var purchaseIntentQueries = 0
        var cancelNextPurchaseIntent = false
        var ownedResult: OwnedPurchasesResult = OwnedPurchasesResult().apply {
            returnCode = 0
            inAppPurchaseDataList = emptyList()
        }
        private val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, Activity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        override fun obtainProductInfo(request: ProductInfoReq): Task<ProductInfoResult> {
            productQueries += 1
            return Tasks.fromResult(
                ProductInfoResult().apply {
                    returnCode = 0
                    productInfoList = listOf(
                        ProductInfo().apply {
                            productId = PRO_PRODUCT_ID
                            priceType = IapClient.PriceType.IN_APP_NONCONSUMABLE
                            productName = "Pro"
                            productDesc = "No watermark"
                            price = "$1"
                        },
                    )
                },
            )
        }

        override fun createPurchaseIntent(request: PurchaseIntentReq): Task<PurchaseIntentResult> {
            purchaseIntentQueries += 1
            if (cancelNextPurchaseIntent) {
                cancelNextPurchaseIntent = false
                throw CancellationException("cancelled")
            }
            return Tasks.fromResult(
                PurchaseIntentResult().apply {
                    returnCode = 0
                    status = Status(0, "ok", pendingIntent)
                },
            )
        }

        override fun obtainOwnedPurchaseRecord(request: OwnedPurchasesReq): Task<OwnedPurchasesResult> =
            Tasks.fromResult(ownedResult)

        override fun parsePurchaseResultInfo(intent: Intent): PurchaseResultInfo =
            error("Activity result parser is not used in this test")
    }
}
