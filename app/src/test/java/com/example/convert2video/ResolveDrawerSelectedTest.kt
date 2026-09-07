package com.example.convert2video

import com.example.convert2video.ui.screens.home.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [resolveDrawerSelected] — Drawer selected SSOT.
 * [restoreAppDestination] — rememberSaveable 폴백 (구버전 enum name → Home).
 */
class ResolveDrawerSelectedTest {

    @Test
    fun success_mapsDrawerVisibleDestinations() {
        // Given / When / Then
        assertEquals(AppDestination.Home, resolveDrawerSelected(AppDestination.Home))
        assertEquals(AppDestination.Options, resolveDrawerSelected(AppDestination.Options))
        assertEquals(AppDestination.Trash, resolveDrawerSelected(AppDestination.Trash))
        assertEquals(AppDestination.ErrorLog, resolveDrawerSelected(AppDestination.ErrorLog))
        assertEquals(
            AppDestination.MicrophoneSource,
            resolveDrawerSelected(AppDestination.MicrophoneSource),
        )
    }

    @Test
    fun success_nonDrawerDestinationsReturnNull() {
        // Given / When / Then
        assertNull(resolveDrawerSelected(AppDestination.Convert))
        assertNull(resolveDrawerSelected(AppDestination.BackgroundPick))
        assertNull(resolveDrawerSelected(AppDestination.AudioPick))
    }

    @Test
    fun success_restoreAppDestination_knownNames() {
        // Given / When / Then — 현재 enum name은 그대로 복원
        assertEquals(AppDestination.Home, restoreAppDestination("Home"))
        assertEquals(AppDestination.Convert, restoreAppDestination("Convert"))
        assertEquals(AppDestination.Options, restoreAppDestination("Options"))
        assertEquals(AppDestination.ErrorLog, restoreAppDestination("ErrorLog"))
        assertEquals(AppDestination.BackgroundPick, restoreAppDestination("BackgroundPick"))
        assertEquals(AppDestination.AudioPick, restoreAppDestination("AudioPick"))
        assertEquals(AppDestination.Trash, restoreAppDestination("Trash"))
        assertEquals(AppDestination.MicrophoneSource, restoreAppDestination("MicrophoneSource"))
    }

    @Test
    fun success_restoreAppDestination_legacyConvertedVideosFallsBackToHome() {
        // Given — 구버전 top-level ConvertedVideos destination
        // When / Then
        assertEquals(AppDestination.Home, restoreAppDestination("ConvertedVideos"))
    }

    @Test
    fun success_restoreLegacyHomeTabFromSavedDestination() {
        // Given / When / Then — ConvertedVideos legacy name → Listen tab 보정
        assertEquals(
            HomeTab.ConvertedVideos,
            restoreLegacyHomeTabFromSavedDestination("ConvertedVideos"),
        )
        assertNull(restoreLegacyHomeTabFromSavedDestination("Home"))
    }

    @Test
    fun success_restoreAppDestination_unknownFallsBackToHome() {
        // Given — 구버전 Record/RecordingsList 및 쓰레기 값
        // When / Then
        assertEquals(AppDestination.Home, restoreAppDestination("Record"))
        assertEquals(AppDestination.Home, restoreAppDestination("RecordingsList"))
        assertEquals(AppDestination.Home, restoreAppDestination(""))
        assertEquals(AppDestination.Home, restoreAppDestination("NotADestination"))
    }
}
