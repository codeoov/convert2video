package com.example.convert2video.record

import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.convert2video.MainActivity
import com.example.convert2video.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingQuickActionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetRecordingFormatHotForTests()
        RecordingController.clearInstanceForTest()
    }

    @After
    fun tearDown() {
        SettingsRepository.resetRecordingFormatHotForTests()
        RecordingController.clearInstanceForTest()
    }

    private fun robolectricShadowOf(pendingIntent: PendingIntent): Any =
        Class.forName("org.robolectric.Shadows")
            .getMethod("shadowOf", PendingIntent::class.java)
            .invoke(null, pendingIntent)

    private fun pendingIntentRequestCode(pendingIntent: PendingIntent): Int {
        val shadow = robolectricShadowOf(pendingIntent)
        return shadow.javaClass.getMethod("getRequestCode").invoke(shadow) as Int
    }

    private fun pendingIntentComponent(pendingIntent: PendingIntent): android.content.ComponentName? {
        val shadow = robolectricShadowOf(pendingIntent)
        val intent = shadow.javaClass.getMethod("getSavedIntent").invoke(shadow) as android.content.Intent
        return intent.component
    }

    private fun assertPendingIntentEquivalent(
        expectedRequestCode: Int,
        actual: PendingIntent,
    ) {
        val expected = scheduledRecordingAppLaunchPendingIntent(context, expectedRequestCode)
        assertEquals(pendingIntentRequestCode(expected), pendingIntentRequestCode(actual))
        assertEquals(pendingIntentComponent(expected), pendingIntentComponent(actual))
    }

    @Test
    fun success_activeSession_resolvesStopBranch() {
        // Given
        val state = RecordingState.Recording(elapsedMs = 100L, amplitude = 1)
        // When
        val branch = resolveRecordingQuickClickBranch(state, hasRecordAudioPermission = true)
        // Then
        assertEquals(RecordingQuickClickBranch.Stop, branch)
    }

    @Test
    fun success_idleWithPermission_resolvesStartRecording() {
        // Given / When
        val branch = resolveRecordingQuickClickBranch(
            state = RecordingState.Idle,
            hasRecordAudioPermission = true,
        )
        // Then
        assertEquals(RecordingQuickClickBranch.StartRecording, branch)
    }

    @Test
    fun success_idleWithoutPermission_resolvesLaunchApp() {
        // Given / When
        val branch = resolveRecordingQuickClickBranch(
            state = RecordingState.Idle,
            hasRecordAudioPermission = false,
        )
        // Then
        assertEquals(RecordingQuickClickBranch.LaunchAppForPermission, branch)
    }

    @Test
    fun success_stoppingSession_resolvesStopBranch() {
        // Given / When
        val branch = resolveRecordingQuickClickBranch(
            state = RecordingState.Stopping,
            hasRecordAudioPermission = false,
        )
        // Then
        assertEquals(RecordingQuickClickBranch.Stop, branch)
    }

    @Test
    fun success_reviewWithPermission_resolvesReviewPendingNotStart() {
        // Given
        val state = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When
        val branch = resolveRecordingQuickClickBranch(state, hasRecordAudioPermission = true)
        // Then
        assertEquals(RecordingQuickClickBranch.ReviewPending, branch)
    }

    @Test
    fun success_reviewWithoutPermission_resolvesReviewPendingNotStart() {
        // Given
        val state = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L)
        // When
        val branch = resolveRecordingQuickClickBranch(state, hasRecordAudioPermission = false)
        // Then
        assertEquals(RecordingQuickClickBranch.ReviewPending, branch)
    }

    @Test
    fun success_reviewPending_performOpensAppNotStart() = runBlocking {
        // Given
        val host = FakeQuickActionHost(
            quickActionState = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L),
        )
        Shadows.shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        var launched: PendingIntent? = null
        // When
        performRecordingQuickClick(
            context = context,
            host = host,
            permissionLaunchRequestCode = REQ_LAUNCH_TILE,
            launchAppForPermission = { launched = it },
        )
        // Then — Review opens app (not silent no-op, not Start)
        assertTrue(host.startCalls.isEmpty())
        assertFalse(host.stopCalled)
        assertPendingIntentEquivalent(REQ_LAUNCH_TILE, requireNotNull(launched))
    }

    @Test
    fun success_formatHot_usesHotValueWithoutDataStoreRead() = runBlocking {
        // Given — setRecordingFormat는 hot cache만 즉시 채움
        SettingsRepository(context).setRecordingFormat(RecordingFormat.WAV)
        // When
        val format = resolveRecordingFormatForQuickAction(context)
        // Then
        assertEquals(RecordingFormat.WAV, format)
    }

    @Test
    fun success_formatHotNull_fallsBackToDataStoreDefault() = runBlocking {
        // Given — hot null, DataStore default AAC
        SettingsRepository.resetRecordingFormatHotForTests()
        // When
        val format = resolveRecordingFormatForQuickAction(context)
        // Then
        assertEquals(RecordingFormat.AAC, format)
    }

    @Test
    fun success_activeSession_callsStopNotStart() = runBlocking {
        // Given
        val host = FakeQuickActionHost(
            quickActionState = RecordingState.Paused(elapsedMs = 500L),
        )
        // When
        performRecordingQuickClick(
            context = context,
            host = host,
            permissionLaunchRequestCode = REQ_LAUNCH_WIDGET,
            launchAppForPermission = { error("unexpected permission launch") },
        )
        // Then
        assertTrue(host.stopCalled)
        assertTrue(host.startCalls.isEmpty())
    }

    @Test
    fun success_idleWithPermission_startsWithHotFormat() = runBlocking {
        // Given
        SettingsRepository(context).setRecordingFormat(RecordingFormat.WAV)
        val host = FakeQuickActionHost(quickActionState = RecordingState.Idle)
        Shadows.shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        // When
        performRecordingQuickClick(
            context = context,
            host = host,
            permissionLaunchRequestCode = REQ_LAUNCH_TILE,
            launchAppForPermission = { error("unexpected permission launch") },
        )
        // Then
        assertEquals(listOf(RecordingFormat.WAV), host.startCalls)
        assertFalse(host.stopCalled)
    }

    @Test
    fun success_noPermission_launchesPendingIntentWithWidgetRequestCode() = runBlocking {
        // Given
        val host = FakeQuickActionHost(quickActionState = RecordingState.Idle)
        var captured: PendingIntent? = null
        // When
        performRecordingQuickClick(
            context = context,
            host = host,
            permissionLaunchRequestCode = REQ_LAUNCH_WIDGET,
            launchAppForPermission = { captured = it },
        )
        // Then
        val pi = requireNotNull(captured)
        assertPendingIntentEquivalent(REQ_LAUNCH_WIDGET, pi)
    }

    @Test
    fun success_noPermission_tileRequestCodeDiffersFromWidget() = runBlocking {
        // Given
        val host = FakeQuickActionHost(quickActionState = RecordingState.Idle)
        var tilePi: PendingIntent? = null
        var widgetPi: PendingIntent? = null
        // When
        performRecordingQuickClick(
            context = context,
            host = host,
            permissionLaunchRequestCode = REQ_LAUNCH_TILE,
            launchAppForPermission = { tilePi = it },
        )
        performRecordingQuickClick(
            context = context,
            host = host,
            permissionLaunchRequestCode = REQ_LAUNCH_WIDGET,
            launchAppForPermission = { widgetPi = it },
        )
        // Then
        assertPendingIntentEquivalent(REQ_LAUNCH_TILE, requireNotNull(tilePi))
        assertPendingIntentEquivalent(REQ_LAUNCH_WIDGET, requireNotNull(widgetPi))
    }

    @Test
    fun success_launchRecordingPermissionPendingIntent_widgetPath_startsMainActivity() {
        // Given
        val pendingIntent = scheduledRecordingAppLaunchPendingIntent(context, REQ_LAUNCH_WIDGET)
        // When
        launchRecordingPermissionPendingIntent(pendingIntent = pendingIntent, tileService = null)
        // Then
        val started = Shadows.shadowOf(context.applicationContext as android.app.Application)
            .nextStartedActivity
        assertEquals(MainActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun success_deriveWidgetUiKey_amplitudeChange_sameKey() {
        // Given — amplitude만 바뀌면 UI 키 동일 (updateAll 생략 대상)
        val keyA = QuickRecordWidgetStateSync.deriveWidgetUiKey(
            state = RecordingState.Recording(elapsedMs = 100L, amplitude = 1),
            hasRecordAudioPermission = true,
        )
        val keyB = QuickRecordWidgetStateSync.deriveWidgetUiKey(
            state = RecordingState.Recording(elapsedMs = 200L, amplitude = 999),
            hasRecordAudioPermission = true,
        )
        // Then
        assertEquals(keyA, keyB)
    }

    @Test
    fun success_deriveWidgetUiKey_activeTransition_changesKey() {
        // Given / When
        val idleKey = QuickRecordWidgetStateSync.deriveWidgetUiKey(
            state = RecordingState.Idle,
            hasRecordAudioPermission = true,
        )
        val activeKey = QuickRecordWidgetStateSync.deriveWidgetUiKey(
            state = RecordingState.Recording(elapsedMs = 0L, amplitude = 0),
            hasRecordAudioPermission = true,
        )
        // Then
        assertFalse(idleKey.isActive)
        assertTrue(activeKey.isActive)
        assertFalse(idleKey.isReview)
        assertFalse(activeKey.isReview)
    }

    @Test
    fun success_deriveWidgetUiKey_review_distinctFromIdle() {
        val idleKey = QuickRecordWidgetStateSync.deriveWidgetUiKey(
            state = RecordingState.Idle,
            hasRecordAudioPermission = true,
        )
        val reviewKey = QuickRecordWidgetStateSync.deriveWidgetUiKey(
            state = RecordingState.Review(outputFile = File("a.m4a"), elapsedMs = 6_000L),
            hasRecordAudioPermission = true,
        )
        assertFalse(reviewKey.isActive)
        assertTrue(reviewKey.isReview)
        assertFalse(idleKey.isReview)
        assertTrue(idleKey != reviewKey)
    }

    private class FakeQuickActionHost(
        override var quickActionState: RecordingState,
    ) : RecordingQuickActionHost {
        val startCalls = mutableListOf<RecordingFormat>()
        var stopCalled = false

        override fun quickActionStart(format: RecordingFormat) {
            startCalls.add(format)
        }

        override fun quickActionStop() {
            stopCalled = true
        }
    }
}
