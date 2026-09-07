package com.example.convert2video.billing

import android.content.Context
import com.example.convert2video.store.StoreCapabilities

internal fun createBillingGateway(context: Context): BillingGateway =
    BillingGatewayOwner.getOrCreate {
        HuaweiBillingGateway(context.applicationContext)
    }
