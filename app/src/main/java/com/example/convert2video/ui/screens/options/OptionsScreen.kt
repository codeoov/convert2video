package com.example.convert2video.ui.screens.options

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.NoiseSuppressor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.key
import kotlinx.coroutines.flow.merge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.R
import com.example.convert2video.data.LanguageOption
import com.example.convert2video.data.PendingCountdown
import com.example.convert2video.data.ThemeMode
import com.example.convert2video.data.YoutubeDefaultVisibility
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.drive.DriveStorageQuota
import com.example.convert2video.drive.formatDriveStorageBytes
import com.example.convert2video.findActivityOrNull
import com.example.convert2video.record.ExactAlarmPermission
import com.example.convert2video.record.NoiseReductionMode
import com.example.convert2video.record.RecordingBackupFolder
import com.example.convert2video.store.StoreCapabilities
import com.example.convert2video.record.RecordingFormat
import com.example.convert2video.record.RecordingScheduleRepeatMode
import com.example.convert2video.ui.components.buttons.RoundedIconButton
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import com.example.convert2video.ui.components.buttons.SoftChipButton
import com.example.convert2video.ui.components.buttons.SoftIconButton
import com.example.convert2video.ui.components.pickers.RecordingScheduleEditDialog
import com.example.convert2video.ui.components.pickers.formatScheduleCardSummary
import com.example.convert2video.ui.components.pickers.formatScheduleRepeatSummary
import com.example.convert2video.ui.components.pickers.formatScheduleTimeRange
import com.example.convert2video.ui.components.pickers.parseRepeatModeForDisplay
import com.example.convert2video.ui.components.cards.C2vCard
import com.example.convert2video.ui.components.controls.C2vSwitch
import com.example.convert2video.ui.components.controls.SegmentedControl
import com.example.convert2video.ui.components.controls.StepperControl
import com.example.convert2video.record.COUNTDOWN_MINUTES_MAX
import com.example.convert2video.record.COUNTDOWN_MINUTES_MIN
import com.example.convert2video.record.countdownElapsedRecordingMinutes
import com.example.convert2video.record.countdownRemainingStartMinutes
import com.example.convert2video.record.isCountdownStartEnabled
import com.example.convert2video.record.shouldShowCountdownWaitingSoon
import com.example.convert2video.desktopsync.ServerState
import com.example.convert2video.ui.theme.C2vTheme
import com.example.convert2video.utils.AppLogger
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val TAG_OPTIONS_SCREEN = "OptionsScreen"
private const val GOOGLE_ACCOUNT_TYPE = "com.google"
private const val TEST_TAG_CONTACT_US_EMAIL = "contact_us_email_button"

/** Google 계정 선택기 Intent — Options YouTube/Drive Login 공용 (GET_ACCOUNTS 불필요). */
@Suppress("DEPRECATION")
private fun newGoogleAccountPickerIntent() = AccountManager.newChooseAccountIntent(
    null,
    null,
    arrayOf(GOOGLE_ACCOUNT_TYPE),
    null,
    null,
    null,
    null,
)

/**
 * Hard-restart Intent: copy [source] before flags. Never mutate launchIntent / activity.intent.
 * Fallback is a newly assembled MAIN/LAUNCHER Intent (no `?: activity.intent`).
 */
