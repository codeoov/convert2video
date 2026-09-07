package com.example.convert2video.ui.screens.youtube_upload

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeUploadNetworkConstraintTest {

    @Test
    fun success_wifiOnlyFalse_usesConnected() {
        // Given
        val wifiOnly = false

        // When
        val networkType = networkTypeForWifiOnly(wifiOnly)

        // Then
        assertEquals(NetworkType.CONNECTED, networkType)
    }

    @Test
    fun success_wifiOnlyTrue_usesUnmetered() {
        // Given
        val wifiOnly = true

        // When
        val networkType = networkTypeForWifiOnly(wifiOnly)

        // Then
        assertEquals(NetworkType.UNMETERED, networkType)
    }
}
