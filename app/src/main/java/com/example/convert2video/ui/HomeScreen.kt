package com.example.convert2video.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.convert2video.data.BackgroundImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToBackgroundPick: () -> Unit,
    onNavigateToAudioPick: () -> Unit,
    onNavigateToConvertedVideos: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val selectedBackground by viewModel.selectedBackground.collectAsStateWithLifecycle()
    val selectedAudio by viewModel.selectedAudio.collectAsStateWithLifecycle()
    val conversionState by viewModel.conversionState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // AC-7: proceed regardless of whether the user granted POST_NOTIFICATIONS.
        viewModel.startConversion()
    }

    // remember로 메모화 — 리컴포지션마다 새 람다 인스턴스 생성 방지
    val onConvertClick: () -> Unit = remember(context, notificationPermissionLauncher, viewModel) {
        {
            val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startConversion()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("변환") },
                actions = {
                    TextButton(onClick = onNavigateToConvertedVideos) {
                        Text("변환 결과")
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val convertEnabled = selectedBackground != null &&
                    selectedAudio != null &&
                    conversionState !is ConversionUiState.InProgress
                Button(
                    onClick = onConvertClick,
                    enabled = convertEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_convert_button"),
                ) {
                    Text("변환하기")
                }
                if (selectedBackground == null || selectedAudio == null) {
                    Text(
                        text = when {
                            selectedBackground == null && selectedAudio == null ->
                                "배경화면과 오디오를 선택해주세요"
                            selectedBackground == null -> "배경화면을 선택해주세요"
                            else -> "오디오를 선택해주세요"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("home_convert_hint"),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackgroundSection(
                selectedBackground = selectedBackground,
                onChangeClick = onNavigateToBackgroundPick,
            )
            AudioSection(
                selectedAudio = selectedAudio,
                onPickClick = onNavigateToAudioPick,
            )
        }
    }

    HomeConversionStateDialog(
        conversionState = conversionState,
        onDismissResult = viewModel::dismissConversionResult,
        onNavigateToConvertedVideos = onNavigateToConvertedVideos,
    )
}

@Composable
private fun BackgroundSection(
    selectedBackground: BackgroundImage?,
    onChangeClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selectedBackground != null) {
                    AsyncImage(
                        model = File(selectedBackground.filePath),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = "없음",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "배경화면",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (selectedBackground != null) {
                        File(selectedBackground.filePath).name
                    } else {
                        "선택된 배경화면 없음"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = onChangeClick,
                modifier = Modifier.testTag("home_pick_background_button"),
            ) {
                Text(if (selectedBackground != null) "변경" else "선택")
            }
        }
    }
}

@Composable
private fun AudioSection(
    selectedAudio: HomeViewModel.SelectedAudio?,
    onPickClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "오디오",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedAudio?.title ?: "선택된 오디오 없음",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                if (selectedAudio?.artist != null) {
                    Text(
                        text = selectedAudio.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            TextButton(
                onClick = onPickClick,
                modifier = Modifier.testTag("home_pick_audio_button"),
            ) {
                Text(if (selectedAudio != null) "변경" else "선택")
            }
        }
    }
}

@Composable
private fun HomeConversionStateDialog(
    conversionState: ConversionUiState,
    onDismissResult: () -> Unit,
    onNavigateToConvertedVideos: () -> Unit,
) {
    when (conversionState) {
        is ConversionUiState.InProgress -> {
            AlertDialog(
                modifier = Modifier.testTag("conversion_progress_dialog"),
                onDismissRequest = {},
                title = { Text("변환 중") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { conversionState.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${conversionState.percent}%")
                    }
                },
                confirmButton = {},
            )
        }
        is ConversionUiState.Success -> {
            AlertDialog(
                modifier = Modifier.testTag("conversion_success_dialog"),
                onDismissRequest = onDismissResult,
                title = { Text("변환 완료") },
                text = { Text("갤러리에 저장했습니다. 유튜브 업로드에 사용할 수 있습니다.") },
                confirmButton = { TextButton(onClick = onDismissResult) { Text("확인") } },
                dismissButton = {
                    TextButton(onClick = {
                        onDismissResult()
                        onNavigateToConvertedVideos()
                    }) { Text("목록 보기") }
                },
            )
        }
        is ConversionUiState.Failed -> {
            AlertDialog(
                modifier = Modifier.testTag("conversion_failed_dialog"),
                onDismissRequest = onDismissResult,
                title = { Text("변환 실패") },
                text = { Text(conversionState.message) },
                confirmButton = { TextButton(onClick = onDismissResult) { Text("확인") } },
            )
        }
        ConversionUiState.Cancelled -> {
            AlertDialog(
                modifier = Modifier.testTag("conversion_cancelled_dialog"),
                onDismissRequest = onDismissResult,
                title = { Text("변환 취소됨") },
                text = { Text("변환이 취소되었습니다. 다시 시도해주세요.") },
                confirmButton = { TextButton(onClick = onDismissResult) { Text("확인") } },
            )
        }
        ConversionUiState.Idle -> Unit
    }
}
