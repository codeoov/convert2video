package com.example.convert2video.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

@Composable
fun BackgroundLibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: BackgroundLibraryViewModel = viewModel(),
) {
    val backgrounds by viewModel.backgrounds.collectAsStateWithLifecycle()
    val conversionState by viewModel.conversionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectedBackground = backgrounds.find { it.isSelected }

    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::addBackground) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val background = selectedBackground
        if (uri != null && background != null) {
            viewModel.startConversion(background.filePath, uri)
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) audioPickerLauncher.launch("audio/*") }

    fun launchAudioPickerRespectingLegacyStorage() {
        val needsLegacyStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsLegacyStoragePermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            audioPickerLauncher.launch("audio/*")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // AC-7: proceed regardless of whether the user granted it.
        launchAudioPickerRespectingLegacyStorage()
    }

    BackgroundLibraryContent(
        backgrounds = backgrounds,
        conversionState = conversionState,
        onAddClick = {
            backgroundPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onSelect = viewModel::selectBackground,
        onDelete = viewModel::deleteBackground,
        onConvertClick = {
            val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsNotificationPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                launchAudioPickerRespectingLegacyStorage()
            }
        },
        onDismissResult = viewModel::dismissConversionResult,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BackgroundLibraryContent(
    backgrounds: List<BackgroundImage>,
    conversionState: ConversionUiState,
    onAddClick: () -> Unit,
    onSelect: (Long) -> Unit,
    onDelete: (BackgroundImage) -> Unit,
    onConvertClick: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<BackgroundImage?>(null) }
    val hasSelectedBackground = backgrounds.any { it.isSelected }
    val convertEnabled = hasSelectedBackground && conversionState !is ConversionUiState.InProgress

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier.testTag("add_background_button"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "배경화면 추가")
            }
        },
        bottomBar = {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onConvertClick,
                    enabled = convertEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("convert_button"),
                ) {
                    Text("변환하기")
                }
                if (!hasSelectedBackground) {
                    Text(
                        text = if (backgrounds.isEmpty()) {
                            "배경화면을 먼저 추가해주세요"
                        } else {
                            "배경화면을 선택해주세요"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("convert_hint"),
                    )
                }
            }
        },
    ) { innerPadding ->
        if (backgrounds.isEmpty()) {
            EmptyLibrary(modifier = Modifier.padding(innerPadding).fillMaxSize())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .testTag("background_grid"),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(backgrounds, key = { it.id }) { background ->
                    BackgroundThumbnail(
                        background = background,
                        onClick = { onSelect(background.id) },
                        onLongPress = { pendingDelete = background },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            modifier = Modifier.testTag("delete_dialog"),
            onDismissRequest = { pendingDelete = null },
            title = { Text("배경화면 삭제") },
            text = { Text("이 배경화면을 라이브러리에서 삭제할까요?") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("delete_confirm_button"),
                    onClick = {
                        onDelete(target)
                        pendingDelete = null
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }

    ConversionStateDialog(conversionState = conversionState, onDismissResult = onDismissResult)
}

@Composable
private fun ConversionStateDialog(
    conversionState: ConversionUiState,
    onDismissResult: () -> Unit,
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
                text = { Text("갤러리(Movies)에 저장했습니다. 유튜브 업로드에 사용할 수 있습니다.") },
                confirmButton = { TextButton(onClick = onDismissResult) { Text("확인") } },
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

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag("empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("저장된 배경화면이 없습니다")
        Text(
            "오른쪽 아래 + 버튼으로 배경화면을 추가하세요",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackgroundThumbnail(
    background: BackgroundImage,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (background.isSelected) 3.dp else 0.dp,
                color = if (background.isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .testTag("background_thumbnail_${background.id}"),
    ) {
        AsyncImage(
            model = File(background.filePath),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
