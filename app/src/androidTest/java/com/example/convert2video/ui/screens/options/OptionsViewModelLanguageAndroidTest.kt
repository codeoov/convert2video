package com.example.convert2video.ui.screens.options

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.convert2video.R
import com.example.convert2video.data.LanguageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OptionsViewModelLanguageAndroidTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as Application
    }

    @Test
    fun success_setLanguage_canRestartFalse_doesNotApply() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val expected = app.getString(R.string.language_picker_apply_failed)
        val current = withContext(Dispatchers.Main) { viewModel.languageOption.value }
        val other = oppositeLanguage(current)
        val applyCalls = AtomicInteger(0)
        withContext(Dispatchers.Main) {
            viewModel.applyLanguageForTest { applyCalls.incrementAndGet() }
        }

        // When
        val message = withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                val pending = async { viewModel.userMessage.first() }
                val applied = viewModel.setLanguage(other, canRestart = false)
                assertFalse(applied)
                pending.await()
            }
        }

        // Then
        withContext(Dispatchers.Main) {
            assertEquals(current, viewModel.languageOption.value)
            assertFalse(viewModel.isLanguageApplying.value)
        }
        assertEquals(0, applyCalls.get())
        assertEquals(expected, message)
    }

    @Test
    fun failure_setLanguage_applyThrows_rollsBack() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val expected = app.getString(R.string.language_picker_apply_failed)
        val current = withContext(Dispatchers.Main) { viewModel.languageOption.value }
        val other = oppositeLanguage(current)
        val applyCalls = AtomicInteger(0)
        withContext(Dispatchers.Main) {
            viewModel.applyLanguageForTest {
                if (applyCalls.incrementAndGet() == 1) {
                    throw IllegalStateException("applyLanguage boom")
                }
            }
        }

        // When
        val message = withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                val pending = async { viewModel.userMessage.first() }
                val applied = viewModel.setLanguage(other, canRestart = true)
                assertFalse(applied)
                pending.await()
            }
        }

        // Then
        withContext(Dispatchers.Main) {
            assertEquals(current, viewModel.languageOption.value)
            assertFalse(viewModel.isLanguageApplying.value)
        }
        assertEquals(2, applyCalls.get())
        assertEquals(expected, message)
    }

    @Test
    fun success_rollbackAfterRestartFailure_restoresPrevious() = runBlocking {
        // Given
        val viewModel = withContext(Dispatchers.Main) { OptionsViewModel(app) }
        val expected = app.getString(R.string.language_picker_apply_failed)
        val previous = withContext(Dispatchers.Main) { viewModel.languageOption.value }
        val other = oppositeLanguage(previous)
        withContext(Dispatchers.Main) {
            viewModel.applyLanguageForTest { }
            assertTrue(viewModel.setLanguage(other, canRestart = true))
            assertEquals(other, viewModel.languageOption.value)
            assertTrue(viewModel.isLanguageApplying.value)
        }

        // When
        val message = withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                val pending = async { viewModel.userMessage.first() }
                viewModel.rollbackAfterRestartFailure(previous)
                pending.await()
            }
        }

        // Then
        withContext(Dispatchers.Main) {
            assertEquals(previous, viewModel.languageOption.value)
            assertFalse(viewModel.isLanguageApplying.value)
        }
        assertEquals(expected, message)
    }

    private fun oppositeLanguage(current: LanguageOption): LanguageOption =
        if (current == LanguageOption.English) LanguageOption.Korean else LanguageOption.English
}

