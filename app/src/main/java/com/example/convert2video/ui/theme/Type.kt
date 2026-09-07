package com.example.convert2video.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Design doc uses Pretendard; no font files are bundled in this project so we fall back to the
// platform sans-serif and lean on weight/letter-spacing to keep the same rhythm.
private val C2vFontFamily = FontFamily.Default

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.4).sp),
    displayMedium = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    displaySmall = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    headlineLarge = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    headlineSmall = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    titleLarge = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.02.sp),
    labelSmall = TextStyle(fontFamily = C2vFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.02.sp),
)
