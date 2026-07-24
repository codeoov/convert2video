package com.example.convert2video.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.convert2video.data.BackgroundImage
import java.io.File

@Composable
fun BackgroundPickScreen(
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BackgroundPickViewModel = viewModel(),
) {
    val backgrounds by viewModel.backgrounds.collectAsStateWithLifecycle()

    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::addBackground) }

    BackgroundPickContent(
        backgrounds = backgrounds,
        onAddClick = {
            backgroundPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onSelect = { id ->
            // Contract: 탭=선택 후 홈 복귀
            viewModel.selectBackground(id)
            onNavigateBack()
        },
        onDelete = viewModel::deleteBackground,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BackgroundPickContent(
    backgrounds: List<BackgroundImage>,
    onAddClick: () -> Unit,
    onSelect: (Long) -> Unit,
    onDelete: (BackgroundImage) -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ID만 저장해 화면 회전 후에도 삭제 확인 다이얼로그 유지
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pendingDelete = backgrounds.find { it.id == pendingDeleteId }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("배경 선택") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier.testTag("add_background_button"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "배경화면 추가")
            }
        },
    ) { innerPadding ->
        if (backgrounds.isEmpty()) {
            EmptyBackgroundPick(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            )
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
                        onLongPress = { pendingDeleteId = background.id },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            modifier = Modifier.testTag("delete_dialog"),
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("배경화면 삭제") },
            text = { Text("이 배경화면을 삭제할까요?") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("delete_confirm_button"),
                    onClick = {
                        onDelete(target)
                        pendingDeleteId = null
                    },
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun EmptyBackgroundPick(modifier: Modifier = Modifier) {
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
