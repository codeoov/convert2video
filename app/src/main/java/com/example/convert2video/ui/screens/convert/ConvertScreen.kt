package com.example.convert2video.ui.screens.convert

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.convert2video.R
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.ui.shared.ConversionUiState
import com.example.convert2video.ui.components.badges.AccentGlyphBadge
import com.example.convert2video.ui.components.badges.SectionLabel
import com.example.convert2video.ui.components.buttons.AccentCtaButton
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import com.example.convert2video.ui.components.buttons.SoftChipButton
import com.example.convert2video.ui.components.buttons.SoftIconButton
import com.example.convert2video.ui.components.buttons.TopBarChipButton
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.components.controls.C2vSwitch
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.components.controls.StepperControl
import com.example.convert2video.ui.components.layout.DashedDropZone
import com.example.convert2video.ui.components.layout.GradientThumbnailPlaceholder
import com.example.convert2video.ui.components.layout.SegmentPreviewBar
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme
import com.example.convert2video.utils.AppLogger
import com.example.convert2video.video.VideoSegmentPlanner
import java.io.File
import kotlinx.coroutines.launch

private const val TAG = "ConvertScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onNavigateToBackgroundPick: () -> Unit,
    onNavigateToAudioPick: () -> Unit,
    onNavigateToRecord: () -> Unit,
    onNavigateToConvertedVideos: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    /** 녹음 FGS 활성(Recording/Paused/Stopping) — 화면 이탈 중 안내 hint. */
    isRecordingActive: Boolean = false,
    /**
     * Activity-scoped [ConvertViewModel] — [com.example.convert2video.MainActivity] /
     * [ConvertAssemblyScreen]에서 항상 명시 전달한다.
     * 기본 `viewModel()`은 isolated preview·단독 Compose 테스트 전용.
     */
    viewModel: ConvertViewModel = viewModel(),
) {
    val selectedBackground by viewModel.selectedBackground.collectAsStateWithLifecycle()
    val selectedAudioList by viewModel.selectedAudioList.collectAsStateWithLifecycle()
    val audioSourceTab by viewModel.audioSourceTab.collectAsStateWithLifecycle()
    val conversionState by viewModel.conversionState.collectAsStateWithLifecycle()
    var hasSeenSuccessfulConversion by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(conversionState) {
        if (conversionState is ConversionUiState.Success) {
            hasSeenSuccessfulConversion = true
        }
    }
    val batchItems by viewModel.batchConversionItems.collectAsStateWithLifecycle()
    val isSegmentSplitEnabled by viewModel.isSegmentSplitEnabled.collectAsStateWithLifecycle()
    val segmentPlanMode by viewModel.segmentPlanMode.collectAsStateWithLifecycle()
    val equalSegmentCount by viewModel.equalSegmentCount.collectAsStateWithLifecycle()
    val customSegmentDrafts by viewModel.customSegmentDrafts.collectAsStateWithLifecycle()
    val segmentPlanError by viewModel.segmentPlanError.collectAsStateWithLifecycle()
    val isPreparingConversion by viewModel.isPreparingConversion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val preparingLabel = stringResource(R.string.home_conversion_preparing)
    val recordingActiveHint = stringResource(R.string.home_recording_active_hint)
    val segmentPlanErrorText = segmentPlanErrorMessage(segmentPlanError)
    val playNoAppMessage = stringResource(R.string.recordings_list_play_no_app)
    val playFailedMessage = stringResource(R.string.recordings_list_play_failed)
    val playNoAppMessageState = rememberUpdatedState(playNoAppMessage)
    val playFailedMessageState = rememberUpdatedState(playFailedMessage)

    val onPlayAudio: (ConvertViewModel.SelectedAudio) -> Unit =
        remember(context, scope, snackbarHostState) {
            { audio ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(audio.uri, "audio/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    AppLogger.w(TAG, "No app to play audio: title=${audio.title}", e)
                    scope.launch { snackbarHostState.showSnackbar(playNoAppMessageState.value) }
                } catch (e: SecurityException) {
                    AppLogger.e(TAG, "Play audio permission denied: title=${audio.title}", e)
                    scope.launch { snackbarHostState.showSnackbar(playFailedMessageState.value) }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Play audio failed: title=${audio.title}", e)
                    scope.launch { snackbarHostState.showSnackbar(playFailedMessageState.value) }
                }
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.startConversion()
    }

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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.home_title),
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
                        modifier = Modifier
                            .padding(start = 20.dp)
                            .testTag("navigate_back_button"),
                    )
                },
                actions = {
                    TopBarChipButton(
                        text = stringResource(R.string.drawer_converted_videos),
                        onClick = onNavigateToConvertedVideos,
                    )
                    RoundedIconButton(
                        icon = Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.cd_open_drawer),
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .padding(end = 20.dp)
                            .testTag("open_drawer_button"),
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (isRecordingActive) {
                    ConvertHintRow(
                        text = recordingActiveHint,
                        modifier = Modifier.testTag("home_recording_active_hint"),
                    )
                }
                val listenVisible = selectedAudioList.isNotEmpty()
                val backgroundNumber = if (listenVisible) 2 else 1
                val audioNumber = if (listenVisible) 3 else 2
                ListenSection(
                    selectedAudioList = selectedAudioList,
                    onPlayAudio = onPlayAudio,
                    sectionNumber = 1,
                )
                BackgroundSection(
                    selectedBackground = selectedBackground,
                    onChangeClick = onNavigateToBackgroundPick,
                    sectionNumber = backgroundNumber,
                )
                AudioSection(
                    selectedAudioList = selectedAudioList,
                    selectedTab = audioSourceTab,
                    onSelectTab = viewModel::selectAudioSourceTab,
                    onNavigateToRecord = onNavigateToRecord,
                    onNavigateToAudioPick = onNavigateToAudioPick,
                    sectionNumber = audioNumber,
                )
                SegmentSplitSection(
                    enabled = isSegmentSplitEnabled,
                    planMode = segmentPlanMode,
                    equalCount = equalSegmentCount,
                    customDrafts = customSegmentDrafts,
                    errorMessage = segmentPlanErrorText,
                    onEnabledChange = viewModel::setSegmentSplitEnabled,
                    onPlanModeChange = viewModel::setSegmentPlanMode,
                    onEqualCountChange = viewModel::setEqualSegmentCount,
                    onCustomDraftChange = viewModel::updateCustomSegmentDraft,
                    onAddCustomDraft = viewModel::addCustomSegmentDraft,
                    onRemoveCustomDraft = viewModel::removeCustomSegmentDraft,
                )
            }

            val segmentAudioOk = !isSegmentSplitEnabled || selectedAudioList.size == 1
            val convertEnabled = selectedAudioList.isNotEmpty() &&
                selectedBackground != null &&
                conversionState !is ConversionUiState.InProgress &&
                !isPreparingConversion &&
                segmentAudioOk
            AccentCtaButton(
                text = if (
                    hasSeenSuccessfulConversion || conversionState is ConversionUiState.Success
                ) {
                    stringResource(R.string.home_convert_again_action)
                } else {
                    stringResource(R.string.home_convert_action)
                },
                onClick = onConvertClick,
                enabled = convertEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 28.dp)
                    .testTag("home_convert_button"),
            )
            if (isPreparingConversion) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("home_conversion_preparing"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = preparingLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = C2vTheme.colors.ink3,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .testTag("home_conversion_preparing_label"),
                )
            }
            if (selectedBackground == null || selectedAudioList.isEmpty()) {
                ConvertHintRow(
                    text = when {
                        selectedBackground == null && selectedAudioList.isEmpty() ->
                            stringResource(R.string.home_convert_hint_both)
                        selectedBackground == null ->
                            stringResource(R.string.home_convert_hint_background)
                        else -> stringResource(R.string.home_convert_hint_audio)
                    },
                    modifier = Modifier
                        .padding(bottom = 14.dp)
                        .testTag("home_convert_hint"),
                )
            }
        }
    }

    HomeConversionStateDialog(
        conversionState = conversionState,
        batchItems = batchItems,
        onDismissResult = viewModel::dismissConversionResult,
        onCancelConversion = viewModel::cancelConversion,
        onNavigateToConvertedVideos = onNavigateToConvertedVideos,
    )
}

