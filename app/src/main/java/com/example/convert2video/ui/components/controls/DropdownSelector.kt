package com.example.convert2video.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Pill 배경의 드롭다운 셀렉터.
 * Controls.kt의 [SegmentedControl]과 동일 스타일(surfaceVariant / pill / labelMedium) 사용.
 *
 * @param options 표시할 항목 목록.
 * @param selectedIndex 현재 선택된 인덱스 (0-based).
 * @param onSelect 항목 선택 콜백 — 선택된 인덱스 전달.
 * @param modifier 외부 레이아웃 수정자.
 * @param enabled false면 클릭 불가 + 흐린 색상.
 * @param testTag 접근성·UI 테스트용 태그. null이면 태그 없음.
 * @param contentDescription 접근성 설명 오버라이드. null이면 선택된 항목 라벨을 사용.
 */
@Composable
fun DropdownSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    contentDescription: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.getOrElse(selectedIndex) { options.firstOrNull().orEmpty() }
    // semantics 람다 안에서 stringResource 호출 금지 — 본문에서 미리 확보
    val rowContentDescription = contentDescription ?: selectedLabel

    Box(
        modifier = if (testTag != null) modifier.testTag(testTag) else modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(C2vRadius.pill))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics {
                    role = Role.Button
                    this.contentDescription = rowContentDescription
                }
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    C2vTheme.colors.ink3
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    C2vTheme.colors.ink3
                },
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    leadingIcon = if (index == selectedIndex) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
