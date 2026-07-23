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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::addBackground) }

    BackgroundLibraryContent(
        backgrounds = backgrounds,
        onAddClick = {
            pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onSelect = viewModel::selectBackground,
        onDelete = viewModel::deleteBackground,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BackgroundLibraryContent(
    backgrounds: List<BackgroundImage>,
    onAddClick: () -> Unit,
    onSelect: (Long) -> Unit,
    onDelete: (BackgroundImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<BackgroundImage?>(null) }

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