@Composable
private fun ConvertHintRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.5.dp, C2vTheme.colors.ink3), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "!",
                style = MaterialTheme.typography.labelSmall,
                color = C2vTheme.colors.ink3,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = C2vTheme.colors.ink3,
        )
    }
}

@Composable
private fun ListenSection(
    selectedAudioList: List<ConvertViewModel.SelectedAudio>,
    onPlayAudio: (ConvertViewModel.SelectedAudio) -> Unit,
    sectionNumber: Int,
) {
    if (selectedAudioList.isEmpty()) return
    val sectionLabel = stringResource(R.string.convert_section_listen)
    val cdPlay = stringResource(R.string.cd_recording_play)
    val unknownTitle = stringResource(R.string.audio_item_unknown_title)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(number = sectionNumber, text = sectionLabel)
        C2vCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selectedAudioList.forEachIndexed { index, audio ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = audio.title.ifBlank { unknownTitle },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SoftIconButton(
                            icon = Icons.Filled.PlayArrow,
                            contentDescription = cdPlay,
                            onClick = { onPlayAudio(audio) },
                            modifier = Modifier.testTag("listen_play_button_$index"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundSection(
    selectedBackground: BackgroundImage?,
    onChangeClick: () -> Unit,
    sectionNumber: Int,
) {
    val sectionLabel = stringResource(R.string.home_background_section)
    val backgroundLabel = stringResource(R.string.home_background_label)
    val noneLabel = stringResource(R.string.home_background_none)
    val changeLabel = stringResource(R.string.home_background_change)
    val selectLabel = stringResource(R.string.home_background_select)
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(number = sectionNumber, text = sectionLabel)
        C2vCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GradientThumbnailPlaceholder(modifier = Modifier.size(56.dp)) {
                    if (selectedBackground != null) {
                        AsyncImage(
                            model = File(selectedBackground.filePath),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(C2vRadius.avatar)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = backgroundLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                    Text(
                        text = if (selectedBackground != null) {
                            File(selectedBackground.filePath).name
                        } else {
                            noneLabel
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SoftChipButton(
                    text = if (selectedBackground != null) changeLabel else selectLabel,
                    onClick = onChangeClick,
                    modifier = Modifier.testTag("home_pick_background_button"),
                )
            }
        }
    }
}

/**
 * Home 오디오 섹션 — 빈 상태 SegmentedControl([녹음]/[파일에서 선택]),
 * 채움 시 탭 숨김 + "변경"으로 마지막 [AudioSourceTab] 재진입.
 */
@Composable
internal fun AudioSection(
    selectedAudioList: List<ConvertViewModel.SelectedAudio>,
    selectedTab: ConvertViewModel.AudioSourceTab,
    onSelectTab: (ConvertViewModel.AudioSourceTab) -> Unit,
    onNavigateToRecord: () -> Unit,
    onNavigateToAudioPick: () -> Unit,
    sectionNumber: Int = 3,
) {
    val sectionLabel = stringResource(R.string.home_audio_section_label)
    val recordLabel = stringResource(R.string.home_audio_tab_record)
    val filePickLabel = stringResource(R.string.home_audio_tab_filepick)
    val emptyTitle = stringResource(R.string.home_audio_empty_title)
    val emptyHint = stringResource(R.string.home_audio_empty_hint)
    val changeLabel = stringResource(R.string.home_audio_change)
    val selectedCountLabel = stringResource(
        R.string.home_audio_selected_count,
        selectedAudioList.size,
    )
    // 동일 탭 재탭도 navigate 유지 — Home에서 Record/AudioPick 재진입 허용 (스킵하지 않음).
    val navigateForTab: (ConvertViewModel.AudioSourceTab) -> Unit = { tab ->
        when (tab) {
            ConvertViewModel.AudioSourceTab.Record -> onNavigateToRecord()
            ConvertViewModel.AudioSourceTab.FilePick -> onNavigateToAudioPick()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(number = sectionNumber, text = sectionLabel)
        if (selectedAudioList.isEmpty()) {
            DashedDropZone(modifier = Modifier.fillMaxWidth()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AccentGlyphBadge(glyph = "♪")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = emptyTitle,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = emptyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = C2vTheme.colors.ink3,
                        )
                    }
                    SegmentedControl(
                        options = listOf(recordLabel, filePickLabel),
                        selectedIndex = when (selectedTab) {
                            ConvertViewModel.AudioSourceTab.Record -> 0
                            ConvertViewModel.AudioSourceTab.FilePick -> 1
                        },
                        onSelect = { index ->
                            val tab = if (index == 0) {
                                ConvertViewModel.AudioSourceTab.Record
                            } else {
                                ConvertViewModel.AudioSourceTab.FilePick
                            }
                            onSelectTab(tab)
                            navigateForTab(tab)
                        },
                        optionTestTags = listOf(
                            "home_audio_tab_record",
                            "home_audio_tab_filepick",
                        ),
                    )
                }
            }
        } else {
            C2vCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentGlyphBadge(
                        glyph = "♪",
                        size = 36.dp,
                        shape = RoundedCornerShape(C2vRadius.control),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        when {
                            selectedAudioList.size == 1 -> {
                                val audio = selectedAudioList.first()
                                Text(
                                    text = audio.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (audio.artist != null) {
                                    Text(
                                        text = audio.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = C2vTheme.colors.ink3,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            else -> Text(
                                text = selectedCountLabel,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    SoftChipButton(
                        text = changeLabel,
                        onClick = { navigateForTab(selectedTab) },
                        modifier = Modifier.testTag("home_pick_audio_button"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentSplitSection(
    enabled: Boolean,
    planMode: ConvertViewModel.SegmentPlanMode,
    equalCount: Int,
    customDrafts: List<ConvertViewModel.CustomSegmentDraft>,
    errorMessage: String?,
    onEnabledChange: (Boolean) -> Unit,
    onPlanModeChange: (ConvertViewModel.SegmentPlanMode) -> Unit,
    onEqualCountChange: (Int) -> Unit,
    onCustomDraftChange: (Int, ConvertViewModel.CustomSegmentDraft) -> Unit,
    onAddCustomDraft: () -> Unit,
    onRemoveCustomDraft: (Int) -> Unit,
) {
    val splitLabel = stringResource(R.string.segment_split_toggle)
    val splitDescription = stringResource(R.string.segment_split_description)
    val decreaseCd = stringResource(R.string.cd_segment_equal_decrease)
    val increaseCd = stringResource(R.string.cd_segment_equal_increase)

    C2vCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_segment_split_section"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 14.dp)) {
                    Text(
                        text = splitLabel,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = splitDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                }
                C2vSwitch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .testTag("home_segment_split_switch")
                        .semantics { contentDescription = splitLabel },
                )
            }

            if (enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SegmentedControl(
                        options = listOf(
                            stringResource(R.string.segment_mode_equal),
                            stringResource(R.string.segment_mode_custom),
                        ),
                        selectedIndex = if (planMode == ConvertViewModel.SegmentPlanMode.Equal) 0 else 1,
                        onSelect = { index ->
                            onPlanModeChange(
                                if (index == 0) {
                                    ConvertViewModel.SegmentPlanMode.Equal
                                } else {
                                    ConvertViewModel.SegmentPlanMode.Custom
                                },
                            )
                        },
                        optionTestTags = listOf("home_segment_mode_equal", "home_segment_mode_custom"),
                    )

                    when (planMode) {
                        ConvertViewModel.SegmentPlanMode.Equal -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(R.string.segment_equal_count, equalCount),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("home_segment_equal_count"),
                                    )
                                    StepperControl(
                                        value = equalCount,
                                        onDecrement = { onEqualCountChange(equalCount - 1) },
                                        onIncrement = { onEqualCountChange(equalCount + 1) },
                                        decrementEnabled = equalCount > ConvertViewModel.MIN_EQUAL_SEGMENT_COUNT,
                                        incrementEnabled = equalCount < VideoSegmentPlanner.MAX_SEGMENT_COUNT,
                                        decrementTestTag = "home_segment_equal_decrease",
                                        incrementTestTag = "home_segment_equal_increase",
                                        decrementContentDescription = decreaseCd,
                                        incrementContentDescription = increaseCd,
                                    )
                                }
                                SegmentPreviewBar(count = equalCount)
                            }
                        }
                        ConvertViewModel.SegmentPlanMode.Custom -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                customDrafts.forEachIndexed { index, draft ->
                                    CustomSegmentDraftRow(
                                        index = index,
                                        draft = draft,
                                        canRemove = customDrafts.size > 1,
                                        onDraftChange = { onCustomDraftChange(index, it) },
                                        onRemove = { onRemoveCustomDraft(index) },
                                    )
                                }
                                TextButton(
                                    onClick = onAddCustomDraft,
                                    enabled = customDrafts.size < VideoSegmentPlanner.MAX_SEGMENT_COUNT,
                                    modifier = Modifier.testTag("home_segment_custom_add"),
                                ) {
                                    Text(stringResource(R.string.segment_custom_add))
                                }
                            }
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("home_segment_plan_error"),
                )
            }
        }
    }
}

@Composable
private fun CustomSegmentDraftRow(
    index: Int,
    draft: ConvertViewModel.CustomSegmentDraft,
    canRemove: Boolean,
    onDraftChange: (ConvertViewModel.CustomSegmentDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val fieldShape = RoundedCornerShape(C2vRadius.control)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.segment_custom_index_label, index + 1),
            style = MaterialTheme.typography.labelMedium,
            color = C2vTheme.colors.ink3,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft.startSeconds,
                onValueChange = { onDraftChange(draft.copy(startSeconds = it)) },
                label = { Text(stringResource(R.string.segment_custom_start_label)) },
                singleLine = true,
                shape = fieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = C2vTheme.colors.cardBorder,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_segment_custom_start_$index"),
            )
            OutlinedTextField(
                value = draft.endSeconds,
                onValueChange = { onDraftChange(draft.copy(endSeconds = it)) },
                label = { Text(stringResource(R.string.segment_custom_end_label)) },
                singleLine = true,
                shape = fieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = C2vTheme.colors.cardBorder,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_segment_custom_end_$index"),
            )
            TextButton(
                onClick = onRemove,
                enabled = canRemove,
                modifier = Modifier.testTag("home_segment_custom_remove_$index"),
            ) {
                Text(stringResource(R.string.segment_custom_remove))
            }
        }
    }
}

@Composable
private fun HomeConversionStateDialog(
    conversionState: ConversionUiState,
    batchItems: List<ConvertViewModel.BatchItemState>,
    onDismissResult: () -> Unit,
    onCancelConversion: () -> Unit,
    onNavigateToConvertedVideos: () -> Unit,
) {
    when (conversionState) {
        is ConversionUiState.InProgress -> {
            var showCancelConfirm by remember { mutableStateOf(false) }
            val progressTitle = stringResource(R.string.conversion_progress_title)
            val statusWaiting = stringResource(R.string.conversion_batch_item_waiting)
            val statusSuccess = stringResource(R.string.conversion_batch_item_success)
            val statusFailed = stringResource(R.string.conversion_batch_item_failed)
            val statusCancelled = stringResource(R.string.conversion_batch_item_cancelled)
            val unknownTitle = stringResource(R.string.audio_item_unknown_title)
            AlertDialog(
                modifier = Modifier.testTag("conversion_progress_dialog"),
                onDismissRequest = {},
                title = { Text(progressTitle) },
                text = {
                    Column {
                        if (batchItems.size > 1) {
                            val completedCount = batchItems.count { it.state is ConversionUiState.Success }
                            Text(
                                text = stringResource(
                                    R.string.conversion_batch_progress_summary,
                                    batchItems.size,
                                    completedCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            batchItems.forEach { item ->
                                val segmentLabel = if (
                                    item.segmentIndex != null && item.segmentTotal != null
                                ) {
                                    stringResource(
                                        R.string.segment_batch_index_label,
                                        item.segmentIndex,
                                        item.segmentTotal,
                                    )
                                } else {
                                    null
                                }
                                val audioTitle = item.audio.title.ifBlank { unknownTitle }
                                val rowTitle = if (segmentLabel != null) {
                                    "$segmentLabel · $audioTitle"
                                } else {
                                    audioTitle
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = rowTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = when (val s = item.state) {
                                            is ConversionUiState.InProgress ->
                                                stringResource(
                                                    R.string.conversion_batch_item_progress,
                                                    s.percent,
                                                )
                                            is ConversionUiState.Success -> statusSuccess
                                            is ConversionUiState.Failed -> statusFailed
                                            ConversionUiState.Cancelled -> statusCancelled
                                            ConversionUiState.Idle -> statusWaiting
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = { conversionState.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (batchItems.size > 1) 8.dp else 0.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        if (batchItems.size <= 1) {
                            Text(
                                stringResource(
                                    R.string.conversion_batch_item_progress,
                                    conversionState.percent,
                                ),
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { showCancelConfirm = true },
                        modifier = Modifier.testTag("conversion_cancel_button"),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
            if (showCancelConfirm) {
                AlertDialog(
                    modifier = Modifier.testTag("conversion_cancel_confirm_dialog"),
                    onDismissRequest = { showCancelConfirm = false },
                    title = { Text(stringResource(R.string.conversion_cancel_confirm_title)) },
                    text = { Text(stringResource(R.string.conversion_cancel_confirm_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showCancelConfirm = false
                                onCancelConversion()
                            },
                            modifier = Modifier.testTag("conversion_cancel_confirm_button"),
                        ) {
                            Text(stringResource(R.string.conversion_cancel_confirm_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCancelConfirm = false }) {
                            Text(stringResource(R.string.conversion_cancel_continue))
                        }
                    },
                )
            }
        }
        is ConversionUiState.Success -> {
            val successText = if (batchItems.size > 1) {
                stringResource(R.string.conversion_success_body_batch, batchItems.size)
            } else {
                stringResource(R.string.conversion_success_body)
            }
            AlertDialog(
                modifier = Modifier.testTag("conversion_success_dialog"),
                onDismissRequest = onDismissResult,
                title = { Text(stringResource(R.string.conversion_success_title)) },
                text = { Text(successText) },
                confirmButton = {
                    TextButton(onClick = onDismissResult) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onDismissResult()
                        onNavigateToConvertedVideos()
                    }) { Text(stringResource(R.string.conversion_success_view_list)) }
                },
            )
        }
        is ConversionUiState.Failed -> {
            AlertDialog(
                modifier = Modifier.testTag("conversion_failed_dialog"),
                onDismissRequest = onDismissResult,
                title = { Text(stringResource(R.string.conversion_failed_title)) },
                text = { Text(conversionState.message) },
                confirmButton = {
                    TextButton(onClick = onDismissResult) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
            )
        }
        ConversionUiState.Cancelled -> {
            AlertDialog(
                modifier = Modifier.testTag("conversion_cancelled_dialog"),
                onDismissRequest = onDismissResult,
                title = { Text(stringResource(R.string.conversion_cancelled_title)) },
                text = { Text(stringResource(R.string.conversion_cancelled_body)) },
                confirmButton = {
                    TextButton(onClick = onDismissResult) {
                        Text(stringResource(R.string.action_confirm))
                    }
                },
            )
        }
        ConversionUiState.Idle -> Unit
    }
}

@Composable
private fun segmentPlanErrorMessage(error: ConvertViewModel.SegmentPlanError?): String? {
    return when (error) {
        null -> null
        ConvertViewModel.SegmentPlanError.SingleAudioRequired ->
            stringResource(R.string.segment_error_single_audio_required)
        ConvertViewModel.SegmentPlanError.DurationUnreadable ->
            stringResource(R.string.segment_error_duration_unreadable)
        ConvertViewModel.SegmentPlanError.PlanInvalid ->
            stringResource(R.string.segment_error_plan_invalid)
        ConvertViewModel.SegmentPlanError.CustomParse ->
            stringResource(R.string.segment_error_custom_parse)
        ConvertViewModel.SegmentPlanError.Unexpected ->
            stringResource(R.string.segment_error_unexpected)
    }
}
