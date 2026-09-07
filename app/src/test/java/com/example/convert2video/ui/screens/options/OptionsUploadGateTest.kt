package com.example.convert2video.ui.screens.options

import com.example.convert2video.store.StoreCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsUploadGateTest {

    @Test
    fun success_huawei_hidesYouTubeAndDriveOptions() {
        val capabilities = StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID)

        assertFalse(shouldShowYouTubeOptions(capabilities))
        assertFalse(shouldShowDriveOptions(capabilities))
    }

    @Test
    fun success_google_keepsYouTubeAndDriveOptions() {
        val capabilities = StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID)

        assertTrue(shouldShowYouTubeOptions(capabilities))
        assertTrue(shouldShowDriveOptions(capabilities))
    }
}
