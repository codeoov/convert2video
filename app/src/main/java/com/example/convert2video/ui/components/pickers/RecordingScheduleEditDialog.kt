package com.example.convert2video.ui.components.pickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.convert2video.R
import com.example.convert2video.data.RecordingSchedule
import com.example.convert2video.record.RecordingScheduleRepeatMode
import com.example.convert2video.ui.components.controls.SegmentedControl
import java.time.LocalDate
import java.time.LocalTime

private enum class ScheduleTimePickerTarget {
    None,
    Start,
    End,
}

private val MINUTE_OF_DAY_RANGE = 0..1439

/**
 * 예약 녹음 추가/수정 다이얼로그.
 * Material3 [TimePicker]×2는 이 파일 내부에만 둔다 (TimePicker A — 별도 TimePickerXxx.kt 금지).
 * 시작/종료는 요약 칩으로 두고, 탭한 쪽 TimePicker만 펼친다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingScheduleEditDialog(
    initial: RecordingSchedule?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        startMinuteOfDay: Int,
        endMinuteOfDay: Int,
        repeatMode: RecordingScheduleRepeatMode,
        daysOfWeekMask: Int,
    ) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isEdit = initial != null
    val defaultStart = defaultScheduleStartMinuteOfDay()
    val defaultEnd = defaultScheduleEndMinuteOfDay(defaultStart)

    val initialStart = initial?.startMinuteOfDay ?: defaultStart
    val initialEnd = initial?.endMinuteOfDay ?: defaultEnd
    val initialMode = initial?.let {
        RecordingScheduleRepeatMode.fromStorageValue(it.repeatMode)
    } ?: RecordingScheduleRepeatMode.ONCE
    val initialMask = when {
        initial == null -> 0
        initialMode == RecordingScheduleRepeatMode.WEEKLY -> initial.daysOfWeekMask
        else -> 0
    }

    var repeatMode by remember { mutableStateOf(initialMode) }
    var daysOfWeekMask by remember {
        mutableIntStateOf(
            if (initialMode == RecordingScheduleRepeatMode.WEEKLY && initialMask == 0) {
                todayWeekdayBitMask()
            } else {
                initialMask
            },
        )
    }
    var expandedPicker by remember { mutableStateOf(ScheduleTimePickerTarget.None) }

    val startPickerState = rememberTimePickerState(
        initialHour = initialStart / 60,
        initialMinute = initialStart % 60,
        is24Hour = true,
    )
    val endPickerState = rememberTimePickerState(
        initialHour = initialEnd / 60,
        initialMinute = initialEnd % 60,
        is24Hour = true,
    )

    val title = stringResource(
        if (isEdit) {
            R.string.options_recording_schedule_edit_title
        } else {
            R.string.options_recording_schedule_add_title
        },
    )
    val onceLabel = stringResource(R.string.options_recording_schedule_once)
    val weeklyLabel = stringResource(R.string.options_recording_schedule_weekly)
    val dailyLabel = stringResource(R.string.options_recording_schedule_daily)
    val startLabel = stringResource(R.string.options_recording_schedule_start)
    val endLabel = stringResource(R.string.options_recording_schedule_end)
    val saveLabel = stringResource(R.string.options_recording_schedule_save)
    val cancelLabel = stringResource(R.string.action_cancel)
    val deleteLabel = stringResource(R.string.options_recording_schedule_delete)
    val dayLabels = listOf(
        stringResource(R.string.options_recording_schedule_day_mon),
        stringResource(R.string.options_recording_schedule_day_tue),
        stringResource(R.string.options_recording_schedule_day_wed),
        stringResource(R.string.options_recording_schedule_day_thu),
        stringResource(R.string.options_recording_schedule_day_fri),
        stringResource(R.string.options_recording_schedule_day_sat),
        stringResource(R.string.options_recording_schedule_day_sun),
    )

    val startMinute = startPickerState.hour * 60 + startPickerState.minute
    val endMinute = endPickerState.hour * 60 + endPickerState.minute
    val startSelected = expandedPicker == ScheduleTimePickerTarget.Start
    val endSelected = expandedPicker == ScheduleTimePickerTarget.End

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = modifier.testTag("recording_schedule_edit_dialog"),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SegmentedControl(
                    options = listOf(onceLabel, weeklyLabel, dailyLabel),
                    selectedIndex = scheduleRepeatSegmentIndex(repeatMode),
                    onSelect = { index ->
                        if (isSaving) return@SegmentedControl
                        val next = scheduleRepeatModeFromSegmentIndex(index)
                        repeatMode = next
                        if (next == RecordingScheduleRepeatMode.WEEKLY && daysOfWeekMask == 0) {
                            daysOfWeekMask = todayWeekdayBitMask()
                        }
                        if (next != RecordingScheduleRepeatMode.WEEKLY) {
                            daysOfWeekMask = 0
                        }
                    },
                    enabled = !isSaving,
                    optionTestTags = listOf(
                        "schedule_repeat_once",
                        "schedule_repeat_weekly",
                        "schedule_repeat_daily",
                    ),
                )

                if (repeatMode == RecordingScheduleRepeatMode.WEEKLY) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dayLabels.forEachIndexed { bit, label ->
                            val selected = daysOfWeekMask and (1 shl bit) != 0
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (isSaving) return@FilterChip
                                    daysOfWeekMask = if (selected) {
                                        daysOfWeekMask and (1 shl bit).inv()
                                    } else {
                                        daysOfWeekMask or (1 shl bit)
                                    }
                                },
                                enabled = !isSaving,
                                label = { Text(label) },
                                modifier = Modifier.testTag("schedule_day_$bit"),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = startSelected,
                        onClick = {
                            if (isSaving) return@FilterChip
                            expandedPicker = if (startSelected) {
                                ScheduleTimePickerTarget.None
                            } else {
                                ScheduleTimePickerTarget.Start
                            }
                        },
                        enabled = !isSaving,
                        label = { Text("$startLabel ${formatMinuteOfDay(startMinute)}") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("schedule_start_time_chip"),
                    )
                    FilterChip(
                        selected = endSelected,
                        onClick = {
                            if (isSaving) return@FilterChip
                            expandedPicker = if (endSelected) {
                                ScheduleTimePickerTarget.None
                            } else {
                                ScheduleTimePickerTarget.End
                            }
                        },
                        enabled = !isSaving,
                        label = { Text("$endLabel ${formatMinuteOfDay(endMinute)}") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("schedule_end_time_chip"),
                    )
                }

                when (expandedPicker) {
                    ScheduleTimePickerTarget.Start -> {
                        Text(
                            text = startLabel,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TimePicker(
                            state = startPickerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_start_time_picker"),
                        )
                    }
                    ScheduleTimePickerTarget.End -> {
                        Text(
                            text = endLabel,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TimePicker(
                            state = endPickerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("schedule_end_time_picker"),
                        )
                    }
                    ScheduleTimePickerTarget.None -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    if (isSaving) return@TextButton
                    val mask = if (repeatMode == RecordingScheduleRepeatMode.WEEKLY) {
                        daysOfWeekMask
                    } else {
                        0
                    }
                    onSave(
                        startPickerState.hour * 60 + startPickerState.minute,
                        endPickerState.hour * 60 + endPickerState.minute,
                        repeatMode,
                        mask,
                    )
                },
                modifier = Modifier.testTag("schedule_save_button"),
            ) {
                Text(saveLabel)
            }
        },
        dismissButton = {
            Row(modifier = Modifier.padding(end = 4.dp)) {
                if (onDelete != null) {
                    TextButton(
                        enabled = !isSaving,
                        onClick = onDelete,
                        modifier = Modifier.testTag("schedule_dialog_delete_button"),
                    ) {
                        Text(deleteLabel)
                    }
                }
                TextButton(
                    enabled = !isSaving,
                    onClick = onDismiss,
                    modifier = Modifier.testTag("schedule_cancel_button"),
                ) {
                    Text(cancelLabel)
                }
            }
        },
    )
}

// ── Schedule pure helpers (pickers SSOT — Dialog의 screens.options import 0) ──

/** Segmented 인덱스 SSOT: 0=ONCE, 1=WEEKLY, 2=DAILY. */
fun scheduleRepeatSegmentIndex(mode: RecordingScheduleRepeatMode): Int = when (mode) {
    RecordingScheduleRepeatMode.ONCE -> 0
    RecordingScheduleRepeatMode.WEEKLY -> 1
    RecordingScheduleRepeatMode.DAILY -> 2
}