private fun newRestartIntent(activity: Activity): Intent {
    val source = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
    if (source != null) {
        return Intent(source).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
    }
    return Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = activity.componentName
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/**
 * File-level private hard restart.
 * [startActivity] success → [Activity.finishAffinity] (swallow) → [Runtime.exit].
 * Returns false when startActivity throws so the caller can rollback
 * (never finish/exit on the startActivity failure path).
 */
private fun restartApp(activity: Activity): Boolean {
    val restartIntent = newRestartIntent(activity)
    try {
        activity.startActivity(restartIntent)
    } catch (e: Exception) {
        AppLogger.e(TAG_OPTIONS_SCREEN, "restartApp startActivity failed", e)
        return false
    }
    try {
        activity.finishAffinity()
    } catch (_: Exception) {
        // swallow — still exit so the process does not linger after a launched restart
    }
    Runtime.getRuntime().exit(0)
    return true
}

/**
 * Snackbar는 스크롤 위치와 무관하게 Scaffold 최하단에 고정 표시된다.
 * 스크롤 최하단에서 마지막 콘텐츠가 Snackbar에 가려지지 않도록 추가 여백을 둔다.
 *
 * 산출 근거:
 *   - Material3 2줄 Snackbar 최대 높이: ~68dp (접근성 큰글씨 모드 대응, 보수적 추정)
 *   - 시각적 여유: 8dp  →  목표 합계: 76dp
 *   - Column verticalArrangement spacedBy(22dp)가 Spacer 바로 앞 아이템과의 사이에
 *     22dp를 추가하므로, Spacer 자체 값은 76 - 22 = 54dp
 *
 * WindowInsets·조건부 렌더링 미적용 이유:
 *   Scaffold innerPadding이 기본 시스템 인셋을 이미 처리하고,
 *   이 화면의 다른 모든 섹션이 동일한 정적 여백 패턴을 사용하는 상황에서
 *   이 Spacer 하나에만 동적 WindowInsets 처리를 추가하면 파일 내 일관성이
 *   깨지고 불필요한 복잡도가 생기므로 정적 상수로 충분하다.
 */
private val snackbarBottomClearance = 54.dp

internal fun shouldShowYouTubeOptions(capabilities: StoreCapabilities): Boolean =
    capabilities.supportsYouTube

internal fun shouldShowDriveOptions(capabilities: StoreCapabilities): Boolean =
    capabilities.supportsDrive

internal fun shouldShowBillingOptions(capabilities: StoreCapabilities): Boolean =
    capabilities.supportsBilling

internal fun shouldAcceptProBillingClick(isLanguageApplying: Boolean): Boolean =
    !isLanguageApplying

/**
 * Upgrade 클릭에서만 Activity를 넘긴다. Application·finishing·destroyed는 null.
 * [OptionsViewModel.purchasePro]는 호출하지 않으며, 신규 Snackbar 문자열도 쓰지 않는다.
 */
internal fun purchaseProActivityOrNull(context: Context): Activity? {
    return try {
        val activity = context.findActivityOrNull()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            null
        } else {
            activity
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppLogger.e(TAG_OPTIONS_SCREEN, error.javaClass.simpleName)
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OptionsViewModel = viewModel(),
    scheduleViewModel: RecordingScheduleViewModel = viewModel(),
    countdownViewModel: RecordingCountdownViewModel = viewModel(),
) {
    val capabilities = StoreCapabilities.current
    val wifiOnlyUpload by viewModel.wifiOnlyUpload.collectAsStateWithLifecycle()
    val youtubeAutoUploadEnabled by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.youtubeAutoUploadEnabled?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf<Boolean?>(null) }
    } else {
        remember { mutableStateOf<Boolean?>(null) }
    }
    val youtubeDefaultVisibility by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.youtubeDefaultVisibility?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf<YoutubeDefaultVisibility?>(null) }
    } else {
        remember { mutableStateOf<YoutubeDefaultVisibility?>(null) }
    }
    val isAuthorized by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.isAuthorized.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val channelTitle by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.channelTitle.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<String?>(null) }
    }
    val youtubeAccountEmail by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.youtubeAccountEmail.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<String?>(null) }
    }
    val isChannelTitleLoading by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.isChannelTitleLoading.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val channelTitleFetchFailed by if (shouldShowYouTubeOptions(capabilities)) {
        viewModel.channelTitleFetchFailed.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val isDriveAuthorized by if (shouldShowDriveOptions(capabilities)) {
        viewModel.isDriveAuthorized.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val driveAccountEmail by if (shouldShowDriveOptions(capabilities)) {
        viewModel.driveAccountEmail.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<String?>(null) }
    }
    val driveStorageQuota by if (shouldShowDriveOptions(capabilities)) {
        viewModel.driveStorageQuota?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf<DriveStorageQuota?>(null) }
    } else {
        remember { mutableStateOf<DriveStorageQuota?>(null) }
    }
    val driveAutoUploadEnabled by if (shouldShowDriveOptions(capabilities)) {
        viewModel.driveAutoUploadEnabled?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf<Boolean?>(null) }
    } else {
        remember { mutableStateOf<Boolean?>(null) }
    }
    val isDriveAuthInFlight by if (shouldShowDriveOptions(capabilities)) {
        viewModel.isDriveAuthInFlight.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(false) }
    }
    val proStatus by if (shouldShowBillingOptions(capabilities)) {
        viewModel.proStatus.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<Boolean?>(null) }
    }
    val billingPurchaseState by if (shouldShowBillingOptions(capabilities)) {
        viewModel.billingPurchaseState.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf<BillingPurchaseUiState>(BillingPurchaseUiState.Idle) }
    }
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val languageOption by viewModel.languageOption.collectAsStateWithLifecycle()
    val isLanguageApplying by viewModel.isLanguageApplying.collectAsStateWithLifecycle()
    val recordingFormat by viewModel.recordingFormat.collectAsStateWithLifecycle()
    val noiseReductionMode by viewModel.noiseReductionMode.collectAsStateWithLifecycle()
    val recordingBackupFolderState by
        viewModel.recordingBackupFolderState.collectAsStateWithLifecycle()
    val isRecordingBackupReconciliationInFlight by
        viewModel.isRecordingBackupReconciliationInFlight.collectAsStateWithLifecycle()
    val driveFolderNameOverride by if (shouldShowDriveOptions(capabilities)) {
        viewModel.driveFolderNameOverride?.collectAsStateWithLifecycle()
            ?: remember { mutableStateOf<String?>(null) }
    } else {
        remember { mutableStateOf<String?>(null) }
    }
    val schedules by scheduleViewModel.schedules.collectAsStateWithLifecycle()
    val isScheduleSaving by scheduleViewModel.isSaving.collectAsStateWithLifecycle()
    val countdownStartIn by countdownViewModel.startInMinutes.collectAsStateWithLifecycle()
    val countdownDuration by countdownViewModel.durationMinutes.collectAsStateWithLifecycle()
    val pendingCountdown by countdownViewModel.pendingCountdown.collectAsStateWithLifecycle()
    val isCountdownInFlight by countdownViewModel.isInFlight.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingSchedule by remember { mutableStateOf<RecordingSchedule?>(null) }
    var isAddingSchedule by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var isEditingDriveFolderName by rememberSaveable { mutableStateOf(false) }
    var driveFolderNameInput by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    // banner_only: 거부 시 배너만 — CRUD 게이트 없음
    var needsExactAlarmPermission by remember {
        mutableStateOf(!ExactAlarmPermission.canScheduleExactAlarms(context))
    }
    DisposableEffect(lifecycleOwner) {
        fun syncExactAlarmBanner() {
            needsExactAlarmPermission =
                !ExactAlarmPermission.canScheduleExactAlarms(context)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncExactAlarmBanner()
                viewModel.refreshRecordingBackupFolderState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // MainActivity와 대칭: 이미 RESUMED면 즉시 can 동기화
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            syncExactAlarmBanner()
            viewModel.refreshRecordingBackupFolderState()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openDrawerCd = stringResource(R.string.cd_open_drawer)
    val optionsTitle = stringResource(R.string.options_title)
    val screenSection = stringResource(R.string.options_screen_section)
    val themeSection = stringResource(R.string.options_theme_section)
    val languageSection = stringResource(R.string.options_language_section)
    val recordingSection = stringResource(R.string.options_recording_section)
    val recordingBackupSection = stringResource(R.string.options_recording_backup_section)
    val scheduleSection = stringResource(R.string.options_recording_schedule_section)
    val countdownSection = stringResource(R.string.options_recording_countdown_section)
    val exactAlarmBannerText =
        stringResource(R.string.options_recording_schedule_exact_alarm_banner)
    val exactAlarmCtaText =
        stringResource(R.string.options_recording_schedule_exact_alarm_cta)
    val exactAlarmCtaCd =
        stringResource(R.string.cd_options_recording_schedule_exact_alarm_cta)
    val exactAlarmOpenFailed =
        stringResource(R.string.options_recording_schedule_exact_alarm_open_failed)
    val youtubeSection = stringResource(R.string.options_youtube_account_section)
    val proSection = stringResource(R.string.options_pro_title)
    val driveSection = stringResource(R.string.options_google_drive_section)
    val driveFolderNameFallback = stringResource(R.string.app_name)
    val driveFolderDialogTitle = stringResource(R.string.options_drive_folder_name_dialog_title)
    val driveFolderFieldLabel = stringResource(R.string.options_drive_folder_name_field_label)
    val driveFolderBlankError = stringResource(R.string.options_drive_folder_name_blank_error)
    val driveFolderConfirmLabel = stringResource(R.string.action_confirm)
    val driveFolderCancelLabel = stringResource(R.string.action_cancel)
    val uploadSection = stringResource(R.string.options_upload_section)
    val contactUsSection = stringResource(R.string.options_contact_us_section)
    val contactUsChooserTitle = stringResource(R.string.options_contact_us_chooser_title)
    val contactUsNoEmailApp = stringResource(R.string.options_contact_us_no_email_app)
    val contactUsLaunchFailed = stringResource(R.string.options_contact_us_launch_failed)
    val recordingBackupFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val selectedTreeUri = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data
        } else {
            null
        }
        viewModel.onRecordingBackupFolderSelected(selectedTreeUri)
    }
    val launchRecordingBackupFolderPicker = {
        recordingBackupFolderPickerLauncher.launch(
            RecordingBackupFolder.buildOpenDocumentTreeIntent(),
        )
    }
    LaunchedEffect(Unit) {
        if (capabilities.supportsYouTube) viewModel.refreshAuthState()
        if (capabilities.supportsDrive) {
            viewModel.refreshDriveAuthState()
            viewModel.refreshDriveStorageQuota()
        }
        viewModel.refreshRecordingBackupFolderState()
    }

    // Options + Schedule + Countdown userMessage를 단일 collector로 Snackbar 직렬화
    LaunchedEffect(Unit) {
        merge(
            viewModel.userMessage,
            scheduleViewModel.userMessage,
            countdownViewModel.userMessage,
        ).collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Room insert/update 성공 시에만 EditDialog dismiss
    LaunchedEffect(Unit) {
        scheduleViewModel.writeSucceeded.collect {
            isAddingSchedule = false
            editingSchedule = null
        }
    }

    if (shouldShowBillingOptions(capabilities)) {
        val billingResolutionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            // ActivityResult has no request identity. The binding is a single-operation quarantine,
            // so this callback can only consume the request whose token is still bound.
            viewModel.acceptBillingCallback(result.resultCode, result.data)
        }
        // The binding is ViewModel-owned so rotation cannot rebind a late A callback to B. A
        // reset is permitted only when no provider ActivityResult is still active.
        DisposableEffect(lifecycleOwner) {
            viewModel.resetBillingCallbackForLifecycleRecreation()
            onDispose { }
        }
        LaunchedEffect(capabilities.supportsBilling) {
            viewModel.billingResolutionRequests.collect { request ->
                val operationId = request.operationId
                currentCoroutineContext().ensureActive()
                val captured = viewModel.captureBillingCallback(operationId) ?: return@collect
                if (!viewModel.claimBillingResolutionLaunch(operationId)) {
                    viewModel.releaseBillingCallback(captured)
                    return@collect
                }
                var providerHandoffCompleted = false
                try {
                    currentCoroutineContext().ensureActive()
                    // Validation and launcher invocation share the binding monitor. An abort either
                    // invalidates before this handoff (and launch is skipped), or after Android has
                    // received the request; there is no check-to-launch gap for a stale generation.
                    providerHandoffCompleted = viewModel.handoffBillingCallbackIfCurrent(captured) {
                        billingResolutionLauncher.launch(request.request)
                    }
                } catch (error: CancellationException) {
                    if (!providerHandoffCompleted) {
                        // A cancelled collector never leaves an unlaunched request claimed for a
                        // future collector; timeout/abort quarantine remains bound for late A.
                        viewModel.releaseBillingCallback(captured)
                        viewModel.onBillingLaunchFailed(operationId)
                    }
                    throw error
                } catch (error: Exception) {
                    AppLogger.e(TAG_OPTIONS_SCREEN, error.javaClass.simpleName)
                    if (!providerHandoffCompleted) {
                        viewModel.releaseBillingCallback(captured)
                        viewModel.onBillingLaunchFailed(operationId)
                    }
                }
            }
        }
    }

    if (capabilities.supportsYouTube) {
        val authorizationLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> viewModel.onAuthorizationActivityResult(result.data) }
        val accountPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.onYouTubeAccountPicked(null)
            } else {
                val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
                    ?: GOOGLE_ACCOUNT_TYPE
                viewModel.onYouTubeAccountPicked(
                    name?.takeIf { it.isNotBlank() }?.let { Account(it, type) },
                )
            }
        }
        LaunchedEffect(Unit) {
            viewModel.authorizationRequest.collect { authorizationLauncher.launch(it) }
        }
        LaunchedEffect(Unit) {
            viewModel.youtubeAccountPickerRequest.collect {
                accountPickerLauncher.launch(newGoogleAccountPickerIntent())
            }
        }
    }

    if (capabilities.supportsDrive) {
        val authorizationLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            viewModel.onDriveAuthorizationActivityResult(result.resultCode, result.data)
        }
        val accountPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.onDriveAccountPicked(null)
            } else {
                val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
                    ?: GOOGLE_ACCOUNT_TYPE
                viewModel.onDriveAccountPicked(
                    name?.takeIf { it.isNotBlank() }?.let { Account(it, type) },
                )
            }
        }
        LaunchedEffect(Unit) {
            viewModel.driveAuthorizationRequest.collect { request ->
                try {
                    authorizationLauncher.launch(request)
                } catch (_: Exception) {
                    viewModel.onDriveAuthorizationLaunchFailed()
                }
            }
        }
        LaunchedEffect(Unit) {
            viewModel.driveAccountPickerRequest.collect {
                accountPickerLauncher.launch(newGoogleAccountPickerIntent())
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(optionsTitle, style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    RoundedIconButton(
                        icon = Icons.Filled.Menu,
                        contentDescription = openDrawerCd,
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            OptionsSection(title = screenSection) {
                C2vCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = themeSection,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.options_theme_system),
                                stringResource(R.string.options_theme_light),
                                stringResource(R.string.options_theme_dark),
                            ),
                            selectedIndex = themeMode.ordinal,
                            enabled = !isLanguageApplying,
                            onSelect = { index ->
                                if (isLanguageApplying) return@SegmentedControl
                                viewModel.setThemeMode(ThemeMode.entries[index])
                            },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = languageSection,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (isLanguageApplying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.options_language_english),
                                stringResource(R.string.options_language_korean),
                            ),
                            selectedIndex = languageOption.ordinal,
                            enabled = !isLanguageApplying,
                            onSelect = { index ->
                                if (isLanguageApplying) return@SegmentedControl
                                val option = LanguageOption.entries.getOrNull(index)
                                    ?: return@SegmentedControl
                                val previous = languageOption
                                val gateActivity = context.findActivityOrNull()
                                if (gateActivity == null ||
                                    gateActivity.isFinishing ||
                                    gateActivity.isDestroyed
                                ) {
                                    viewModel.setLanguage(option, canRestart = false)
                                    return@SegmentedControl
                                }
                                if (!viewModel.setLanguage(option, canRestart = true)) {
                                    return@SegmentedControl
                                }
                                val restartActivity = context.findActivityOrNull()
                                if (restartActivity == null ||
                                    restartActivity.isFinishing ||
                                    restartActivity.isDestroyed
                                ) {
                                    viewModel.rollbackAfterRestartFailure(previous)
                                    return@SegmentedControl
                                }
                                if (!restartApp(restartActivity)) {
                                    viewModel.rollbackAfterRestartFailure(previous)
                                }
                            },
                        )
                    }
                }
            }

            OptionsSection(title = recordingSection) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptionsRecordingFormatContent(
                        recordingFormat = recordingFormat,
                        onSelectFormat = viewModel::setRecordingFormat,
                    )
                    OptionsNoiseReductionContent(
                        noiseReductionMode = noiseReductionMode,
                        onSelectMode = viewModel::setNoiseReductionMode,
                    )
                }
            }

            OptionsSection(title = recordingBackupSection) {
                OptionsRecordingBackupContent(
                    backupState = recordingBackupFolderState,
                    isReconnecting = isRecordingBackupReconciliationInFlight,
                    onSelectFolder = launchRecordingBackupFolderPicker,
                    onReconnect = launchRecordingBackupFolderPicker,
                )
            }

            OptionsSection(title = scheduleSection) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (needsExactAlarmPermission) {
                        C2vCard(
                            modifier = Modifier.testTag("recording_schedule_exact_alarm_banner"),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = exactAlarmBannerText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = {
                                        fun showExactAlarmOpenFailed() {
                                            lifecycleOwner.lifecycleScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    exactAlarmOpenFailed,
                                                )
                                            }
                                        }
                                        val intent =
                                            ExactAlarmPermission.buildRequestIntent(context)
                                        if (intent == null) {
                                            AppLogger.w(
                                                TAG_OPTIONS_SCREEN,
                                                "exact alarm settings intent unavailable (SDK)",
                                            )
                                            showExactAlarmOpenFailed()
                                            return@TextButton
                                        }
                                        if (!ExactAlarmPermission.isRequestIntentResolvable(
                                                context,
                                                intent,
                                            )
                                        ) {
                                            AppLogger.e(
                                                TAG_OPTIONS_SCREEN,
                                                "exact alarm settings intent unresolved",
                                            )
                                            showExactAlarmOpenFailed()
                                            return@TextButton
                                        }
                                        val activity = context.findActivityOrNull()
                                        if (activity == null) {
                                            AppLogger.e(
                                                TAG_OPTIONS_SCREEN,
                                                "exact alarm CTA: Activity unavailable",
                                            )
                                            showExactAlarmOpenFailed()
                                            return@TextButton
                                        }
                                        runCatching {
                                            activity.startActivity(intent)
                                        }.onFailure { e ->
                                            AppLogger.e(
                                                TAG_OPTIONS_SCREEN,
                                                "exact alarm settings intent failed",
                                                e,
                                            )
                                            showExactAlarmOpenFailed()
                                        }
                                    },
                                    modifier = Modifier
                                        .testTag("recording_schedule_exact_alarm_cta_button")
                                        .semantics { contentDescription = exactAlarmCtaCd },
                                ) {
                                    Text(exactAlarmCtaText)
                                }
                            }
                        }
                    }
                    OptionsRecordingScheduleContent(
                        schedules = schedules,
                        onAddClick = {
                            if (!isLanguageApplying) {
                                editingSchedule = null
                                isAddingSchedule = true
                            }
                        },
                        onEditClick = { schedule ->
                            if (!isLanguageApplying) {
                                isAddingSchedule = false
                                editingSchedule = schedule
                            }
                        },
                        onDeleteClick = { id -> pendingDeleteId = id },
                        onEnabledChange = scheduleViewModel::setEnabled,
                        isLanguageApplying = isLanguageApplying,
                    )
                }
            }

            OptionsSection(title = countdownSection) {
                OptionsRecordingCountdownContent(
                    startInMinutes = countdownStartIn,
                    durationMinutes = countdownDuration,
                    pendingCountdown = pendingCountdown,
                    needsExactAlarmPermission = needsExactAlarmPermission,
                    isLanguageApplying = isLanguageApplying,
                    isInFlight = isCountdownInFlight,
                    onStartInDecrement = countdownViewModel::decrementStartIn,
                    onStartInIncrement = countdownViewModel::incrementStartIn,
                    onDurationDecrement = countdownViewModel::decrementDuration,
                    onDurationIncrement = countdownViewModel::incrementDuration,
                    onStartClick = countdownViewModel::startCountdown,
                    onCancelClick = countdownViewModel::cancelCountdown,
                )
            }

            if (shouldShowBillingOptions(capabilities)) {
                OptionsSection(title = proSection) {
                    OptionsProUpgradeContent(
                        proStatus = proStatus,
                        billingPurchaseState = billingPurchaseState,
                        onUpgradeClick = {
                            if (shouldAcceptProBillingClick(isLanguageApplying)) {
                                purchaseProActivityOrNull(context)?.let(viewModel::purchasePro)
                            }
                        },
                        onRestoreClick = {
                            if (shouldAcceptProBillingClick(isLanguageApplying)) {
                                viewModel.restorePurchase()
                            }
                        },
                    )
                }
            }

            if (capabilities.supportsYouTube) {
                OptionsSection(title = youtubeSection) {
                    OptionsYoutubeAccountContent(
                        isAuthorized = isAuthorized,
                        channelTitle = channelTitle,
                        youtubeAccountEmail = youtubeAccountEmail,
                        isChannelTitleLoading = isChannelTitleLoading,
                        channelTitleFetchFailed = channelTitleFetchFailed,
                        youtubeAutoUploadEnabled = youtubeAutoUploadEnabled,
                        youtubeDefaultVisibility = youtubeDefaultVisibility,
                        onLoginClick = {
                            if (!isLanguageApplying) viewModel.requestYouTubeAccountPick()
                        },
                        onSignOutClick = viewModel::signOut,
                        onRetryClick = viewModel::retryFetchChannelTitle,
                        onYoutubeAutoUploadChange = viewModel::setYoutubeAutoUploadEnabled,
                        onYoutubeDefaultVisibilityChange = viewModel::setYoutubeDefaultVisibility,
                        isLanguageApplying = isLanguageApplying,
                    )
                }
            }

            if (capabilities.supportsDrive) {
                OptionsSection(title = driveSection) {
                    OptionsDriveAccountContent(
                        isDriveAuthorized = isDriveAuthorized,
                        driveAccountEmail = driveAccountEmail,
                        driveAutoUploadEnabled = driveAutoUploadEnabled,
                        isDriveAuthInFlight = isDriveAuthInFlight,
                        driveFolderName = driveFolderNameOverride ?: driveFolderNameFallback,
                        onLoginClick = {
                            if (!isLanguageApplying) viewModel.requestDriveAccountPick()
                        },
                        onSignOutClick = viewModel::signOutDrive,
                        onDriveAutoUploadChange = viewModel::setDriveAutoUploadEnabled,
                        onChangeFolderNameClick = {
                            driveFolderNameInput = driveFolderNameOverride ?: driveFolderNameFallback
                            isEditingDriveFolderName = true
                        },
                        isLanguageApplying = isLanguageApplying,
                        driveStorageQuota = driveStorageQuota,
                    )
                }
            }

            OptionsSection(title = uploadSection) {
                OptionsWifiOnlyUploadContent(
                    wifiOnlyUpload = wifiOnlyUpload,
                    onWifiOnlyUploadChange = viewModel::setWifiOnlyUpload,
                )
            }
            OptionsSection(title = contactUsSection) {
                OptionsContactUsContent(
                    onEmailClick = {
                        val intent = buildContactUsIntent(context)
                        try {
                            val chooser = Intent.createChooser(intent, contactUsChooserTitle)
                                .apply {
                                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            context.startActivity(chooser)
                        } catch (e: ActivityNotFoundException) {
                            AppLogger.w(TAG_OPTIONS_SCREEN, "no email app found", e)
                            coroutineScope.launch { snackbarHostState.showSnackbar(contactUsNoEmailApp) }
                        } catch (e: SecurityException) {
                            AppLogger.e(TAG_OPTIONS_SCREEN, "security exception launching email intent", e)
                            coroutineScope.launch { snackbarHostState.showSnackbar(contactUsLaunchFailed) }
                        } catch (e: Exception) {
                            AppLogger.e(TAG_OPTIONS_SCREEN, "failed to launch email intent", e)
                            coroutineScope.launch { snackbarHostState.showSnackbar(contactUsLaunchFailed) }
                        }
                    },
                )
            }
            // Snackbar는 스크롤 위치와 무관하게 화면 최하단에 고정 표시되므로,
            // 스크롤 최하단의 마지막 콘텐츠가 Snackbar에 가려지지 않도록 여백을 확보한다.
            // (위 spacedBy 22dp + snackbarBottomClearance 54dp = 합계 76dp)
            Spacer(modifier = Modifier.height(snackbarBottomClearance))
        }
    }

    // pendingDeleteId가 있으면 EditDialog 숨김(이중 Dialog 금지). 취소=목록만(편집 복귀 없음).
    if (pendingDeleteId == null && (isAddingSchedule || editingSchedule != null)) {
        val editing = editingSchedule
        RecordingScheduleEditDialog(
            initial = editing,
            isSaving = isScheduleSaving,
            onDismiss = {
                if (!isScheduleSaving) {
                    isAddingSchedule = false
                    editingSchedule = null
                }
            },
            onSave = { start, end, mode, mask ->
                // dismiss는 writeSucceeded에서만. 사전검증 실패 시 다이얼로그 유지.
                if (editing == null) {
                    scheduleViewModel.insertSchedule(start, end, mode, mask)
                } else {
                    scheduleViewModel.updateSchedule(editing, start, end, mode, mask)
                }
            },
            onDelete = editing?.let { target ->
                {
                    // Confirm 전에 Edit 닫기 — 취소해도 목록만 남음
                    pendingDeleteId = target.id
                    editingSchedule = null
                    isAddingSchedule = false
                }
            },
        )
    }

    pendingDeleteId?.let { deleteId ->
        val deleteTitle = stringResource(R.string.options_recording_schedule_delete_title)
        val deleteConfirm = stringResource(R.string.options_recording_schedule_delete_confirm)
        val deleteAction = stringResource(R.string.options_recording_schedule_delete)
        val cancelLabel = stringResource(R.string.action_cancel)
        AlertDialog(
            onDismissRequest = {
                // 취소=목록만
                pendingDeleteId = null
                editingSchedule = null
                isAddingSchedule = false
            },
            title = { Text(deleteTitle) },
            text = { Text(deleteConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scheduleViewModel.deleteById(deleteId)
                        pendingDeleteId = null
                        editingSchedule = null
                        isAddingSchedule = false
                    },
                    modifier = Modifier.testTag("schedule_delete_confirm_button"),
                ) {
                    Text(deleteAction)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // 취소=목록만
                        pendingDeleteId = null
                        editingSchedule = null
                        isAddingSchedule = false
                    },
                    modifier = Modifier.testTag("schedule_delete_cancel_button"),
                ) {
                    Text(cancelLabel)
                }
            },
        )
    }

    // Drive 폴더 이름 변경 다이얼로그 — ConvertedVideosTabContent 이름변경 관용구 재사용
    // stringResource는 상단 비조건부 블록(driveFolderDialogTitle 등)에서 미리 취득
    if (isEditingDriveFolderName) {
        val isFolderNameBlank = driveFolderNameInput.isBlank()
        AlertDialog(
            onDismissRequest = { isEditingDriveFolderName = false },
            title = { Text(driveFolderDialogTitle) },
            text = {
                OutlinedTextField(
                    value = driveFolderNameInput,
                    onValueChange = { driveFolderNameInput = it },
                    label = { Text(driveFolderFieldLabel) },
                    singleLine = true,
                    isError = isFolderNameBlank,
                    supportingText = if (isFolderNameBlank) {
                        { Text(driveFolderBlankError) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("drive_folder_name_input"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isFolderNameBlank,
                    onClick = {
                        viewModel.setDriveFolderName(driveFolderNameInput)
                        isEditingDriveFolderName = false
                    },
                    modifier = Modifier.testTag("drive_folder_name_confirm_button"),
                ) {
                    Text(driveFolderConfirmLabel)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { isEditingDriveFolderName = false },
                    modifier = Modifier.testTag("drive_folder_name_cancel_button"),
                ) {
                    Text(driveFolderCancelLabel)
                }
            },
        )
    }

}

@Composable
private fun OptionsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = C2vTheme.colors.ink3,
        )
        content()
    }
}

