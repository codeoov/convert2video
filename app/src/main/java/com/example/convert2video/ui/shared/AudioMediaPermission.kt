package com.example.convert2video.ui.shared

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class AudioMediaPermissionState(
    val launch: () -> Unit,
    val launchGetContent: () -> Unit,
)

@Composable
fun rememberAudioMediaPermissionState(
    onPermissionGranted: () -> Unit,
    onAudioSelected: (Uri) -> Unit,
): AudioMediaPermissionState {
    val context = LocalContext.current
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val currentOnPermissionGranted = rememberUpdatedState(onPermissionGranted)
    val currentOnAudioSelected = rememberUpdatedState(onAudioSelected)

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { currentOnAudioSelected.value(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            currentOnPermissionGranted.value()
        } else {
            getContentLauncher.launch("audio/*")
        }
    }

    return remember(permissionLauncher, getContentLauncher, context, audioPermission) {
        AudioMediaPermissionState(
            launch = {
                if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) {
                    currentOnPermissionGranted.value()
                } else {
                    permissionLauncher.launch(audioPermission)
                }
            },
            launchGetContent = {
                getContentLauncher.launch("audio/*")
            },
        )
    }
}