fun scheduleRepeatModeFromSegmentIndex(index: Int): RecordingScheduleRepeatMode = when (index) {
    1 -> RecordingScheduleRepeatMode.WEEKLY
    2 -> RecordingScheduleRepeatMode.DAILY
    else -> RecordingScheduleRepeatMode.ONCE
}

/** 현재 시각의 hour*60 (정시). */
fun defaultScheduleStartMinuteOfDay(): Int = LocalTime.now().hour * 60

/** (시작 + 30) % 1440 — 시작≠종료 보장. */
fun defaultScheduleEndMinuteOfDay(startMinuteOfDay: Int): Int =
    (startMinuteOfDay + 30) % 1440

/**
 * 오늘 요일 1 bit (Monday-start: bit0=월 … bit6=일).
 * [LocalDate.dayOfWeek.value]는 MON=1 … SUN=7 → bit = value - 1.
 */
fun todayWeekdayBitMask(): Int {
    val bit = LocalDate.now().dayOfWeek.value - 1
    return 1 shl bit
}

/** 분 단위 → `HH:mm`. */
fun formatMinuteOfDay(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(MINUTE_OF_DAY_RANGE)
    val hour = clamped / 60
    val minute = clamped % 60
    return "%02d:%02d".format(hour, minute)
}

/** 시간 요약 `HH:mm–HH:mm` (overnight end < start 허용, 표시만). */
fun formatScheduleTimeRange(startMinuteOfDay: Int, endMinuteOfDay: Int): String =
    "${formatMinuteOfDay(startMinuteOfDay)}–${formatMinuteOfDay(endMinuteOfDay)}"

