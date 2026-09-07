package com.example.convert2video.data

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashPurgeWorkerTest {

    @Test
    fun success_trashPurgeUniqueWorkName_isLiteralSsot() {
        // Given / When / Then
        assertEquals("trash_purge", TrashPurgeWorker.TRASH_PURGE_UNIQUE_WORK_NAME)
    }

    @Test
    fun success_schedulePeriodicWork_enqueuesDailyKeepUniqueWork() {
        // Given
        val fake = FakePeriodicWorkEnqueuer()

        // When
        TrashPurgeWorker.schedulePeriodicWork(
            enqueueUniquePeriodicWork = fake::enqueueUniquePeriodicWork,
        )

        // Then
        assertTrue(fake.wasCalled)
        assertEquals(TrashPurgeWorker.TRASH_PURGE_UNIQUE_WORK_NAME, fake.lastUniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, fake.lastPolicy)
        assertTrue(fake.lastRequest is PeriodicWorkRequest)
    }

    private class FakePeriodicWorkEnqueuer {
        var wasCalled = false
        var lastUniqueWorkName: String? = null
        var lastPolicy: ExistingPeriodicWorkPolicy? = null
        var lastRequest: PeriodicWorkRequest? = null

        fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            wasCalled = true
            lastUniqueWorkName = uniqueWorkName
            lastPolicy = existingPeriodicWorkPolicy
            lastRequest = request
        }
    }
}
