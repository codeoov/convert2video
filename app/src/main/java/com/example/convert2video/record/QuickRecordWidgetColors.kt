package com.example.convert2video.record

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider

/** Glance QuickRecord 위젯 배경·텍스트 [ColorProvider] SSOT. */
internal object QuickRecordWidgetColors {
    val permissionBackground = ColorProvider(
        day = Color(0xFFFFBB33),
        night = Color(0xFFFF8800),
    )
    val activeBackground = ColorProvider(
        day = Color(0xFFFF4444),
        night = Color(0xFFCC0000),
    )
    val idleBackground = ColorProvider(
        day = Color(0xFF666666),
        night = Color(0xFF444444),
    )
    val permissionText = ColorProvider(
        day = Color.Black,
        night = Color.White,
    )
}