/**
 * 표시 전용 silent 파싱 — unknown/blank → [ONCE], [AppLogger] 없음.
 * (Room 읽기·복구는 [RecordingScheduleRepeatMode.fromStorageValue]를 쓴다.)
 */
fun parseRepeatModeForDisplay(value: String): RecordingScheduleRepeatMode = when (value) {
    RecordingScheduleRepeatMode.WEEKLY.name -> RecordingScheduleRepeatMode.WEEKLY
    RecordingScheduleRepeatMode.DAILY.name -> RecordingScheduleRepeatMode.DAILY
    else -> RecordingScheduleRepeatMode.ONCE
}

/**
 * 반복 요약.
 * ONCE=`1회`, DAILY=`매일`, WEEKLY=`매주`+선택 요일(월…일, Monday-start bit0..bit6).
 * 빈 WEEKLY 마스크 → [weeklyPrefix]만(폴백).
 * [dayLabelsMonToSun] 계약: size == 7 (Mon…Sun).
 */
fun formatScheduleRepeatSummary(
    schedule: RecordingSchedule,
    onceLabel: String,
    dailyLabel: String,
    weeklyPrefix: String,
    dayLabelsMonToSun: List<String>,
): String {
    require(dayLabelsMonToSun.size == 7) {
        "dayLabelsMonToSun must have size 7 (Mon…Sun), was ${dayLabelsMonToSun.size}"
    }
    val mode = parseRepeatModeForDisplay(schedule.repeatMode)
    return when (mode) {
        RecordingScheduleRepeatMode.ONCE -> onceLabel
        RecordingScheduleRepeatMode.DAILY -> dailyLabel
        RecordingScheduleRepeatMode.WEEKLY -> {
            val selected = buildList {
                for (bit in 0..6) {
                    if (schedule.daysOfWeekMask and (1 shl bit) != 0) {
                        add(dayLabelsMonToSun[bit])
                    }
                }
            }
            if (selected.isEmpty()) {
                weeklyPrefix
            } else {
                "$weeklyPrefix ${selected.joinToString(",")}"
            }
        }
    }
}

/**
 * Options 카드 한 줄 요약: 반복 + 시간 (UI는 maxLines=1 + Ellipsis).
 * 예: `1회 07:00–07:30`, `매일 07:00–07:30`, `매주 월,수,금 07:00–07:30`.
 * [dayLabelsMonToSun] 계약: size == 7 — [formatScheduleRepeatSummary]에서 require.
 */
fun formatScheduleCardSummary(
    schedule: RecordingSchedule,
    onceLabel: String,
    dailyLabel: String,
    weeklyPrefix: String,
    dayLabelsMonToSun: List<String>,
): String {
    val repeat = formatScheduleRepeatSummary(
        schedule = schedule,
        onceLabel = onceLabel,
        dailyLabel = dailyLabel,
        weeklyPrefix = weeklyPrefix,
        dayLabelsMonToSun = dayLabelsMonToSun,
    )
    val time = formatScheduleTimeRange(schedule.startMinuteOfDay, schedule.endMinuteOfDay)
    return "$repeat $time"
}