/**
 * Wi-Fi 전용 업로드 토글 (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * [wifiOnlyUpload] null이면 DataStore 대기 — Switch 비활성.
 */
@Composable
fun OptionsWifiOnlyUploadContent(
    wifiOnlyUpload: Boolean?,
    onWifiOnlyUploadChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wifiOnlyLabel = stringResource(R.string.options_wifi_only_upload)
    val wifiOnlyDescription = stringResource(R.string.options_wifi_only_upload_description)
    val wifiOnlyCd = stringResource(R.string.cd_options_wifi_only_upload)
    val isWifiSettingReady = wifiOnlyUpload != null

    C2vCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = wifiOnlyLabel,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = wifiOnlyDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = C2vTheme.colors.ink3,
                )
            }
            C2vSwitch(
                checked = wifiOnlyUpload == true,
                onCheckedChange = onWifiOnlyUploadChange,
                enabled = isWifiSettingReady,
                modifier = Modifier
                    .testTag("wifi_only_upload_switch")
                    .semantics { contentDescription = wifiOnlyCd },
            )
        }
    }
}

/**
 * Desktop sync 서버 토글 + 페어링 상태 (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * [serverState]가 Running/Starting이면 toggle checked. Stopped/Failed는 unchecked.
 * Starting 중에는 toggle disabled(이중 탭 방지).
 * 6h auto-stop notice는 항상 toggle 아래 표시.
 * [isPaired] true이면 기기명 + Unpair 버튼 노출.
 * [isPaired] false + server **Running** 일 때만 대기 안내 표시 (Starting 중에는 미표시).
 */
