package com.example.convert2video.ui.components.layout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Public layout composables:
 * - [GradientThumbnailPlaceholder]
 * - [DashedDropZone]
 * - [SegmentPreviewBar]
 */

/** Small rounded avatar with the design doc's diagonal cocoa gradient placeholder. */
// TODO(2026-08-04): Sprint 토큰화 — C2vTheme 그라데이션 미정
@Composable
fun GradientThumbnailPlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.avatar))
            .background(Brush.linearGradient(listOf(Color(0xFF3B2F22), Color(0xFF1C1712))))
            .border(BorderStroke(1.dp, C2vTheme.colors.cardBorder), RoundedCornerShape(C2vRadius.avatar)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Dashed-border empty-state drop zone, e.g. the "오디오를 선택하세요" panel. */
@Composable
fun DashedDropZone(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val strokeColor = C2vTheme.colors.cardBorder
    val fillColor = C2vTheme.colors.dropZoneBackground
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(C2vRadius.card))
            .background(fillColor)
            .dashedRoundRectBorder(
                color = strokeColor,
                cornerRadius = C2vRadius.card,
                strokeWidth = 1.5.dp,
                dashLength = 6.dp,
                gapLength = 5.dp,
            )
            .padding(vertical = 30.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun Modifier.dashedRoundRectBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp,
    dashLength: Dp,
    gapLength: Dp,
): Modifier = this.then(
    Modifier.drawWithCache {
        val strokeWidthPx = strokeWidth.toPx()
        val cornerPx = cornerRadius.toPx()
        val pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(dashLength.toPx(), gapLength.toPx()),
            0f,
        )
        onDrawBehind {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = strokeWidthPx, pathEffect = pathEffect),
            )
        }
    },
)

/** Thin accent segment-count preview bars, purely decorative (mirrors the design doc's split preview). */
@Composable
fun SegmentPreviewBar(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count.coerceAtLeast(1)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
