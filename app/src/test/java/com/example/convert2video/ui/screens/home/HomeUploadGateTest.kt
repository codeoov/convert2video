package com.example.convert2video.ui.screens.home

import com.example.convert2video.store.StoreCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUploadGateTest {

    @Test
    fun success_huawei_hidesConvertedVideosBatchUpload() {
        assertFalse(
            shouldShowConvertedVideosBatchUpload(
                StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID),
            ),
        )
    }

    @Test
    fun success_google_keepsConvertedVideosBatchUpload() {
        assertTrue(
            shouldShowConvertedVideosBatchUpload(
                StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID),
            ),
        )
    }
}
