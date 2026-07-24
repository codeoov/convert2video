package com.example.convert2video.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.data.ConvertedVideo

private val FORBIDDEN_NAME_CHARS = Regex("""[/\\:*?"<>|]""")
private const val MAX_DISPLAY_NAME_LENGTH = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertedVideosScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConvertedVideosViewModel = viewModel(factory = ConvertedVideosViewModel.Factory),
) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingDelete by remember { mutableStateOf<ConvertedVideo?>(null) }
    var pendingRename by remember { mutableStateOf<ConvertedVideo?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // NG4: SharedFlow 수집 — 단발 이벤트이므로 key=Unit으로 구독 유지
    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // NG5: 화면 진입 시 목록 새로고침 (Boolean 라우팅에서 화면 재조합 시마다 실행)
    LaunchedEffect(Unit) {
        viewModel.loadVideos()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("변환 결과") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                videos.isEmpty() -> {
                    Text(
                        text = "변환된 영상이 없습니다",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(videos, key = { it.uri.toString() }) { video ->
                            ConvertedVideoItem(
                                video = video,
                                onPlayClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(video.uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                },
                                onDeleteClick = { pendingDelete = video },
                                onRenameClick = {
                                    renameInput = video.displayName.removeSuffix(".mp4")
                                    pendingRename = video
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("영상 삭제") },
            text = { Text("이 영상을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVideo(target.uri)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }

    pendingRename?.let { target ->
        val isValidName = renameInput.isNotBlank()
            && !renameInput.contains(FORBIDDEN_NAME_CHARS)
            && renameInput.length <= MAX_DISPLAY_NAME_LENGTH
        val hasInvalidChars = renameInput.contains(FORBIDDEN_NAME_CHARS)

        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("이름 변경") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("새 이름") },
                    suffix = { Text(".mp4") },
                    singleLine = true,
                    isError = renameInput.isNotEmpty() && !isValidName,
                    supportingText = when {
                        hasInvalidChars -> { { Text("""사용 불가 문자: / \ : * ? " < > |""") } }
                        renameInput.length > MAX_DISPLAY_NAME_LENGTH -> {
                            { Text("이름이 너무 깁니다 (최대 ${MAX_DISPLAY_NAME_LENGTH}자)") }
                        }
                        else -> null
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = isValidName,
                    onClick = {
                        viewModel.renameVideo(target.uri, renameInput)
                        pendingRename = null
                    },
                ) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ConvertedVideoItem(
    video: ConvertedVideo,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRenameClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // NG6: 긴 파일명 말줄임 처리
            Text(
                text = video.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPlayClick) {
                Icon(Icons.Default.PlayArrow, contentDescription = "재생")
            }
            IconButton(onClick = onRenameClick) {
                Icon(Icons.Default.Edit, contentDescription = "이름 변경")
            }
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = "삭제")
            }
        }
    }
}