@Composable
fun OptionsDesktopPairingContent(
    serverState: ServerState,
    isPaired: Boolean,
    pairedDeviceName: String?,
    onToggleServer: (Boolean) -> Unit,
    onUnpairClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val serverLabel = stringResource(R.string.options_desktop_sync_server_label)
    val autoStopNotice = stringResource(R.string.options_desktop_sync_auto_stop_notice)
    val statusRunning = stringResource(R.string.options_desktop_sync_status_running)
    val statusStarting = stringResource(R.string.options_desktop_sync_status_starting)
    val statusFailed = stringResource(R.string.options_desktop_sync_status_failed)
    val pairedWithText = stringResource(
        R.string.options_desktop_sync_paired_with,
        pairedDeviceName ?: "",
    )
    val unpairLabel = stringResource(R.string.options_desktop_sync_unpair)
    val waitingHint = stringResource(R.string.options_desktop_sync_waiting_hint)
    val toggleCd = stringResource(R.string.cd_options_desktop_sync_server_toggle)
    val unpairCd = stringResource(R.string.cd_options_desktop_sync_unpair)

    val isServerActive = serverState is ServerState.Running || serverState is ServerState.Starting
    val statusText: String? = when (serverState) {
        is ServerState.Running -> statusRunning
        is ServerState.Starting -> statusStarting
        is ServerState.Failed -> statusFailed
        is ServerState.Stopped -> null
    }

    C2vCard(
        contentPadding = PaddingValues(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = serverLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                C2vSwitch(
                    checked = isServerActive,
                    onCheckedChange = onToggleServer,
                    enabled = serverState !is ServerState.Starting,
                    modifier = Modifier
                        .testTag("desktop_sync_server_toggle")
                        .semantics { contentDescription = toggleCd },
                )
            }
            Text(
                text = autoStopNotice,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
            )
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("desktop_sync_status_text"),
                )
            }
            if (isPaired) {
                Text(
                    text = pairedWithText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("desktop_sync_paired_device_name"),
                )
                TextButton(
                    onClick = onUnpairClick,
                    modifier = Modifier
                        .testTag("desktop_sync_unpair_button")
                        .semantics { contentDescription = unpairCd },
                ) {
                    Text(text = unpairLabel)
                }
            } else if (serverState is ServerState.Running) {
                Text(
                    text = waitingHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = C2vTheme.colors.ink3,
                    modifier = Modifier.testTag("desktop_sync_waiting_hint"),
                )
            }
        }
    }
}

