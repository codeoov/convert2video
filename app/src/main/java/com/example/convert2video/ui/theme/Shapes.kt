package com.example.convert2video.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val C2vShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Corner radii not covered by [C2vShapes] roles, kept as literal dp to mirror the design doc exactly. */
object C2vRadius {
    val card = 18.dp
    val innerCard = 14.dp
    val pill = 13.dp
    val control = 11.dp
    val cta = 16.dp
    val chip = 10.dp
    val avatar = 12.dp
    val badge = 6.dp
}
