package com.example.convert2video.store

import com.example.convert2video.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCapabilitiesTest {

    @Test
    fun success_googlePlay_exposesGoogleOutboundAndBillingCapabilities() {
        // Given
        val storeId = StoreCapabilities.GOOGLE_PLAY_STORE_ID

        // When
        val capabilities = StoreCapabilities.forStoreId(storeId)

        // Then
        assertEquals(storeId, capabilities.storeId)
        assertTrue(capabilities.isGoogleServicesAvailable)
        assertTrue(capabilities.supportsYouTube)
        assertTrue(capabilities.supportsDrive)
        assertTrue(capabilities.supportsBilling)
    }

    @Test
    fun success_huawei_allowsBillingAndBlocksGoogleOutboundCapabilities() {
        // Given
        val storeId = StoreCapabilities.HUAWEI_STORE_ID

        // When
        val capabilities = StoreCapabilities.forStoreId(storeId)

        // Then
        assertEquals(storeId, capabilities.storeId)
        assertFalse(capabilities.isGoogleServicesAvailable)
        assertFalse(capabilities.supportsYouTube)
        assertFalse(capabilities.supportsDrive)
        assertTrue(capabilities.supportsBilling)
    }

    @Test
    fun success_unknownStore_preservesStoreIdAndBlocksAllCapabilities() {
        // Given
        val storeId = "unknown_store"

        // When
        val capabilities = StoreCapabilities.forStoreId(storeId)

        // Then
        assertEquals(storeId, capabilities.storeId)
        assertFalse(capabilities.isGoogleServicesAvailable)
        assertFalse(capabilities.supportsYouTube)
        assertFalse(capabilities.supportsDrive)
        assertFalse(capabilities.supportsBilling)
    }

    @Test
    fun success_currentAndFromStoreId_preserveForStoreIdBehavior() {
        // Given
        val storeId = BuildConfig.STORE_ID

        // When
        val expectedCapabilities = StoreCapabilities.forStoreId(storeId)
        val currentCapabilities = StoreCapabilities.current
        val aliasedCapabilities = StoreCapabilities.fromStoreId(storeId)

        // Then
        assertEquals(expectedCapabilities, currentCapabilities)
        assertEquals(expectedCapabilities, aliasedCapabilities)
    }
}
