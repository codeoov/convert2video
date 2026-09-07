package com.example.convert2video.record

import com.example.convert2video.store.StoreCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingServiceCapabilityTest {

    @Test
    fun success_huawei_skipsDriveButRunsLocalConversionBranch() = runTest {
        var driveCalls = 0
        var conversionCalls = 0

        enqueueRecordingPostSaveBranches(
            capabilities = StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID),
            enqueueDriveAutoUpload = { driveCalls += 1 },
            enqueueAutoConvert = { conversionCalls += 1 },
        )

        assertFalse(
            shouldEnqueueDriveAutoUpload(
                StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID),
            ),
        )
        assertEquals(0, driveCalls)
        assertEquals(1, conversionCalls)
    }

    @Test
    fun success_google_runsDriveAndLocalConversionBranches() = runTest {
        var driveCalls = 0
        var conversionCalls = 0

        enqueueRecordingPostSaveBranches(
            capabilities = StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID),
            enqueueDriveAutoUpload = { driveCalls += 1 },
            enqueueAutoConvert = { conversionCalls += 1 },
        )

        assertTrue(
            shouldEnqueueDriveAutoUpload(
                StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID),
            ),
        )
        assertEquals(1, driveCalls)
        assertEquals(1, conversionCalls)
    }
}
