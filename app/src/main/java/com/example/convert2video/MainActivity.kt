package com.example.convert2video

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.convert2video.ui.BackgroundLibraryScreen
import com.example.convert2video.ui.theme.Convert2videoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Convert2videoTheme {
                BackgroundLibraryScreen()
            }
        }
    }
}
