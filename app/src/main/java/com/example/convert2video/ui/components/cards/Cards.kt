package com.example.convert2video.ui.components.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.convert2video.ui.theme.C2vRadius
import com.example.convert2video.ui.theme.C2vTheme

/**
 * Public card composables:
 * - [C2vCard]
 */

/**
 * Rounded surface card matching the design doc's `border-radius:18px; background:var(--surface);
 * border:1px solid var(--line)` card shell. Prefer this over stock [Card] so every screen shares
 * the exact same container styling.
 */
@Composable
fun C2vCard(
    modifier: Modifier = Modifier,
    accentBorder: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(C2vRadius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (accentBorder) MaterialTheme.colorScheme.primary else C2vTheme.colors.cardBorder,
        ),
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