/**
 * Google Drive 계정 + 자동 업로드 (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * **상태 머신 A**: [driveAccountEmail]이 non-null일 때만 로그인됨 UI(이메일+로그아웃).
 * email 확정 전·[isDriveAuthInFlight] 중에는 「확인 중…」+ 로그인 버튼 비활성 —
 * authorized prefs만으로 「확인 중…」+로그아웃 UI를 열지 않는다.
 * [isDriveAuthorized]는 호출부 호환·자동업로드 맥락용(계정 카드 분기에는 사용하지 않음).
 * [driveAutoUploadEnabled] null이면 DataStore 대기 — Switch 비활성(wifiOnly 패턴).
 * [driveStorageQuota] non-null일 때만 로그인됨 UI에 사용량 표시(조회 실패·미로그인은 숨김).
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun OptionsDriveAccountContent(
    isDriveAuthorized: Boolean,
    driveAccountEmail: String?,
    driveAutoUploadEnabled: Boolean?,
    isDriveAuthInFlight: Boolean,
    driveFolderName: String,
    onLoginClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onDriveAutoUploadChange: (Boolean) -> Unit,
    onChangeFolderNameClick: () -> Unit,
    isLanguageApplying: Boolean = false,
    driveStorageQuota: DriveStorageQuota? = null,
    modifier: Modifier = Modifier,
) {
    val driveLoginHint = stringResource(R.string.options_drive_login_hint)
    val driveLoginGoogle = stringResource(R.string.drive_login_google)
    val driveSignOutLabel = stringResource(R.string.drive_sign_out)
    val driveEmailChecking = stringResource(R.string.drive_email_checking)
    val driveAutoUploadLabel = stringResource(R.string.options_drive_auto_upload)
    val driveAutoUploadDescription = stringResource(R.string.options_drive_auto_upload_description)
    val driveAutoUploadCd = stringResource(R.string.cd_options_drive_auto_upload)
    val folderNameLabel = stringResource(R.string.options_drive_folder_name_label)
    val folderNameChange = stringResource(R.string.options_drive_folder_name_change)
    val folderNameRow = stringResource(R.string.options_drive_folder_name_row, folderNameLabel, driveFolderName)
    val driveStorageCd = stringResource(R.string.cd_options_drive_storage)
    val isDriveAutoUploadReady = driveAutoUploadEnabled != null
    // 상태 머신 A: email 확정 = 로그인됨 UI ([isDriveAuthorized]는 Screen 호환용, 분기 미사용)
    val isDriveSignedInUi = driveAccountEmail != null

    C2vCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isDriveSignedInUi) {
                Text(
                    text = stringResource(
                        R.string.drive_signed_in,
                        checkNotNull(driveAccountEmail),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = C2vTheme.colors.ink3,
                )
                TextButton(
                    onClick = onSignOutClick,
                    modifier = Modifier.testTag("drive_sign_out_button"),
                ) {
                    Text(driveSignOutLabel)
                }
                if (driveStorageQuota != null) {
                    DriveStorageQuotaContent(
                        quota = driveStorageQuota,
                        contentDescription = driveStorageCd,
                    )
                }
            } else {
                Text(
                    text = if (isDriveAuthInFlight) driveEmailChecking else driveLoginHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = C2vTheme.colors.ink3,
                )
                TextButton(
                    onClick = onLoginClick,
                    enabled = !isDriveAuthInFlight && !isLanguageApplying,
                    modifier = Modifier.testTag("drive_login_button"),
                ) {
                    Text(driveLoginGoogle)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = driveAutoUploadLabel,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = driveAutoUploadDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                }
                C2vSwitch(
                    checked = driveAutoUploadEnabled == true,
                    onCheckedChange = onDriveAutoUploadChange,
                    enabled = isDriveAutoUploadReady,
                    modifier = Modifier
                        .testTag("drive_auto_upload_switch")
                        .semantics { contentDescription = driveAutoUploadCd },
                )
            }
            // 로그인 상태에서만 저장 폴더 행 표시
            if (isDriveSignedInUi) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drive_folder_name_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = folderNameRow,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(
                        onClick = onChangeFolderNameClick,
                        modifier = Modifier.testTag("drive_folder_name_change_button"),
                    ) {
                        Text(folderNameChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveStorageQuotaContent(
    quota: DriveStorageQuota,
    contentDescription: String,
) {
    val limitBytes = quota.limitBytes
    if (limitBytes != null && limitBytes <= 0L) return
    val usageLabel = formatDriveStorageBytesLabel(quota.usageBytes)
    val usageRatio = if (limitBytes != null) {
        (quota.usageBytes.toDouble() / limitBytes.toDouble()).coerceIn(0.0, 1.0)
    } else {
        null
    }
    val labelText = if (usageRatio != null && limitBytes != null) {
        val percent = (usageRatio * 100.0).roundToInt()
        stringResource(
            R.string.options_drive_storage_used_percent,
            percent,
            usageLabel,
            formatDriveStorageBytesLabel(limitBytes),
        )
    } else {
        stringResource(R.string.options_drive_storage_used_unlimited, usageLabel)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (usageRatio != null) {
            LinearProgressIndicator(
                progress = { usageRatio.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drive_storage_quota_indicator"),
            )
        }
        Text(
            text = labelText,
            style = MaterialTheme.typography.bodySmall,
            color = C2vTheme.colors.ink3,
            modifier = Modifier.testTag("drive_storage_quota_label"),
        )
    }
}

@Composable
private fun formatDriveStorageBytesLabel(bytes: Long): String {
    val formatted = formatDriveStorageBytes(bytes)
    return "${formatted.numberLabel} ${stringResource(formatted.unitRes)}"
}

/**
 * YouTube 계정 카드 (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * [isYoutubeSignedInUi] = [isAuthorized] && [channelTitle] != null 일 때만 로그인됨 UI.
 * 표시 문자열은 [youtubeAccountEmail](blank면 [channelTitle] 폴백) — 게이트와 표시를 섞지 않음.
 * 채널명 조회 중([isChannelTitleLoading]=true): 「확인 중…」 텍스트 + [LinearProgressIndicator].
 * 조회 완료 대기([isChannelTitleLoading]=false, 채널명 미확정): 「확인 중…」 텍스트만.
 * 조회 실패([channelTitleFetchFailed]=true): 실패 메시지 + 재시도 버튼.
 * 미로그인: 안내 문구 + 로그인 버튼.
 * [OptionsDriveAccountContent]와 공통 추출 금지.
 * [youtubeAutoUploadEnabled] null이면 DataStore 대기 — Switch 비활성(wifiOnly 패턴).
 */
