package com.example.convert2video.ui.screens.background_pick

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.convert2video.R
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.buttons.SoftChipButton
import com.example.convert2video.ui.components.layout.DashedDropZone
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme
import java.io.File

@Composable
fun BackgroundPickScreen(
    onNavigateBack: () -> Unit = {},
    onOpenDrawer: () -> Unit,
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
        onOpenDrawer = onOpenDrawer,
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
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ID만 저장해 화면 회전 후에도 삭제 확인 다이얼로그 유지
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val pendingDelete = backgrounds.find { it.id == pendingDeleteId }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.background_pick_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                navigationIcon = {
                    RoundedIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back),
                        onClick = onNavigateBack,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                },
                actions = {
                    RoundedIconButton(
                        icon = Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.cd_open_drawer),
                        onClick = onOpenDrawer,
                        enabled = pendingDeleteId == null,
                        modifier = Modifier
                            .padding(end = 20.dp)
                            .testTag("open_drawer_button"),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                modifier = Modifier.testTag("add_background_button"),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_add_background),
                )
            }
        },
    ) { innerPadding ->
        if (backgrounds.isEmpty()) {
            EmptyBackgroundPick(
                onAddClick = onAddClick,
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(20.dp)
                    .fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .testTag("background_grid"),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
            title = { Text(stringResource(R.string.background_delete_title)) },
            text = { Text(stringResource(R.string.background_delete_confirm)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("delete_confirm_button"),
                    onClick = {
                        onDelete(target)
                        pendingDeleteId = null
                    },
                ) { Text(stringResource(R.string.converted_videos_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyBackgroundPick(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("empty_state"), contentAlignment = Alignment.Center) {
        DashedDropZone {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(C2vRadius.control + 4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.background_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.background_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                }
                SoftChipButton(
                    text = stringResource(R.string.background_add_image),
                    onClick = onAddClick,
                )
            }
        }
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
            .clip(RoundedCornerShape(C2vRadius.innerCard))
            .border(
                width = if (background.isSelected) 3.dp else 1.dp,
                color = if (background.isSelected) MaterialTheme.colorScheme.primary else C2vTheme.colors.cardBorder,
                shape = RoundedCornerShape(C2vRadius.innerCard),
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
