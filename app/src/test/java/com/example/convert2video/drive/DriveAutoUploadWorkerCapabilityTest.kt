package com.example.convert2video.drive

import androidx.work.Data
import androidx.work.ListenableWorker
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.workerContextForTest
import com.example.convert2video.workerParametersForTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAutoUploadWorkerCapabilityTest {

    @Test
    fun success_huawei_doWorkReturnsEmptySuccessBeforeGoogleSideEffects() = runTest {
        var authApiForegroundNotificationCalls = 0
        val worker = DriveAutoUploadWorker(
            workerContextForTest(),
            workerParametersForTest(Data.Builder().putString("file_uri", "content://blocked").build()),
            StoreCapabilities.forStoreId(StoreCapabilities.HUAWEI_STORE_ID),
            googleUpload = {
                authApiForegroundNotificationCalls += 1
                ListenableWorker.Result.failure()
            },
        )
        val result = worker.doWork()

        assertEquals(0, authApiForegroundNotificationCalls)
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue((result as ListenableWorker.Result.Success).outputData.keyValueMap.isEmpty())
    }

    @Test
    fun success_google_keepsUploadPathOpen() = runTest {
        var googlePathCalls = 0
        val worker = DriveAutoUploadWorker(
            workerContextForTest(),
            workerParametersForTest(Data.Builder().build()),
            StoreCapabilities.forStoreId(StoreCapabilities.GOOGLE_PLAY_STORE_ID),
            googleUpload = {
                googlePathCalls += 1
                ListenableWorker.Result.success()
            },
        )

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, googlePathCalls)
    }
}
