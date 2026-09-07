package com.example.convert2video.billing

import android.content.Context

internal fun createBillingGateway(context: Context): BillingGateway =
    BillingGatewayOwner.getOrCreate {
        GoogleBillingGateway(context.applicationContext)
    }
