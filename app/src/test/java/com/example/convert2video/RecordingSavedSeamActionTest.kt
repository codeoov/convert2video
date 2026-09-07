package com.example.convert2video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveRecordingSavedSeamAction] and post-Keep prompt seam regression (JVM unit).
 */
class RecordingSavedSeamActionTest {

    // Given / When / Then

    @Test
    fun success_uriOk_navigatesHomeAndAppliesAudio() {
        // Given / When
        val action = resolveRecordingSavedSeamAction(uriSucceeded = true)

        // Then — Home + 오디오 적용, terminal clear/Toast 없음
        assertTrue(action.applyAudioToHome)
        assertTrue(action.navigateHome)
        assertFalse(action.clearRecordTerminal)
        assertFalse(action.showUriFailedFeedback)
    }

    @Test
    fun failure_uriFail_staysOnRecordAndClearsTerminal() {
        // Given / When
        val action = resolveRecordingSavedSeamAction(uriSucceeded = false)

        // Then — Record 잔류 + 좀비 Saved 방지 + 피드백. 오디오 미적용
        assertFalse(action.applyAudioToHome)
        assertFalse(action.navigateHome)
        assertTrue(action.clearRecordTerminal)
        assertTrue(action.showUriFailedFeedback)
        assertEquals(
            RecordingSavedSeamAction(
                applyAudioToHome = false,
                navigateHome = false,
                clearRecordTerminal = true,
                showUriFailedFeedback = true,
            ),
            action,
        )
    }

    @Test
    fun success_promptShownForSuccessfulKeepWithoutBackupOrHandledFlag() {
        // Given / When / Then — only the explicit successful Keep callback candidate can show it.
        assertTrue(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.SuccessfulKeepCallback,
                isBackupFolderConnected = false,
                isPromptHandled = false,
            ),
        )
    }

    @Test
    fun success_promptNotShownWhenUriIsUnavailable() {
        // Given / When / Then — URI failure never produces a successful Keep prompt candidate.
        assertFalse(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.UriUnavailable,
                isBackupFolderConnected = false,
                isPromptHandled = false,
            ),
        )
    }

    @Test
    fun success_promptNotShownWhenBackupIsConnected() {
        // Given / When / Then — connected means the existing persisted read/write grant predicate passed.
        assertFalse(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.SuccessfulKeepCallback,
                isBackupFolderConnected = true,
                isPromptHandled = false,
            ),
        )
    }

    @Test
    fun success_promptShownWhenBackupGrantIsBrokenOrInaccessible() {
        // Given / When / Then — broken/inaccessible grant is treated as not connected.
        assertTrue(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.SuccessfulKeepCallback,
                isBackupFolderConnected = false,
                isPromptHandled = false,
            ),
        )
    }

    @Test
    fun success_promptNotShownWhenKeepFailed() {
        // Given / When / Then — Keep failure is an explicit no-prompt candidate.
        assertFalse(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.KeepFailed,
                isBackupFolderConnected = false,
                isPromptHandled = false,
            ),
        )
    }

    @Test
    fun success_promptNotShownWhenRecordingDiscarded() {
        // Given / When / Then — discard is an explicit no-prompt candidate.
        assertFalse(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.Discarded,
                isBackupFolderConnected = false,
                isPromptHandled = false,
            ),
        )
    }

    @Test
    fun success_promptNotShownWhenHandledFlagIsTrue() {
        // Given / When / Then
        assertFalse(
            shouldShowRecordingBackupPrompt(
                candidate = RecordingBackupPromptCandidate.SuccessfulKeepCallback,
                isBackupFolderConnected = false,
                isPromptHandled = true,
            ),
        )
    }

    @Test
    fun success_promptOptionsNavigatesToExistingOptionsScreenAndDismissStaysPut() {
        // Given / When / Then — Options opens the existing backup card/picker entry point; no picker is launched here.
        assertEquals(
            AppDestination.Options,
            resolveRecordingBackupPromptDestination(openOptions = true),
        )
        assertEquals(
            null,
            resolveRecordingBackupPromptDestination(openOptions = false),
        )
    }

    @Test
    fun success_promptActionClosesAndNavigatesOnlyAfterHandledPersistenceSucceeds() {
        // Given / When / Then — failed persistence leaves both UI effects disabled.
        assertEquals(
            RecordingBackupPromptActionResult(
                closePrompt = false,
                navigateToOptions = false,
            ),
            resolveRecordingBackupPromptActionResult(
                openOptions = true,
                persistence = RecordingBackupPromptPersistence.Failed,
            ),
        )
        assertEquals(
            RecordingBackupPromptActionResult(
                closePrompt = true,
                navigateToOptions = false,
            ),
            resolveRecordingBackupPromptActionResult(
                openOptions = false,
                persistence = RecordingBackupPromptPersistence.Succeeded,
            ),
        )
        assertEquals(
            RecordingBackupPromptActionResult(
                closePrompt = true,
                navigateToOptions = true,
            ),
            resolveRecordingBackupPromptActionResult(
                openOptions = true,
                persistence = RecordingBackupPromptPersistence.Succeeded,
            ),
        )
    }
}
