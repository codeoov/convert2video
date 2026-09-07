package com.example.convert2video.ui.screens.convert

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.data.BackgroundRepository
import com.example.convert2video.video.CONVERSION_UNIQUE_WORK_NAME
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ConvertLongAudioEnqueueAndroidTest {

    @Test
    fun success_exactTenMinutesAndOverLimitAudio_enqueueWithoutPaywall() = runBlocking(Dispatchers.Main) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val workManager = WorkManager.getInstance(app)
        val existing = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(CONVERSION_UNIQUE_WORK_NAME).get()
        }
        assumeTrue("Do not cancel an unrelated unfinished conversion", existing.all { it.state.isFinished })

        val database = AppDatabase.getInstance(app)
        val backgroundDao = database.backgroundDao()
        val previousBackground = withContext(Dispatchers.IO) { backgroundDao.getSelected() }
        val backgroundFile = File(
            BackgroundRepository.backgroundsDir(app),
            "round1_long_audio_background.jpg",
        )
        backgroundFile.createNewFile()
        val backgroundId = withContext(Dispatchers.IO) {
            val id = backgroundDao.insert(
                BackgroundImage(
                    filePath = backgroundFile.absolutePath,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            backgroundDao.selectExclusively(id)
            id
        }
        val viewModel = ConvertViewModel(app)
        val emittedMessages = mutableListOf<String>()
        val messageJob = launch {
            viewModel.userMessage.collect { emittedMessages += it }
        }

        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                viewModel.selectedBackground.first { it?.id == backgroundId }
            }
            val durations = listOf(600_000L, 600_001L)
            durations.forEach { durationMs ->
                viewModel.setAudioList(
                    listOf(
                        ConvertViewModel.SelectedAudio(
                            uri = Uri.parse("content://round1/synthetic-$durationMs.m4a"),
                            title = "synthetic-$durationMs.m4a",
                            artist = null,
                            durationMs = durationMs,
                        ),
                    ),
                )
                viewModel.startConversion()

                val infos = withTimeout(TEST_TIMEOUT_MILLIS) {
                    pollWorkInfos(workManager)
                }
                assertTrue(infos.isNotEmpty())
                workManager.cancelUniqueWork(CONVERSION_UNIQUE_WORK_NAME)
                awaitFinishedWork(workManager)
            }
            assertTrue("Unexpected conversion user message: $emittedMessages", emittedMessages.isEmpty())
        } finally {
            messageJob.cancel()
            workManager.cancelUniqueWork(CONVERSION_UNIQUE_WORK_NAME)
            withContext(Dispatchers.IO) {
                backgroundDao.deleteById(backgroundId)
                previousBackground?.let { backgroundDao.selectExclusively(it.id) }
            }
            backgroundFile.delete()
        }
    }

    private suspend fun pollWorkInfos(workManager: WorkManager): List<WorkInfo> {
        var infos = emptyList<WorkInfo>()
        withTimeout(TEST_TIMEOUT_MILLIS) {
            do {
                infos = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(CONVERSION_UNIQUE_WORK_NAME).get()
                }
                if (infos.isEmpty()) delay(POLL_DELAY_MILLIS)
            } while (infos.isEmpty())
        }
        return infos
    }

    private suspend fun awaitFinishedWork(workManager: WorkManager) {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (true) {
                val infos = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(CONVERSION_UNIQUE_WORK_NAME).get()
                }
                if (infos.isNotEmpty() && infos.all { it.state.isFinished }) return@withTimeout
                delay(POLL_DELAY_MILLIS)
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
        const val POLL_DELAY_MILLIS = 25L
    }
}
