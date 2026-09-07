package com.example.convert2video.ui.screens.language_pick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.convert2video.R
import com.example.convert2video.data.LanguageOption
import com.example.convert2video.ui.components.cards.C2vCard

/**
 * First-launch language prompt UI branch.
 *
 * Gate matrix ([resolveLanguagePromptGate]):
 * - null → [LanguagePromptUi.Loading]
 * - false + !committed → [LanguagePromptUi.Picker] (stays mounted during apply)
 * - false + committed → [LanguagePromptUi.ApplyingProgress] (prompt written, awaiting recreate)
 * - true + !committed → [LanguagePromptUi.App]
 * - true + committed → [LanguagePromptUi.ApplyingProgress] (rare)
 */
enum class LanguagePromptUi {
    Loading,
    Picker,
    ApplyingProgress,
    App,
}

/**
 * Pure gate for first-launch language prompt vs main app.
 * [committed] must become true only after successful [setLanguagePromptShown]
 * (or immediately before recreate) — never at tap start — so Picker stays mounted
 * while apply runs.
 */
fun resolveLanguagePromptGate(
    promptShown: Boolean?,
    committed: Boolean,
): LanguagePromptUi = when {
    promptShown == null -> LanguagePromptUi.Loading
    promptShown == false && !committed -> LanguagePromptUi.Picker
    promptShown == false && committed -> LanguagePromptUi.ApplyingProgress
    promptShown == true && !committed -> LanguagePromptUi.App
    else -> LanguagePromptUi.ApplyingProgress // true && committed
}

/**
 * First-launch language chooser UI only (no ViewModel / no apply coroutine).
 * Apply is owned by [MainActivity] setContent scope so it survives recomposition.
 *
 * Native option labels stay hardcoded (`"English"` / `"한국어"`) so they do not flip with locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerScreen(
    isApplying: Boolean,
    onLanguageSelected: (LanguageOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.language_picker_title)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LanguageOptionRow(
                    label = "English",
                    enabled = !isApplying,
                    onClick = { onLanguageSelected(LanguageOption.English) },
                )
                LanguageOptionRow(
                    label = "한국어",
                    enabled = !isApplying,
                    onClick = { onLanguageSelected(LanguageOption.Korean) },
                )
            }
            if (isApplying) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun LanguageOptionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    C2vCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(20.dp),
        )
    }
}
