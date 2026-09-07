package com.example.convert2video.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCapabilitiesBillingGateTest {

    @Test
    fun success_googlePlay_billingGateAllowsAccess() {
        // Given
        val capabilities = StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID)

        // When
        val supportsBilling = capabilities.supportsBilling

        // Then
        assertTrue(supportsBilling)
    }

    @Test
    fun success_huawei_billingGateAllowsAccess() {
        // Given
        val capabilities = StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID)

        // When
        val supportsBilling = capabilities.supportsBilling

        // Then
        assertTrue(supportsBilling)
    }

    @Test
    fun success_unknownStore_billingGateBlocksAccess() {
        // Given
        val capabilities = StoreCapabilities.forStoreId("unknown_store")

        // When
        val supportsBilling = capabilities.supportsBilling

        // Then
        assertFalse(supportsBilling)
    }
}
