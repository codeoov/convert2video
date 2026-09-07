package com.example.convert2video.record

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import com.example.convert2video.R
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingRepository
import com.example.convert2video.data.TrashRepository
import com.example.convert2video.data.TrashedItem
import com.example.convert2video.data.TrashedItemDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.work.WorkManager
import com.example.convert2video.data.BackgroundRepository
import com.example.convert2video.data.SettingsRepository
import com.example.convert2video.drive.DriveAutoUploadWorker
import com.example.convert2video.video.ConversionWorker

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecordingServiceTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val serviceRule = ServiceTestRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use the app singleton DB path for Service indexing; also open in-memory only for asserts
        // via the real AppDatabase.getInstance so Service + test share the same DB file.
        AppDatabase.clearInstance()
        db = AppDatabase.getInstance(context)
        clearTrashBestEffort()
    }

    @After
    fun tearDown() {
        runCatching {
            runCatching { serviceRule.unbindService() }
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
            Thread.sleep(300L)
            val binder = runCatching {
                serviceRule.bindService(
                    Intent(context, RecordingService::class.java),
                ) as RecordingService.LocalBinder
            }.getOrNull()
            if (binder != null) {
                val service = binder.service
                when (val state = service.state.value) {
                    is RecordingState.Review -> {
                        val review = state
                        context.startService(
                            Intent(context, RecordingService::class.java).apply {
                                action = RecordingService.ACTION_DISCARD
                                putExtra(
                                    RecordingService.EXTRA_REVIEW_FILE_PATH,
                                    review.outputFile.absolutePath,
                                )
                                putExtra(RecordingService.EXTRA_REVIEW_ELAPSED_MS, review.elapsedMs)
                                putExtra(
                                    RecordingService.EXTRA_FORMAT,
                                    recordingFormatFromFile(review.outputFile).name,
                                )
                                putExtra(RecordingService.EXTRA_SESSION_ID, service.sessionId)
                            },
                        )
                        awaitState(service, timeoutMs = 5_000L) { it is RecordingState.Idle }
                    }
                    is RecordingState.Recording, is RecordingState.Paused -> {
                        context.startService(
                            Intent(context, RecordingService::class.java)
                                .setAction(RecordingService.ACTION_STOP),
                        )
                        awaitState(service, timeoutMs = 5_000L) {
                            it is RecordingState.Idle ||
                                it is RecordingState.Failed ||
                                it is RecordingState.Review
                        }
                        if (service.state.value is RecordingState.Review) {
                            context.startService(
                                Intent(context, RecordingService::class.java)
                                    .setAction(RecordingService.ACTION_DISCARD),
                            )
                            awaitState(service, timeoutMs = 5_000L) { it is RecordingState.Idle }
                        }
                    }
                    else -> Unit
                }
                service.trashRepositoryForTest = null
                runCatching { serviceRule.unbindService() }
                runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
            }
            Thread.sleep(300L)
        }
        runCatching {
            runBlocking { SettingsRepository(context).setLastUsedBackgroundPath(null) }
        }
        AppDatabase.clearInstance()
    }

    @Test
    fun success_startPauseResumeStop_mapsToIdleAndIndexesRepo() {
        // Given — clear prior recording rows for this device DB
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)

        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service

        // When / Then — START → Recording
        awaitState(service) { it is RecordingState.Recording }
        assertTrue(RecordingService.isRunning())

        // PAUSE
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE_RESUME),
        )
        awaitState(service) { it is RecordingState.Paused }

        // RESUME
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE_RESUME),
        )
        awaitState(service) { it is RecordingState.Recording }

        // Second START while active — must not crash; session stays active
        serviceRule.startService(start)
        assertTrue(RecordingService.isRunning())

        // Saved 경로: MIN+slack 이상까지 대기
        awaitState(service, timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
            it is RecordingState.Recording &&
                it.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }

        // STOP → leave Recording/Paused immediately (Stopping), then Review (no insert)
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
        awaitState(service) {
            it !is RecordingState.Recording && it !is RecordingState.Paused
        }
        assertTrue(
            service.state.value is RecordingState.Stopping ||
                service.state.value is RecordingState.Review,
        )
        awaitState(service) { it is RecordingState.Review }
        val review = service.state.value as RecordingState.Review
        assertTrue(review.outputFile.exists())
        assertTrue(review.elapsedMs >= 0L)
        assertTrue(RecordingService.isRunning())
        val rowsBeforeKeep = runBlocking {
            RecordingRepository(context, db.recordingDao()).recordings.first()
        }
        assertTrue(rowsBeforeKeep.isEmpty())

        val conversionIdsBeforeKeep = conversionWorkerIds()
        val driveIdsBeforeKeep = driveWorkerIds()
        plantLastUsedBackgroundForEnqueue()

        // KEEP → Saved + Repo row + Drive/AutoConvert enqueue attempted
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_KEEP),
        )
        awaitState(service) { it is RecordingState.Saved }
        val saved = service.state.value as RecordingState.Saved
        assertTrue(saved.outputFile.exists())
        assertTrue(saved.elapsedMs >= 0L)

        val rows = runBlocking { RecordingRepository(context, db.recordingDao()).recordings.first() }
        assertTrue(rows.isNotEmpty())
        assertEquals("AAC", rows.first().format)
        assertTrue(
            "Keep must enqueue ConversionWorker (auto-convert) unlike TOO_SHORT",
            conversionWorkerIds() != conversionIdsBeforeKeep,
        )
        // Drive enqueue is attempted; without auth it may no-op (convert-only, not INDEX_FAILED)
        assertTrue(driveWorkerIds().containsAll(driveIdsBeforeKeep))
        assertTrue(service.state.value is RecordingState.Saved)
        clearLastUsedBackground()
    }

    @Test
    fun success_stopTooShort_mapsToFailedTooShort_withoutDbOrFile() {
        // Given
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }

        val storageDir = C2vRecordingNames.appStorageDir(context)
        val namesBeforeStart =
            storageDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)

        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service

        awaitState(service) { it is RecordingState.Recording }
        val conversionIdsBeforeStop = conversionWorkerIds()
        // MediaRecorder.stop()은 캡처 직후 throw — MIN(5s) 미만 유지하며 짧게만 대기
        awaitState(service, timeoutMs = 2_000L) {
            it is RecordingState.Recording && it.elapsedMs >= 200L
        }
        // When — Recording 직후 STOP (duration <= MIN)
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )

        // Then — Failed(TOO_SHORT), DB 행 없음, 산출 파일 없음, auto-convert 미enqueue
        awaitState(service) { it is RecordingState.Failed }
        val failed = service.state.value as RecordingState.Failed
        assertEquals(RecordingErrorCodes.TOO_SHORT, failed.errorCode)

        val rows = runBlocking { RecordingRepository(context, db.recordingDao()).recordings.first() }
        assertTrue(rows.isEmpty())

        val namesAfter =
            storageDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val leftover = namesAfter - namesBeforeStart
        assertTrue(
            "TOO_SHORT 후 신규 산출 파일이 없어야 함: $leftover",
            leftover.isEmpty(),
        )
        assertFalse(RecordingService.isRunning())
        assertEquals(
            "TOO_SHORT must not enqueue ConversionWorker (auto-convert)",
            conversionIdsBeforeStop,
            conversionWorkerIds(),
        )
    }

    @Test
    fun success_stopTooShortFromPaused_mapsToFailedTooShort() {
        // Given — 짧게 Recording → Pause 후 STOP (durationMs <= MIN)
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }

        val storageDir = C2vRecordingNames.appStorageDir(context)
        val namesBeforeStart =
            storageDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)

        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service

        awaitState(service) { it is RecordingState.Recording }
        // Pause 전 MediaRecorder 안정화 — Paused에서는 elapsed가 증가하지 않음
        awaitState(service, timeoutMs = 2_000L) {
            it is RecordingState.Recording && it.elapsedMs >= 200L
        }
        context.startService(
            Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_PAUSE_RESUME),
        )
        awaitState(service) { it is RecordingState.Paused }
        val conversionIdsBeforeStop = conversionWorkerIds()

        // When — Paused에서 STOP (duration <= MIN)
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )

        // Then — Failed(TOO_SHORT), DB 행 없음, 산출 파일 없음, auto-convert 미enqueue
        awaitState(service) { it is RecordingState.Failed }
        val failed = service.state.value as RecordingState.Failed
        assertEquals(RecordingErrorCodes.TOO_SHORT, failed.errorCode)
        val rows = runBlocking { RecordingRepository(context, db.recordingDao()).recordings.first() }
        assertTrue(rows.isEmpty())

        val namesAfter =
            storageDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val leftover = namesAfter - namesBeforeStart
        assertTrue(
            "TOO_SHORT(Paused) 후 신규 산출 파일이 없어야 함: $leftover",
            leftover.isEmpty(),
        )
        assertFalse(RecordingService.isRunning())
        assertEquals(
            "TOO_SHORT(Paused) must not enqueue ConversionWorker (auto-convert)",
            conversionIdsBeforeStop,
            conversionWorkerIds(),
        )
    }

    @Test
    fun success_stopPastMin_mapsToReview_thenDiscardGoesIdleWithoutRow() {
        // Given
        clearTrashBestEffort()
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }
        val trashRepo = TrashRepository.create(context)
        val trashCountBeforeDiscard = runBlocking { trashRepo.observeAll().first().size }

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        service.trashRepositoryForTest = trashRepo
        try {
            awaitState(service) { it is RecordingState.Recording }
            awaitState(service, timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
                it is RecordingState.Recording &&
                    it.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
            }
            val conversionIdsBeforeStop = conversionWorkerIds()

            // When — STOP → Review (Room 0, file kept)
            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            )
            awaitState(service) { it is RecordingState.Review }
            val review = service.state.value as RecordingState.Review
            val reviewOutputPath = review.outputFile.absolutePath
            assertTrue(review.outputFile.exists())
            val rowsAtReview = runBlocking {
                RecordingRepository(context, db.recordingDao()).recordings.first()
            }
            assertTrue(rowsAtReview.isEmpty())
            assertEquals(conversionIdsBeforeStop, conversionWorkerIds())
            assertTrue(RecordingService.isRunning())

            // When — DISCARD → Idle, no recording row, trash row + file
            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_DISCARD),
            )
            awaitState(service) { it is RecordingState.Idle }
            val rowsAfter = runBlocking {
                RecordingRepository(context, db.recordingDao()).recordings.first()
            }
            assertTrue(rowsAfter.isEmpty())
            awaitTrashCount(trashRepo, trashCountBeforeDiscard + 1)
            val trashRowsAfter = runBlocking { trashRepo.observeAll().first() }
            val trashed = trashRowsAfter.single { it.displayName == review.outputFile.name }
            assertEquals(review.outputFile.name, trashed.displayName)
            assertEquals(TrashedItem.RECORDING_AUDIO, trashed.itemType)
            assertFalse(trashed.wasIndexed)
            assertNull(trashed.originalFilePath)
            assertEquals("AAC", trashed.recordingFormat)
            assertEquals(review.elapsedMs, trashed.durationMs)
            assertTrue(File(trashed.trashFilePath).exists())
            assertFalse(File(reviewOutputPath).exists())
            assertFalse(RecordingService.isRunning())
        } finally {
            service.trashRepositoryForTest = null
        }
    }

    @Test
    fun failure_discardMoveToTrashFails_fallbackDeletesOutputAndIdle() {
        // Given — Review까지 동일; moveToTrash null seam
        clearTrashBestEffort()
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }
        val trashRepo = TrashRepository.create(context)
        val trashCountBeforeDiscard = runBlocking { trashRepo.observeAll().first().size }

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        service.trashRepositoryForTest = TrashRepository(
            context,
            InsertThrowingDao(db.trashedItemDao(), RuntimeException("insert boom")),
            db.recordingDao(),
            db.importedAudioDao(),
        )
        try {
            awaitState(service) { it is RecordingState.Recording }
            awaitState(service, timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
                it is RecordingState.Recording &&
                    it.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
            }
            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            )
            awaitState(service) { it is RecordingState.Review }
            val review = service.state.value as RecordingState.Review
            val reviewOutputPath = review.outputFile.absolutePath
            assertTrue(review.outputFile.exists())

            // When — DISCARD with moveToTrash → null
            context.startService(
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_DISCARD),
            )
            awaitState(service) { it is RecordingState.Idle }

            // Then — Idle, no recording row, trash unchanged, output deleted
            val rowsAfter = runBlocking {
                RecordingRepository(context, db.recordingDao()).recordings.first()
            }
            assertTrue(rowsAfter.isEmpty())
            val trashRowsAfter = runBlocking { trashRepo.observeAll().first() }
            assertEquals(trashCountBeforeDiscard, trashRowsAfter.size)
            assertFalse(File(reviewOutputPath).exists())
            assertFalse(RecordingService.isRunning())
        } finally {
            service.trashRepositoryForTest = null
        }
    }

    @Test
    fun failure_discardCancellationException_cleansUpThenRethrows() {
        // Given — Review까지 동일; insert CancellationException seam
        clearTrashBestEffort()
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }
        val trashRepo = TrashRepository.create(context)
        val trashCountBeforeDiscard = runBlocking { trashRepo.observeAll().first().size }

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        service.trashRepositoryForTest = TrashRepository(
            context,
            InsertThrowingDao(db.trashedItemDao(), CancellationException("cancel")),
            db.recordingDao(),
            db.importedAudioDao(),
        )
        try {
            awaitState(service) { it is RecordingState.Recording }
            awaitState(service, timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
                it is RecordingState.Recording &&
                    it.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
            }
            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            )
            awaitState(service) { it is RecordingState.Review }
            val review = service.state.value as RecordingState.Review
            val reviewOutputPath = review.outputFile.absolutePath
            assertTrue(review.outputFile.exists())

            // When — DISCARD with insert CancellationException
            context.startService(
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_DISCARD),
            )
            awaitState(service) { it is RecordingState.Idle }

            // Then — Idle, no recording row, trash unchanged, output deleted
            val rowsAfter = runBlocking {
                RecordingRepository(context, db.recordingDao()).recordings.first()
            }
            assertTrue(rowsAfter.isEmpty())
            val trashRowsAfter = runBlocking { trashRepo.observeAll().first() }
            assertEquals(trashCountBeforeDiscard, trashRowsAfter.size)
            assertFalse(File(reviewOutputPath).exists())
            assertFalse(RecordingService.isRunning())
        } finally {
            service.trashRepositoryForTest = null
        }
    }

    @Test
    fun success_stopPastMin_discardWav_movesToTrash() {
        // Given — WAV format discard path
        clearTrashBestEffort()
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }
        val trashRepo = TrashRepository.create(context)
        val trashCountBeforeDiscard = runBlocking { trashRepo.observeAll().first().size }

        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.WAV.name)
        }
        serviceRule.startService(start)
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        service.trashRepositoryForTest = trashRepo
        try {
            awaitState(service) { it is RecordingState.Recording }
            awaitState(service, timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
                it is RecordingState.Recording &&
                    it.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
            }

            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            )
            awaitState(service) { it is RecordingState.Review }
            val review = service.state.value as RecordingState.Review
            val reviewOutputPath = review.outputFile.absolutePath
            assertTrue(review.outputFile.name.endsWith(".wav"))

            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_DISCARD),
            )
            awaitState(service) { it is RecordingState.Idle }

            awaitTrashCount(trashRepo, trashCountBeforeDiscard + 1)
            val trashRowsAfter = runBlocking { trashRepo.observeAll().first() }
            val trashed = trashRowsAfter.single { it.displayName == review.outputFile.name }
            assertEquals(TrashedItem.RECORDING_AUDIO, trashed.itemType)
            assertEquals("WAV", trashed.recordingFormat)
            assertTrue(File(trashed.trashFilePath).exists())
            assertTrue(trashed.trashFilePath.endsWith(".wav"))
            assertFalse(File(reviewOutputPath).exists())
            assertFalse(RecordingService.isRunning())
        } finally {
            service.trashRepositoryForTest = null
        }
    }

    @Test
    fun success_foregroundStartDenied_stringRes_andBindWindowContract() {
        // Given / When / Then — device에서도 매핑·FG 지연/즉시 stop 계약 유지
        assertEquals(
            R.string.recording_foreground_start_denied,
            recordingErrorCodeToStringRes(RecordingErrorCodes.FOREGROUND_START_DENIED),
        )
        assertTrue(RECORDING_FAILED_BIND_WINDOW_MS > 0L)
        // FG 성공 → 지연 demote; FG 미진입 → 즉시 stop (지연 금지)
        assertTrue(shouldScheduleDelayedStopSelf(enteredForeground = true))
        assertFalse(shouldScheduleDelayedStopSelf(enteredForeground = false))
        // busy/deny bind 창 → 재START ignore
        assertTrue(shouldIgnoreStartWhileSessionBusy(isSessionActive = true))
        assertFalse(shouldIgnoreStartWhileSessionBusy(isSessionActive = false))
        // Idle + Service 활성 → 워치독 clear 금지
        assertFalse(
            shouldClearStuckExpectedSession(
                expectedSessionId = 1L,
                startSessionId = 1L,
                current = RecordingState.Idle,
                isServiceRunning = true,
            ),
        )
    }

    @Test
    fun success_serviceScopeExceptionHandler_logOnly_doesNotEmitFailed() {
        // Given — bind Idle Service. A_seam 순수 계약은 NotificationTest SSOT.
        // 여기서는 serviceScope CEH(log-only)가 state를 Failed로 바꾸지 않음을 증명.
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        awaitState(service) { it is RecordingState.Idle }
        val before = service.state.value

        // When — uncaught on serviceScope (onFailed=null Handler)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.launchUncaughtForTest(RuntimeException("uncaught-service-ceh"))
        }

        // Then — settle window: Idle 유지 · Failed 미발행 (Thread.sleep 고정 대기 금지)
        awaitStill(service, settleMs = 800L) { state ->
            state is RecordingState.Idle && state == before && state !is RecordingState.Failed
        }
        assertEquals(before, service.state.value)
        assertTrue(service.state.value is RecordingState.Idle)
    }

    @Test
    fun success_reviewOccupancy_startIgnored_isRunningTrue() {
        // Given — STOP>5s → Review holds the file (occupancy ≠ Tile active session)
        runBlocking {
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }
        val start = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
        }
        serviceRule.startService(start)
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        awaitState(service) { it is RecordingState.Recording }
        awaitState(service, timeoutMs = RecordingSavedWait.timeoutForSavedWaitMs) {
            it is RecordingState.Recording &&
                it.elapsedMs >= RecordingSavedWait.minElapsedForSavedMs
        }
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
        )
        awaitState(service) { it is RecordingState.Review }
        val reviewFile = (service.state.value as RecordingState.Review).outputFile.path
        assertTrue(RecordingService.isRunning())

        // When — START while Review occupancy
        serviceRule.startService(start)

        // Then — still Review, no second session
        awaitStill(service, settleMs = 400L) { it is RecordingState.Review }
        assertEquals(reviewFile, (service.state.value as RecordingState.Review).outputFile.path)
        assertTrue(RecordingService.isRunning())

        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_DISCARD),
        )
        awaitState(service) { it is RecordingState.Idle }
    }

    @Test
    fun success_keepWithoutPendingReviewOrExtras_staysIdle() {
        // Given — Idle Service, no pendingReview
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        awaitState(service) { it is RecordingState.Idle }
        val occupancyBefore = RecordingService.isRunning()

        // When — KEEP without extras
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_KEEP),
        )

        // Then — ignored, Idle (not Failed, no insert, occupancy unchanged)
        awaitStill(service, settleMs = 400L) { it is RecordingState.Idle }
        val rows = runBlocking {
            RecordingRepository(context, db.recordingDao()).recordings.first()
        }
        assertTrue(rows.isEmpty())
        assertEquals(occupancyBefore, RecordingService.isRunning())
        assertTrue(service.state.value is RecordingState.Idle)
    }

    @Test
    fun success_keepExtrasFromIdle_doesNotInsert() {
        // Given — Idle + confined extras must not insert (G1)
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        awaitState(service) { it is RecordingState.Idle }
        val storageDir = C2vRecordingNames.appStorageDir(context)
        val decoy = File(storageDir, "idle_keep_extras_decoy.m4a")
        decoy.writeBytes(byteArrayOf(0, 1, 2, 3))
        val occupancyBefore = RecordingService.isRunning()
        try {
            context.startService(
                Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_KEEP
                    putExtra(RecordingService.EXTRA_REVIEW_FILE_PATH, decoy.absolutePath)
                    putExtra(RecordingService.EXTRA_REVIEW_ELAPSED_MS, 6_000L)
                    putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
                    putExtra(RecordingService.EXTRA_SESSION_ID, 99L)
                },
            )
            awaitStill(service, settleMs = 400L) { it is RecordingState.Idle }
            val rows = runBlocking {
                RecordingRepository(context, db.recordingDao()).recordings.first()
            }
            assertTrue(rows.isEmpty())
            assertEquals(occupancyBefore, RecordingService.isRunning())
            assertTrue(service.state.value is RecordingState.Idle)
        } finally {
            decoy.delete()
        }
    }

    @Test
    fun success_discardExtrasFromIdle_isNoOpNotStopSelf() {
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        awaitState(service) { it is RecordingState.Idle }
        val storageDir = C2vRecordingNames.appStorageDir(context)
        val decoy = File(storageDir, "idle_discard_extras_decoy.m4a")
        decoy.writeBytes(byteArrayOf(0, 1, 2, 3))
        val occupancyBefore = RecordingService.isRunning()
        try {
            context.startService(
                Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_DISCARD
                    putExtra(RecordingService.EXTRA_REVIEW_FILE_PATH, decoy.absolutePath)
                    putExtra(RecordingService.EXTRA_REVIEW_ELAPSED_MS, 6_000L)
                    putExtra(RecordingService.EXTRA_FORMAT, RecordingFormat.AAC.name)
                    putExtra(RecordingService.EXTRA_SESSION_ID, 99L)
                },
            )
            awaitStill(service, settleMs = 400L) { it is RecordingState.Idle }
            assertTrue(decoy.exists())
            assertEquals(occupancyBefore, RecordingService.isRunning())
            assertTrue(service.state.value is RecordingState.Idle)
        } finally {
            decoy.delete()
        }
    }

    @Test
    fun aaa_success_discardWithoutPendingReviewOrExtras_isNoOpNotStopSelf() {
        // Given — Idle Service, no pending review. Occupancy was never claimed.
        val binder = serviceRule.bindService(
            Intent(context, RecordingService::class.java),
        ) as RecordingService.LocalBinder
        val service = binder.service
        awaitState(service) { it is RecordingState.Idle }
        val occupancyBefore = RecordingService.isRunning()
        val stateBefore = service.state.value

        // When — DISCARD without extras → true no-op (Keep ignore와 동일, stopSelf 금지)
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_DISCARD),
        )

        // Then — state machine: Idle 유지, occupancy 불변 (isRunning()==false 로 stopSelf를 고정하지 않음)
        awaitStill(service, settleMs = 400L) { it is RecordingState.Idle }
        assertEquals(stateBefore, service.state.value)
        assertEquals(occupancyBefore, RecordingService.isRunning())
        assertTrue(service.state.value is RecordingState.Idle)
    }

    private fun awaitTrashCount(
        trashRepo: TrashRepository,
        expected: Int,
        timeoutMs: Long = 8_000L,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val count = runBlocking { trashRepo.observeAll().first().size }
            if (count == expected) return
            Thread.sleep(50L)
        }
        val last = runBlocking { trashRepo.observeAll().first().size }
        throw AssertionError("Timed out waiting for trash count=$expected; last=$last")
    }

    private fun awaitState(
        service: RecordingService,
        timeoutMs: Long = 8_000L,
        predicate: (RecordingState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (predicate(service.state.value)) return
            Thread.sleep(50L)
        }
        throw AssertionError("Timed out waiting for state; last=${service.state.value}")
    }

    /** settleMs 동안 predicate가 계속 참이어야 한다 (Controller awaitStillSaved와 동일 패턴). */
    private fun awaitStill(
        service: RecordingService,
        settleMs: Long,
        predicate: (RecordingState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMs)
        while (System.nanoTime() < deadline) {
            val current = service.state.value
            if (!predicate(current)) {
                throw AssertionError("log-only CEH mutated state; last=$current")
            }
            Thread.sleep(40L)
        }
    }

    private fun conversionWorkerIds(): Set<UUID> =
        WorkManager.getInstance(context)
            .getWorkInfosByTag(ConversionWorker::class.java.name)
            .get(5L, TimeUnit.SECONDS)
            .map { it.id }
            .toSet()

    private fun driveWorkerIds(): Set<UUID> =
        WorkManager.getInstance(context)
            .getWorkInfosByTag(DriveAutoUploadWorker::class.java.name)
            .get(5L, TimeUnit.SECONDS)
            .map { it.id }
            .toSet()

    private fun plantLastUsedBackgroundForEnqueue() {
        val bgFile = File(BackgroundRepository.backgroundsDir(context), "keep_enqueue_bg.jpg")
        bgFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        runBlocking {
            SettingsRepository(context).setLastUsedBackgroundPath(bgFile.absolutePath)
        }
    }

    private fun clearLastUsedBackground() {
        runBlocking {
            SettingsRepository(context).setLastUsedBackgroundPath(null)
        }
    }

    private fun clearTrashBestEffort() {
        runBlocking {
            val trashRepo = TrashRepository.create(context)
            trashRepo.observeAll().first().forEach { item ->
                runCatching { trashRepo.permanentlyDelete(item) }
            }
            TrashRepository.trashDir(context).listFiles()?.forEach { file ->
                runCatching {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
        }
    }
}

private class InsertThrowingDao(
    private val real: TrashedItemDao,
    private val error: Throwable,
) : TrashedItemDao {
    override fun observeAll(): Flow<List<TrashedItem>> = real.observeAll()
    override suspend fun insert(item: TrashedItem): Long = throw error
    override suspend fun deleteById(id: Long) = real.deleteById(id)
    override suspend fun listExpired(cutoffEpochMs: Long): List<TrashedItem> =
        real.listExpired(cutoffEpochMs)
}
