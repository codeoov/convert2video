package com.example.convert2video.store

import com.example.convert2video.BuildConfig

/** Store capability SSOT for Google outbound boundaries and store-specific billing gates. */
data class StoreCapabilities(
    val storeId: String,
    val isGoogleServicesAvailable: Boolean,
    val supportsYouTube: Boolean,
    val supportsDrive: Boolean,
    val supportsBilling: Boolean,
) {
    companion object {
        const val GOOGLE_PLAY_STORE_ID = "google_play"
        const val HUAWEI_STORE_ID = "huawei"

        /** Maps the flavor's STORE_ID without allowing unknown stores to fail open. */
        fun forStoreId(storeId: String): StoreCapabilities = when (storeId) {
            GOOGLE_PLAY_STORE_ID -> StoreCapabilities(
                storeId = GOOGLE_PLAY_STORE_ID,
                isGoogleServicesAvailable = true,
                supportsYouTube = true,
                supportsDrive = true,
                supportsBilling = true,
            )
            HUAWEI_STORE_ID -> StoreCapabilities(
                storeId = HUAWEI_STORE_ID,
                isGoogleServicesAvailable = false,
                supportsYouTube = false,
                supportsDrive = false,
                supportsBilling = true,
            )
            else -> StoreCapabilities(
                storeId = storeId,
                isGoogleServicesAvailable = false,
                supportsYouTube = false,
                supportsDrive = false,
                supportsBilling = false,
            )
        }

        fun fromStoreId(storeId: String): StoreCapabilities = forStoreId(storeId)

        val current: StoreCapabilities by lazy { forStoreId(BuildConfig.STORE_ID) }
    }
}
