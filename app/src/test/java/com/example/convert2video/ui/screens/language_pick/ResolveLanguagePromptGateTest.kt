package com.example.convert2video.ui.screens.language_pick

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveLanguagePromptGateTest {

    @Test
    fun success_nullPrompt_isLoading() {
        // Given / When / Then
        assertEquals(
            LanguagePromptUi.Loading,
            resolveLanguagePromptGate(promptShown = null, committed = false),
        )
        assertEquals(
            LanguagePromptUi.Loading,
            resolveLanguagePromptGate(promptShown = null, committed = true),
        )
    }

    @Test
    fun success_falseNotCommitted_isPicker() {
        // Given — apply in flight still uses this gate (committed stays false until prompt write)
        // When / Then
        assertEquals(
            LanguagePromptUi.Picker,
            resolveLanguagePromptGate(promptShown = false, committed = false),
        )
    }

    @Test
    fun success_falseCommitted_isApplyingProgress() {
        // Given — prompt written, awaiting recreate (not required at tap start)
        // When / Then
        assertEquals(
            LanguagePromptUi.ApplyingProgress,
            resolveLanguagePromptGate(promptShown = false, committed = true),
        )
    }

    @Test
    fun success_trueNotCommitted_isApp() {
        // Given / When / Then
        assertEquals(
            LanguagePromptUi.App,
            resolveLanguagePromptGate(promptShown = true, committed = false),
        )
    }

    @Test
    fun success_trueCommitted_isApplyingProgress() {
        // Given — rare: DataStore true while still awaiting recreate
        // When / Then
        assertEquals(
            LanguagePromptUi.ApplyingProgress,
            resolveLanguagePromptGate(promptShown = true, committed = true),
        )
    }
}
