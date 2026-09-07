package com.example.convert2video.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.convert2video.ui.theme.C2vTheme

@Composable
fun AudioRowBody(
    fileName: String,
    secondaryLine: String,
    folderLabel: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondaryLine.isNotBlank()) {
            Text(
                text = secondaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!folderLabel.isNullOrBlank()) {
            Text(
                text = folderLabel,
                style = MaterialTheme.typography.bodySmall,
                color = C2vTheme.colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
