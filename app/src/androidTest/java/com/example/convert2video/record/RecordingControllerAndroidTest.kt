package com.example.convert2video.record

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.RecordingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Controller sticky·bind 대기·세션 재시작 계약 검증 (기기/에뮬 마이크 필요).
 */
@RunWith(AndroidJUnit4::class)
class RecordingControllerAndroidTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var controller: RecordingController

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as Application
        AppDatabase.clearInstance()
        RecordingController.clearInstanceForTest()
        controller = RecordingController.getInstance(app)
        runBlocking {
            val db = AppDatabase.getInstance(context)
            val repo = RecordingRepository(context, db.recordingDao())
            repo.recordings.first().forEach { repo.deleteRecording(it) }
        }
        awaitServiceIdle()
    }

    @After
    fun tearDown() {
        runCatching { controller.stop() }
        awaitController(timeoutMs = 8_000L) {
            it is RecordingState.Saved ||
                it is RecordingState.Failed ||
                it is RecordingState.Idle ||
                it is RecordingState.Review
        }
        when (val state = controller.state.value) {
            is RecordingState.Review -> runCatching { controller.discard() }
            is RecordingState.Saved, is RecordingState.Failed ->
                runCatching { controller.clearTerminalState() }
            else -> Unit
        }
        RecordingController.clearInstanceForTest()
        runCatching {
            context.startService(
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_STOP),
            )
        }
        awaitServiceIdle()
        AppDatabase.clearInstance()
    }

    @Test
    fun success_stop_exposesReview_thenKeepSaved_thenClearTerminalReturnsIdle() {
        // Given
        controller.start(RecordingFormat.AAC)
        awaitController { it is RecordingState.Recording }
        awaitElapsedRecording()

        // When — stop → Review (no insert)
        controller.stop()
        awaitController { it is RecordingState.Review }
        val review = controller.state.value as RecordingState.Review
        assertTrue(review.outputFile.exists())
        assertTrue(review.elapsedMs >= 0L)

        val rowsAtReview = runBlocking {
            RecordingRepository(context, AppDatabase.getInstance(context).recordingDao())
                .recordings.first()
        }
        assertEquals(0, rowsAtReview.size)

        // STOP on Review is no-op
        controller.stop()
        assertTrue(controller.state.value is RecordingState.Review)

        // start on Review is no-op (no clear-then-restart)
        controller.start(RecordingFormat.AAC)
        assertTrue(controller.state.value is RecordingState.Review)

        // clearTerminal does not wipe Review
        controller.clearTerminalState()
        assertTrue(controller.state.value is RecordingState.Review)

        // KEEP → Saved
        controller.keep()
        awaitController { it is RecordingState.Saved }
        val saved = controller.state.value as RecordingState.Saved
        assertTrue(saved.outputFile.exists())

        controller.stop()
        assertTrue(controller.state.value is RecordingState.Saved)

        controller.clearTerminalState()
        assertEquals(RecordingState.Idle, controller.state.value)
    }

    @Test
    fun success_keepThenImmediateDiscard_convergesSavedOrReviewRemains() {
        // Given
        controller.start(RecordingFormat.AAC)
        awaitController { it is RecordingState.Recording }
        awaitElapsedRecording()
        controller.stop()
        awaitController { it is RecordingState.Review }

        // When — Keep in-flight then immediate Discard
        controller.keep()
        controller.discard()

        // Then — Saved sticky (Discard no-op) OR Review remains (Discard ignored before Keep sent)
        awaitController(timeoutMs = 12_000L) {
            it is RecordingState.Saved || it is RecordingState.Review
        }
        val after = controller.state.value
        assertTrue(
            "Keep-then-Discard must not drop to Idle; last=$after",
            after is RecordingState.Saved || after is RecordingState.Review,
        )
        if (after is RecordingState.Saved) {
            assertTrue(after.outputFile.exists())
            val rows = runBlocking {
                RecordingRepository(context, AppDatabase.getInstance(context).recordingDao())
                    .recordings.first()
            }
            assertEquals(1, rows.size)
        }
    }

    @Test
    fun success_reviewOccupancy_startNoOp_isRunningTrue() {
        // Given
        controller.start(RecordingFormat.AAC)
        awaitController { it is RecordingState.Recording }
        awaitElapsedRecording()
        controller.stop()
        awaitController { it is RecordingState.Review }
        assertTrue(RecordingService.isRunning())

        // When — occupancy Start collide 금지
        controller.start(RecordingFormat.AAC)

        // Then
        assertTrue(controller.state.value is RecordingState.Review)
        assertTrue(RecordingService.isRunning())
    }

    @Test
    fun success_savedSticky_survivesLateIdleFromRebind() {
        // Given — complete one session to Saved
        controller.start(RecordingFormat.AAC)
        awaitController { it is RecordingState.Recording }
        awaitElapsedRecording()
        controller.stop()
        awaitController { it is RecordingState.Review }
        controller.keep()
        awaitController { it is RecordingState.Saved }
        val saved = controller.state.value as RecordingState.Saved

        // When — BIND_AUTO_CREATE로 Idle Service가 다시 떠도 Controller sticky 유지
        val probeConnection = object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: android.content.ComponentName?,
                binder: android.os.IBinder?,
            ) = Unit

            override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
        }
        val bound = context.bindService(
            Intent(context, RecordingService::class.java),
            probeConnection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue(bound)
        awaitController(timeoutMs = 2_000L) { it is RecordingState.Saved }
        runCatching { context.unbindService(probeConnection) }

        // Then — late Idle must not wipe Saved; stop 이후 추가 insert 없음
        assertTrue(controller.state.value is RecordingState.Saved)
        assertEquals(saved.outputFile.path, (controller.state.value as RecordingState.Saved).outputFile.path)

        val rows = runBlocking {
            RecordingRepository(context, AppDatabase.getInstance(context).recordingDao())
                .recordings.first()
        }
        assertEquals(1, rows.size)
    }

    @Test
    fun success_restartAfterSaved_startsNewSessionWithoutStaleSticky() {
        // Given — first session → Saved
        controller.start(RecordingFormat.AAC)
        awaitController { it is RecordingState.Recording }
        awaitElapsedRecording()
        controller.stop()
        awaitController { it is RecordingState.Review }
        controller.keep()
        awaitController { it is RecordingState.Saved }
        val firstSaved = controller.state.value as RecordingState.Saved

        // When — 즉시 재시작 (finish 완료 대기 + 새 sessionId)
        awaitServiceIdle()
        controller.start(RecordingFormat.AAC)
        awaitController { it is RecordingState.Recording }
        assertTrue(controller.state.value is RecordingState.Recording)

        // 2차 stop 전에도 MIN+slack 대기 (TOO_SHORT 방지)
        awaitElapsedRecording()
        controller.stop()
        awaitController { it is RecordingState.Review }
        controller.keep()
        awaitController { it is RecordingState.Saved }
        val secondSaved = controller.state.value as RecordingState.Saved

        // Then — 새 파일(또는 최소한 sticky가 첫 Saved에 고정되지 않음)
        assertNotEquals(firstSaved.outputFile.path, secondSaved.outputFile.path)
        assertTrue(secondSaved.outputFile.exists())
    }

    @Test
    fun success_scopeExceptionHandler_fromIdle_emitsFailedForegroundStartDenied() {
        // Given — Idle (non-active). A_seam 순수 호출은 NotificationTest SSOT.
        // 여기서는 CEH → onScopeUncaughtFailed 상태 전이만 검증.
        controller.clearTerminalState()
        assertEquals(RecordingState.Idle, controller.state.value)

        // When — scope.launch uncaught → CoroutineExceptionHandler
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.launchUncaughtForTest(RuntimeException("uncaught-for-ceh"))
        }

        // Then — Failed(FOREGROUND_START_DENIED)
        awaitController(timeoutMs = 3_000L) {
            it is RecordingState.Failed &&
                it.errorCode == RecordingErrorCodes.FOREGROUND_START_DENIED
        }
        val failed = controller.state.value as RecordingState.Failed
        assertEquals(RecordingErrorCodes.FOREGROUND_START_DENIED, failed.errorCode)
    }

    @Test
    fun success_scopeExceptionHandler_duringSaved_keepsSavedSticky() {
        // Given — sticky Saved (경로 포함 상태; CEH가 덮지 않아야 함)
        val savedFile = File(context.cacheDir, "ceh_saved_sticky.m4a").also {
            it.writeText("sticky")
        }
        val saved = RecordingState.Saved(outputFile = savedFile, elapsedMs = 1_000L)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.setStateForTest(saved)
        }
        assertTrue(controller.state.value is RecordingState.Saved)

        // When
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.launchUncaughtForTest(RuntimeException("uncaught-during-saved"))
        }

        // Then — settle window 동안 Saved 유지 (Failed로 덮지 않음)
        awaitStillSaved(savedFile.path, settleMs = 800L)
    }

    private fun awaitStillSaved(expectedPath: String, settleMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMs)
        while (System.nanoTime() < deadline) {
            val current = controller.state.value
            if (current !is RecordingState.Saved) {
                throw AssertionError("Saved sticky wiped by CEH; last=$current")
            }
            assertEquals(expectedPath, current.outputFile.path)
            Thread.sleep(40L)
        }
        val finalState = controller.state.value as RecordingState.Saved
        assertEquals(expectedPath, finalState.outputFile.path)
    }

    private fun awaitElapsedRecording(
        minElapsedMs: Long = RecordingSavedWait.minElapsedForSavedMs,
        timeoutMs: Long = RecordingSavedWait.timeoutForSavedWaitMs,
    ) {
        awaitController(timeoutMs) { state ->
            state is RecordingState.Recording && state.elapsedMs >= minElapsedMs
        }
    }

    private fun awaitServiceIdle(timeoutMs: Long = 8_000L) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!RecordingService.isRunning()) return
            Thread.sleep(40L)
        }
        if (RecordingService.isRunning()) {
            throw AssertionError("Timed out waiting for RecordingService.isRunning() == false")
        }
    }

    private fun awaitController(
        timeoutMs: Long = 10_000L,
        predicate: (RecordingState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (predicate(controller.state.value)) return
            Thread.sleep(40L)
        }
        throw AssertionError("Timed out waiting for controller state; last=${controller.state.value}")
    }
}
