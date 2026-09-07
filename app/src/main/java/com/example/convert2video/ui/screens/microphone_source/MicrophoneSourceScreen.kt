package com.example.convert2video.ui.screens.microphone_source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.record.MicrophoneSource
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.buttons.SoftIconButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.theme.C2vTheme
import com.example.convert2video.ui.theme.Convert2videoTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicrophoneSourceScreen(
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MicrophoneSourceViewModel = viewModel(factory = MicrophoneSourceViewModel.Factory),
) {
    val microphoneSource by viewModel.microphoneSource.collectAsStateWithLifecycle()
    val isBluetoothConnected by viewModel.isBluetoothConnected.collectAsStateWithLifecycle()

    val titleStr = stringResource(R.string.microphone_source_title)
    val cdOpenDrawerStr = stringResource(R.string.cd_open_drawer)
    val cdNavigateBackStr = stringResource(R.string.cd_navigate_back)

    LaunchedEffect(Unit) {
        viewModel.refreshBluetoothConnected()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleStr,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    RoundedIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = cdNavigateBackStr,
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("navigate_back_button"),
                    )
                },
                actions = {
                    RoundedIconButton(
                        icon = Icons.Filled.Menu,
                        contentDescription = cdOpenDrawerStr,
                        onClick = onOpenDrawer,
                        modifier = Modifier.testTag("open_drawer_button"),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            MicrophoneSourceContent(
                microphoneSource = microphoneSource,
                isBluetoothConnected = isBluetoothConnected,
                onSelectSource = viewModel::setMicrophoneSource,
            )
        }
    }
}

/**
 * 마이크 소스 SegmentedControl (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * [microphoneSource] null이면 DataStore 대기 — SegmentedControl 비활성.
 * ordinal: Default=0, Bluetooth=1 — options 순서와 동기.
 */
@Composable
fun MicrophoneSourceContent(
    microphoneSource: MicrophoneSource?,
    isBluetoothConnected: Boolean,
    onSelectSource: (MicrophoneSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionLabel = stringResource(R.string.microphone_source_title)
    val defaultLabel = stringResource(R.string.microphone_source_default)
    val bluetoothLabel = stringResource(R.string.microphone_source_bluetooth)
    val description = stringResource(R.string.microphone_source_description)
    val connectedHint = stringResource(R.string.microphone_source_connected_hint)
    val notConnectedHint = stringResource(R.string.microphone_source_not_connected_hint)
    val infoCd = stringResource(R.string.cd_microphone_source_info)
    val infoTitle = stringResource(R.string.microphone_source_info_title)
    val infoBody = stringResource(R.string.microphone_source_info_body)
    val confirmLabel = stringResource(R.string.action_confirm)
    val isReady = microphoneSource != null
    var showInfo by rememberSaveable { mutableStateOf(false) }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(infoTitle) },
            text = { Text(infoBody) },
            confirmButton = {
                TextButton(
                    onClick = { showInfo = false },
                    modifier = Modifier.testTag("microphone_source_info_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }

    C2vCard(
        modifier = modifier.testTag("microphone_source_segmented_control"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = sectionLabel,
                    style = MaterialTheme.typography.titleSmall,
                )
                SoftIconButton(
                    icon = Icons.Filled.Info,
                    contentDescription = infoCd,
                    onClick = { showInfo = true },
                    modifier = Modifier.testTag("microphone_source_info_button"),
                )
            }
            SegmentedControl(
                options = listOf(defaultLabel, bluetoothLabel),
                selectedIndex = microphoneSource?.ordinal ?: -1,
                onSelect = { index ->
                    if (!isReady) return@SegmentedControl
                    val source = MicrophoneSource.entries.getOrNull(index) ?: return@SegmentedControl
                    onSelectSource(source)
                },
                enabled = isReady,
                optionTestTags = listOf(
                    "microphone_source_default",
                    "microphone_source_bluetooth",
                ),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("microphone_source_description"),
            )
            Text(
                text = if (isBluetoothConnected) connectedHint else notConnectedHint,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("microphone_source_connection_hint"),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MicrophoneSourceContentPreview() {
    Convert2videoTheme {
        MicrophoneSourceContent(
            microphoneSource = MicrophoneSource.Default,
            isBluetoothConnected = false,
            onSelectSource = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