@Composable
fun OptionsYoutubeAccountContent(
    isAuthorized: Boolean,
    channelTitle: String?,
    isChannelTitleLoading: Boolean,
    channelTitleFetchFailed: Boolean,
    youtubeAutoUploadEnabled: Boolean?,
    onLoginClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onRetryClick: () -> Unit,
    onYoutubeAutoUploadChange: (Boolean) -> Unit,
    isLanguageApplying: Boolean = false,
    youtubeAccountEmail: String? = null,
    youtubeDefaultVisibility: YoutubeDefaultVisibility? = null,
    onYoutubeDefaultVisibilityChange: (YoutubeDefaultVisibility) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val loginHint = stringResource(R.string.options_login_hint)
    val loginGoogle = stringResource(R.string.youtube_login_google)
    val signOutLabel = stringResource(R.string.youtube_sign_out)
    val channelChecking = stringResource(R.string.youtube_channel_checking)
    val channelFetchFailed = stringResource(R.string.youtube_channel_fetch_failed)
    val channelRetry = stringResource(R.string.youtube_channel_retry)
    val youtubeAutoUploadLabel = stringResource(R.string.options_youtube_auto_upload)
    val youtubeAutoUploadDescription = stringResource(R.string.options_youtube_auto_upload_description)
    val youtubeAutoUploadCd = stringResource(R.string.cd_options_youtube_auto_upload)
    val youtubeAutoUploadInfoCd = stringResource(R.string.cd_options_youtube_auto_upload_info)
    val youtubeAutoUploadInfoTitle = stringResource(R.string.options_youtube_auto_upload_info_title)
    val youtubeAutoUploadInfoBody = stringResource(R.string.options_youtube_auto_upload_info_body)
    val confirmLabel = stringResource(R.string.action_confirm)
    val isYoutubeAutoUploadReady = youtubeAutoUploadEnabled != null
    val defaultVisibilitySection = stringResource(R.string.options_youtube_default_visibility)
    val privacyPrivateLabel = stringResource(R.string.youtube_privacy_private)
    val privacyUnlistedLabel = stringResource(R.string.youtube_privacy_unlisted)
    val privacyPublicLabel = stringResource(R.string.youtube_privacy_public)
    val publicWarningTitle = stringResource(R.string.youtube_privacy_public_warning_title)
    val publicWarningBody = stringResource(R.string.youtube_privacy_public_warning_body)
    val defaultVisibilityInfoCd = stringResource(R.string.cd_options_youtube_default_visibility_info)
    val isDefaultVisibilityReady = youtubeDefaultVisibility != null
    var showYoutubeAutoUploadInfo by rememberSaveable { mutableStateOf(false) }
    var showPublicPrivacyWarning by rememberSaveable { mutableStateOf(false) }
    val isYoutubeSignedInUi = isAuthorized && channelTitle != null
    val signedInDisplayName = youtubeAccountEmail?.takeIf { it.isNotBlank() } ?: channelTitle

    if (showYoutubeAutoUploadInfo) {
        AlertDialog(
            onDismissRequest = { showYoutubeAutoUploadInfo = false },
            title = { Text(youtubeAutoUploadInfoTitle) },
            text = { Text(youtubeAutoUploadInfoBody) },
            confirmButton = {
                TextButton(
                    onClick = { showYoutubeAutoUploadInfo = false },
                    modifier = Modifier.testTag("youtube_auto_upload_info_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }

    if (showPublicPrivacyWarning) {
        AlertDialog(
            onDismissRequest = { showPublicPrivacyWarning = false },
            title = { Text(publicWarningTitle) },
            text = { Text(publicWarningBody) },
            confirmButton = {
                TextButton(
                    onClick = { showPublicPrivacyWarning = false },
                    modifier = Modifier.testTag("youtube_default_visibility_public_warning_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }

    C2vCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                isYoutubeSignedInUi -> {
                    Text(
                        text = stringResource(
                            R.string.youtube_signed_in,
                            checkNotNull(signedInDisplayName),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                    )
                    TextButton(
                        onClick = onSignOutClick,
                        modifier = Modifier.testTag("youtube_sign_out_button"),
                    ) {
                        Text(signOutLabel)
                    }
                }
                isAuthorized && !isYoutubeSignedInUi && channelTitleFetchFailed -> {
                    Text(
                        text = channelFetchFailed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                    )
                    TextButton(
                        onClick = onRetryClick,
                        modifier = Modifier.testTag("youtube_retry_button"),
                    ) {
                        Text(channelRetry)
                    }
                }
                isAuthorized && !isYoutubeSignedInUi && !channelTitleFetchFailed && isChannelTitleLoading -> {
                    // G-1: isChannelTitleLoading을 실제 분기 조건으로 사용 — 인디케이터 노출
                    Text(
                        text = channelChecking,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("youtube_channel_loading_indicator"),
                    )
                }
                isAuthorized && !isYoutubeSignedInUi && !channelTitleFetchFailed && !isChannelTitleLoading -> {
                    Text(
                        text = channelChecking,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                    )
                }
                else -> {
                    Text(
                        text = loginHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                    )
                    TextButton(
                        onClick = onLoginClick,
                        enabled = !isLanguageApplying,
                        modifier = Modifier.testTag("youtube_login_button"),
                    ) {
                        Text(loginGoogle)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = youtubeAutoUploadLabel,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SoftIconButton(
                            icon = Icons.Filled.Info,
                            contentDescription = youtubeAutoUploadInfoCd,
                            onClick = { showYoutubeAutoUploadInfo = true },
                            modifier = Modifier.testTag("youtube_auto_upload_info_button"),
                        )
                    }
                    Text(
                        text = youtubeAutoUploadDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                }
                C2vSwitch(
                    checked = youtubeAutoUploadEnabled == true,
                    onCheckedChange = onYoutubeAutoUploadChange,
                    enabled = isYoutubeAutoUploadReady,
                    modifier = Modifier
                        .testTag("youtube_auto_upload_switch")
                        .semantics { contentDescription = youtubeAutoUploadCd },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = defaultVisibilitySection,
                    style = MaterialTheme.typography.titleSmall,
                )
                SegmentedControl(
                    modifier = Modifier.fillMaxWidth(),
                    options = listOf(privacyPrivateLabel, privacyUnlistedLabel, privacyPublicLabel),
                    selectedIndex = youtubeDefaultVisibility?.ordinal ?: -1,
                    onSelect = { index ->
                        if (!isDefaultVisibilityReady || isLanguageApplying) return@SegmentedControl
                        val visibility = YoutubeDefaultVisibility.entries.getOrNull(index)
                            ?: return@SegmentedControl
                        onYoutubeDefaultVisibilityChange(visibility)
                    },
                    enabled = isDefaultVisibilityReady && !isLanguageApplying,
                    optionTestTags = listOf(
                        "youtube_default_visibility_private",
                        "youtube_default_visibility_unlisted",
                        "youtube_default_visibility_public",
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Spacer(modifier = Modifier.weight(2f))
                    SoftIconButton(
                        icon = Icons.Filled.Info,
                        contentDescription = defaultVisibilityInfoCd,
                        onClick = { showPublicPrivacyWarning = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("youtube_default_visibility_public_warning_info_button"),
                    )
                }
            }
        }
    }
}

/**
 * Recording-backup folder card (stateless). The selected SAF tree remains private to the
 * ViewModel; reconnect progress is shown without exposing the provider URI or filesystem path.
 */
@Composable
fun OptionsRecordingBackupContent(
    backupState: RecordingBackupFolderUiState,
    isReconnecting: Boolean = false,
    onSelectFolder: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.options_recording_backup_title)
    val infoContentDescription = stringResource(R.string.cd_options_recording_backup_info)
    val infoTitle = stringResource(R.string.options_recording_backup_info_title)
    val infoBody = stringResource(R.string.options_recording_backup_info_body)
    val confirmLabel = stringResource(R.string.action_confirm)
    val selectFolderLabel = stringResource(R.string.options_recording_backup_select_folder)
    val selectFolderContentDescription =
        stringResource(R.string.cd_options_recording_backup_select_folder)
    val reconnectLabel = stringResource(R.string.options_recording_backup_reconnect)
    val reconnectContentDescription =
        stringResource(R.string.cd_options_recording_backup_reconnect)
    var showRecordingBackupInfo by rememberSaveable { mutableStateOf(false) }

    if (showRecordingBackupInfo) {
        AlertDialog(
            onDismissRequest = { showRecordingBackupInfo = false },
            title = { Text(infoTitle) },
            text = { Text(infoBody) },
            confirmButton = {
                TextButton(
                    onClick = { showRecordingBackupInfo = false },
                    modifier = Modifier.testTag("recording_backup_info_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }

    C2vCard(
        modifier = modifier.testTag("recording_backup_card"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                SoftIconButton(
                    icon = Icons.Filled.Info,
                    contentDescription = infoContentDescription,
                    onClick = { showRecordingBackupInfo = true },
                    modifier = Modifier.testTag("recording_backup_info_button"),
                )
                if (isReconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .testTag("recording_backup_reconciliation_progress"),
                        strokeWidth = 2.dp,
                    )
                }
            }

            when (backupState) {
                RecordingBackupFolderUiState.Unavailable -> {
                    Text(
                        text = stringResource(R.string.options_recording_backup_unavailable_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("recording_backup_status_unavailable"),
                    )
                    Text(
                        text = stringResource(
                            R.string.options_recording_backup_unavailable_description,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                    SoftChipButton(
                        text = selectFolderLabel,
                        onClick = onSelectFolder,
                        enabled = !isReconnecting,
                        modifier = Modifier
                            .testTag("recording_backup_select_folder_button")
                            .semantics {
                                contentDescription = selectFolderContentDescription
                            },
                    )
                }

                RecordingBackupFolderUiState.Connected -> {
                    Text(
                        text = stringResource(R.string.options_recording_backup_connected_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("recording_backup_status_connected"),
                    )
                    Text(
                        text = stringResource(
                            R.string.options_recording_backup_connected_description,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                }

                RecordingBackupFolderUiState.BrokenGrant -> {
                    Text(
                        text = stringResource(R.string.options_recording_backup_broken_grant_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("recording_backup_status_broken_grant"),
                    )
                    Text(
                        text = stringResource(
                            R.string.options_recording_backup_broken_grant_description,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = C2vTheme.colors.ink3,
                    )
                    SoftChipButton(
                        text = reconnectLabel,
                        onClick = onReconnect,
                        enabled = !isReconnecting,
                        modifier = Modifier
                            .testTag("recording_backup_reconnect_button")
                            .semantics {
                                contentDescription = reconnectContentDescription
                            },
                    )
                }
            }
        }
    }
}

/**
 * 녹음 포맷 SegmentedControl (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * [recordingFormat] null이면 DataStore 대기 — SegmentedControl 비활성(wifiOnly 패턴).
 * ordinal: AAC=0, WAV=1 — options 순서와 동기.
 */
@Composable
fun OptionsRecordingFormatContent(
    recordingFormat: RecordingFormat?,
    onSelectFormat: (RecordingFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatSection = stringResource(R.string.options_recording_format_section)
    val aacLabel = stringResource(R.string.options_recording_format_aac)
    val wavLabel = stringResource(R.string.options_recording_format_wav)
    val formatInfoCd = stringResource(R.string.cd_options_recording_format_info)
    val formatInfoTitle = stringResource(R.string.options_recording_format_info_title)
    val formatInfoBody = stringResource(R.string.options_recording_format_info_body)
    val confirmLabel = stringResource(R.string.action_confirm)
    val isReady = recordingFormat != null
    var showRecordingFormatInfo by rememberSaveable { mutableStateOf(false) }

    if (showRecordingFormatInfo) {
        AlertDialog(
            onDismissRequest = { showRecordingFormatInfo = false },
            title = { Text(formatInfoTitle) },
            text = { Text(formatInfoBody) },
            confirmButton = {
                TextButton(
                    onClick = { showRecordingFormatInfo = false },
                    modifier = Modifier.testTag("recording_format_info_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }

    C2vCard(
        modifier = modifier.testTag("recording_format_segmented_control"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatSection,
                    style = MaterialTheme.typography.titleSmall,
                )
                SoftIconButton(
                    icon = Icons.Filled.Info,
                    contentDescription = formatInfoCd,
                    onClick = { showRecordingFormatInfo = true },
                    modifier = Modifier.testTag("recording_format_info_button"),
                )
            }
            SegmentedControl(
                options = listOf(aacLabel, wavLabel),
                selectedIndex = recordingFormat?.ordinal ?: -1,
                onSelect = { index ->
                    if (!isReady) return@SegmentedControl
                    val format = RecordingFormat.entries.getOrNull(index) ?: return@SegmentedControl
                    onSelectFormat(format)
                },
                enabled = isReady,
                optionTestTags = listOf(
                    "recording_format_aac",
                    "recording_format_wav",
                ),
            )
        }
    }
}

/**
 * 잡음 감소 SegmentedControl (무상태). androidTest에서 ViewModel 없이 검증한다.
 *
 * [noiseReductionMode] null이면 DataStore 대기 — SegmentedControl 비활성.
 * ordinal: DeviceDefault=0, On=1, Off=2 — options 순서와 동기.
 */
@Composable
fun OptionsNoiseReductionContent(
    noiseReductionMode: NoiseReductionMode?,
    onSelectMode: (NoiseReductionMode) -> Unit,
    modifier: Modifier = Modifier,
    noiseSuppressorAvailable: Boolean? = null,
) {
    val noiseSection = stringResource(R.string.options_noise_reduction_section)
    val deviceDefaultLabel = stringResource(R.string.options_noise_reduction_device_default)
    val onLabel = stringResource(R.string.options_noise_reduction_on)
    val offLabel = stringResource(R.string.options_noise_reduction_off)
    val wavOnlyHint = stringResource(R.string.options_noise_reduction_wav_only_hint)
    val deviceDefaultHintAvailable =
        stringResource(R.string.options_noise_reduction_device_default_hint_available)
    val deviceDefaultHintUnavailable =
        stringResource(R.string.options_noise_reduction_device_default_hint_unavailable)
    val noiseInfoCd = stringResource(R.string.cd_options_noise_reduction_info)
    val noiseInfoTitle = stringResource(R.string.options_noise_reduction_info_title)
    val noiseInfoBody = stringResource(R.string.options_noise_reduction_info_body)
    val confirmLabel = stringResource(R.string.action_confirm)
    val isReady = noiseReductionMode != null
    var showNoiseReductionInfo by rememberSaveable { mutableStateOf(false) }
    val rememberedSuppressorAvailable = remember { NoiseSuppressor.isAvailable() }
    val suppressorAvailable = noiseSuppressorAvailable ?: rememberedSuppressorAvailable

    if (showNoiseReductionInfo) {
        AlertDialog(
            onDismissRequest = { showNoiseReductionInfo = false },
            title = { Text(noiseInfoTitle) },
            text = { Text(noiseInfoBody) },
            confirmButton = {
                TextButton(
                    onClick = { showNoiseReductionInfo = false },
                    modifier = Modifier.testTag("noise_reduction_info_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
        )
    }

    C2vCard(
        modifier = modifier.testTag("noise_reduction_segmented_control"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = noiseSection,
                    style = MaterialTheme.typography.titleSmall,
                )
                SoftIconButton(
                    icon = Icons.Filled.Info,
                    contentDescription = noiseInfoCd,
                    onClick = { showNoiseReductionInfo = true },
                    modifier = Modifier.testTag("noise_reduction_info_button"),
                )
            }
            SegmentedControl(
                options = listOf(deviceDefaultLabel, onLabel, offLabel),
                selectedIndex = noiseReductionMode?.ordinal ?: -1,
                onSelect = { index ->
                    if (!isReady) return@SegmentedControl
                    val mode = NoiseReductionMode.entries.getOrNull(index) ?: return@SegmentedControl
                    onSelectMode(mode)
                },
                enabled = isReady,
                optionTestTags = listOf(
                    "noise_reduction_device_default",
                    "noise_reduction_on",
                    "noise_reduction_off",
                ),
            )
            // AAC(MediaRecorder)는 audioSessionId 미노출 — WAV에만 적용됨을 항상 안내(포맷 무관 고정 문구)
            Text(
                text = wavOnlyHint,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                modifier = Modifier.testTag("noise_reduction_wav_only_hint"),
            )
            Text(
                text = if (suppressorAvailable) {
                    deviceDefaultHintAvailable
                } else {
                    deviceDefaultHintUnavailable
                },
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                modifier = Modifier.testTag("noise_reduction_device_default_hint"),
            )
        }
    }
}

/**
 * 예약 녹음 카드 목록 (무상태).
 * 행은 각각 [C2vCard] — 시간([formatScheduleTimeRange])을 titleLarge로 강조하고
 * 반복([formatScheduleRepeatSummary]) + [RecordingScheduleRepeatMode] 아이콘을 보조로 둔다.
 * a11y 요약은 [formatScheduleCardSummary] SSOT. 시간/반복 Column만 clickable(편집), Switch·Delete는 밖.
 * 빈 상태는 캘린더 아이콘 + empty 문자열. 행 key = schedule.id.
 */
@Composable
fun OptionsRecordingScheduleContent(
    schedules: List<RecordingSchedule>,
    onAddClick: () -> Unit,
    onEditClick: (RecordingSchedule) -> Unit,
    onDeleteClick: (Long) -> Unit,
    onEnabledChange: (Long, Boolean) -> Unit,
    isLanguageApplying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val emptyLabel = stringResource(R.string.options_recording_schedule_empty)
    val addCd = stringResource(R.string.cd_options_recording_schedule_add)
    val deleteCd = stringResource(R.string.cd_options_recording_schedule_delete)
    val enabledCd = stringResource(R.string.cd_options_recording_schedule_enabled)
    val editCd = stringResource(R.string.cd_options_recording_schedule_edit)
    val onceLabel = stringResource(R.string.options_recording_schedule_once)
    val dailyLabel = stringResource(R.string.options_recording_schedule_daily)
    val weeklyPrefix = stringResource(R.string.options_recording_schedule_weekly_prefix)
    // forEach 밖 — 행 remember 키에 포함 (schedule 전체·enabled/createdAt 제외)
    val dayLabels = listOf(
        stringResource(R.string.options_recording_schedule_day_mon),
        stringResource(R.string.options_recording_schedule_day_tue),
        stringResource(R.string.options_recording_schedule_day_wed),
        stringResource(R.string.options_recording_schedule_day_thu),
        stringResource(R.string.options_recording_schedule_day_fri),
        stringResource(R.string.options_recording_schedule_day_sat),
        stringResource(R.string.options_recording_schedule_day_sun),
    )

    Column(
        modifier = modifier.testTag("recording_schedule_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            SoftIconButton(
                icon = Icons.Filled.Add,
                contentDescription = addCd,
                onClick = onAddClick,
                enabled = !isLanguageApplying,
                modifier = Modifier.testTag("recording_schedule_add_button"),
            )
        }

        if (schedules.isEmpty()) {
            C2vCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recording_schedule_empty_state"),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("recording_schedule_empty_icon"),
                        tint = C2vTheme.colors.ink3,
                    )
                    Text(
                        text = emptyLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = C2vTheme.colors.ink3,
                    )
                }
            }
        } else {
            schedules.forEach { schedule ->
                key(schedule.id) {
                    val repeatMode = remember(schedule.repeatMode) {
                        parseRepeatModeForDisplay(schedule.repeatMode)
                    }
                    val timeRange = remember(
                        schedule.startMinuteOfDay,
                        schedule.endMinuteOfDay,
                    ) {
                        formatScheduleTimeRange(
                            schedule.startMinuteOfDay,
                            schedule.endMinuteOfDay,
                        )
                    }
                    val repeatSummary = remember(
                        schedule.repeatMode,
                        schedule.daysOfWeekMask,
                        onceLabel,
                        dailyLabel,
                        weeklyPrefix,
                        dayLabels,
                    ) {
                        formatScheduleRepeatSummary(
                            schedule = schedule,
                            onceLabel = onceLabel,
                            dailyLabel = dailyLabel,
                            weeklyPrefix = weeklyPrefix,
                            dayLabelsMonToSun = dayLabels,
                        )
                    }
                    val cardSummary = remember(
                        schedule.id,
                        schedule.startMinuteOfDay,
                        schedule.endMinuteOfDay,
                        schedule.repeatMode,
                        schedule.daysOfWeekMask,
                        onceLabel,
                        dailyLabel,
                        weeklyPrefix,
                        dayLabels,
                    ) {
                        formatScheduleCardSummary(
                            schedule = schedule,
                            onceLabel = onceLabel,
                            dailyLabel = dailyLabel,
                            weeklyPrefix = weeklyPrefix,
                            dayLabelsMonToSun = dayLabels,
                        )
                    }
                    val editContentDescription = remember(editCd, cardSummary) {
                        "$editCd, $cardSummary"
                    }
                    C2vCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recording_schedule_row_${schedule.id}"),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = scheduleRepeatModeIcon(repeatMode),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("recording_schedule_repeat_icon_${schedule.id}"),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = editContentDescription
                                    }
                                    .clickable(
                                        enabled = !isLanguageApplying,
                                        onClickLabel = editCd,
                                        onClick = { onEditClick(schedule) },
                                    ),
                            ) {
                                Text(
                                    text = timeRange,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = repeatSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = C2vTheme.colors.ink3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            C2vSwitch(
                                checked = schedule.enabled,
                                onCheckedChange = { enabled ->
                                    onEnabledChange(schedule.id, enabled)
                                },
                                modifier = Modifier
                                    .testTag("recording_schedule_enabled_${schedule.id}")
                                    .semantics {
                                        contentDescription = "$enabledCd, $cardSummary"
                                    },
                            )
                            SoftIconButton(
                                icon = Icons.Filled.Delete,
                                contentDescription = "$deleteCd, $cardSummary",
                                onClick = { onDeleteClick(schedule.id) },
                                modifier = Modifier
                                    .testTag("recording_schedule_delete_${schedule.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** ONCE=캘린더, WEEKLY=목록, DAILY=반복. material-icons-core만 사용. */
private fun scheduleRepeatModeIcon(mode: RecordingScheduleRepeatMode): ImageVector = when (mode) {
    RecordingScheduleRepeatMode.ONCE -> Icons.Filled.DateRange
    RecordingScheduleRepeatMode.WEEKLY -> Icons.AutoMirrored.Filled.List
    RecordingScheduleRepeatMode.DAILY -> Icons.Filled.Refresh
}

/**
 * Options Quick Timer. Exact-alarm 배너는 스케줄 섹션 SSOT — 여기서 복제하지 않음.
 * Start는 [needsExactAlarmPermission]이면 비활성.
 * [nowElapsedMillis] non-null이면 테스트용 고정 시계(틱커 없음). null이면 Content 내부에서
 * STARTED+ 일 때만 1초 틱.
 */
@Composable
fun OptionsRecordingCountdownContent(
    startInMinutes: Int,
    durationMinutes: Int,
    pendingCountdown: PendingCountdown?,
    needsExactAlarmPermission: Boolean,
    onStartInDecrement: () -> Unit,
    onStartInIncrement: () -> Unit,
    onDurationDecrement: () -> Unit,
    onDurationIncrement: () -> Unit,
    onStartClick: () -> Unit,
    onCancelClick: () -> Unit,
    isLanguageApplying: Boolean = false,
    isInFlight: Boolean = false,
    nowElapsedMillis: Long? = null,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var tickedNowElapsedMillis by remember {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    var isLifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    isLifecycleStarted = true
                    if (nowElapsedMillis == null) {
                        tickedNowElapsedMillis = SystemClock.elapsedRealtime()
                    }
                }
                Lifecycle.Event.ON_STOP -> isLifecycleStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pendingCountdown != null, isLifecycleStarted, nowElapsedMillis) {
        if (nowElapsedMillis != null) return@LaunchedEffect
        if (pendingCountdown == null || !isLifecycleStarted) return@LaunchedEffect
        tickedNowElapsedMillis = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000L)
            tickedNowElapsedMillis = SystemClock.elapsedRealtime()
        }
    }
    val resolvedNowElapsedMillis = nowElapsedMillis ?: tickedNowElapsedMillis

    val startInLabel = stringResource(R.string.options_recording_countdown_start_in)
    val durationLabel = stringResource(R.string.options_recording_countdown_duration)
    val startLabel = stringResource(R.string.options_recording_countdown_start)
    val cancelLabel = stringResource(R.string.options_recording_countdown_cancel)
    val statusIdle = stringResource(R.string.options_recording_countdown_status_idle)
    val statusWaitingSoon = stringResource(R.string.options_recording_countdown_status_waiting_soon)
    val pending = pendingCountdown
    val remainingStartMinutes = if (pending == null) {
        0
    } else {
        countdownRemainingStartMinutes(resolvedNowElapsedMillis, pending.startElapsedMillis)
    }
    val startedElapsed = pending?.recordingStartedElapsedMillis
    val elapsedRecordingMinutes = if (pending == null || startedElapsed == null) {
        0
    } else {
        countdownElapsedRecordingMinutes(
            resolvedNowElapsedMillis,
            startedElapsed,
            pending.durationMinutes,
        )
    }
    val statusWaiting = stringResource(
        R.string.options_recording_countdown_status_waiting,
        remainingStartMinutes,
    )
    val recordingDurationMinutes = (pending?.durationMinutes ?: durationMinutes).coerceAtLeast(0)
    val statusRecording = stringResource(
        R.string.options_recording_countdown_status_recording,
        elapsedRecordingMinutes,
        recordingDurationMinutes,
    )
    val startInDecCd = stringResource(R.string.cd_options_recording_countdown_start_in_decrement)
    val startInIncCd = stringResource(R.string.cd_options_recording_countdown_start_in_increment)
    val durationDecCd = stringResource(R.string.cd_options_recording_countdown_duration_decrement)
    val durationIncCd = stringResource(R.string.cd_options_recording_countdown_duration_increment)
    val startCd = stringResource(R.string.cd_options_recording_countdown_start)
    val cancelCd = stringResource(R.string.cd_options_recording_countdown_cancel)

    val hasPending = pendingCountdown != null
    val isRecording = pendingCountdown?.recordingStartedElapsedMillis != null
    val statusText = when {
        pendingCountdown == null -> statusIdle
        isRecording -> statusRecording
        shouldShowCountdownWaitingSoon(remainingStartMinutes) -> statusWaitingSoon
        else -> statusWaiting
    }
    val startEnabled = isCountdownStartEnabled(
        needsExactAlarmPermission = needsExactAlarmPermission,
        isLanguageApplying = isLanguageApplying,
        hasPendingCountdown = hasPending,
        isInFlight = isInFlight,
    )
    val steppersEnabled = !hasPending && !isLanguageApplying && !isInFlight
    val cancelEnabled = hasPending && !isLanguageApplying && !isInFlight

    C2vCard(
        modifier = modifier.testTag("recording_countdown_card"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = startInLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
                StepperControl(
                    value = startInMinutes,
                    onDecrement = onStartInDecrement,
                    onIncrement = onStartInIncrement,
                    decrementEnabled = steppersEnabled && startInMinutes > COUNTDOWN_MINUTES_MIN,
                    incrementEnabled = steppersEnabled && startInMinutes < COUNTDOWN_MINUTES_MAX,
                    decrementTestTag = "recording_countdown_start_in_decrement",
                    incrementTestTag = "recording_countdown_start_in_increment",
                    decrementContentDescription = startInDecCd,
                    incrementContentDescription = startInIncCd,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
                StepperControl(
                    value = durationMinutes,
                    onDecrement = onDurationDecrement,
                    onIncrement = onDurationIncrement,
                    decrementEnabled = steppersEnabled && durationMinutes > COUNTDOWN_MINUTES_MIN,
                    incrementEnabled = steppersEnabled && durationMinutes < COUNTDOWN_MINUTES_MAX,
                    decrementTestTag = "recording_countdown_duration_decrement",
                    incrementTestTag = "recording_countdown_duration_increment",
                    decrementContentDescription = durationDecCd,
                    incrementContentDescription = durationIncCd,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                modifier = Modifier.testTag("recording_countdown_status"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SoftChipButton(
                    text = startLabel,
                    onClick = onStartClick,
                    enabled = startEnabled,
                    modifier = Modifier
                        .testTag("recording_countdown_start_button")
                        .semantics { contentDescription = startCd },
                )
                SoftChipButton(
                    text = cancelLabel,
                    onClick = onCancelClick,
                    enabled = cancelEnabled,
                    modifier = Modifier
                        .testTag("recording_countdown_cancel_button")
                        .semantics { contentDescription = cancelCd },
                )
            }
        }
    }
}

@Composable
internal fun OptionsContactUsContent(
    onEmailClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.options_contact_us_title)
    val description = stringResource(R.string.options_contact_us_description)
    val buttonLabel = stringResource(R.string.options_contact_us_button)
    val buttonCd = stringResource(R.string.cd_options_contact_us)

    C2vCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
            )
            SoftChipButton(
                text = buttonLabel,
                onClick = onEmailClick,
                modifier = Modifier
                    .testTag(TEST_TAG_CONTACT_US_EMAIL)
                    .semantics { contentDescription = buttonCd },
            )
        }
    }
}
