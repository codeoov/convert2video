package com.example.convert2video

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.convert2video.ui.AudioPickScreen
import com.example.convert2video.ui.BackgroundPickScreen
import com.example.convert2video.ui.ConvertedVideosScreen
import com.example.convert2video.ui.HomeScreen
import com.example.convert2video.ui.HomeViewModel
import com.example.convert2video.ui.theme.Convert2videoTheme

enum class AppDestination { Home, BackgroundPick, AudioPick, ConvertedVideos }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val homeViewModel: HomeViewModel = viewModel()
            var currentDestination by rememberSaveable { mutableStateOf(AppDestination.Home) }

            BackHandler(enabled = currentDestination != AppDestination.Home) {
                currentDestination = AppDestination.Home
            }

            Convert2videoTheme {
                when (currentDestination) {
                    AppDestination.Home -> HomeScreen(
                        viewModel = homeViewModel,
                        onNavigateToBackgroundPick = {
                            currentDestination = AppDestination.BackgroundPick
                        },
                        onNavigateToAudioPick = {
                            currentDestination = AppDestination.AudioPick
                        },
                        onNavigateToConvertedVideos = {
                            currentDestination = AppDestination.ConvertedVideos
                        },
                    )
                    AppDestination.BackgroundPick -> BackgroundPickScreen(
                        onNavigateBack = { currentDestination = AppDestination.Home },
                    )
                    AppDestination.AudioPick -> AudioPickScreen(
                        onAudioPicked = { item ->
                            homeViewModel.setAudio(item.uri, item.title, item.artist)
                            currentDestination = AppDestination.Home
                        },
                        onNavigateBack = { currentDestination = AppDestination.Home },
                    )
                    AppDestination.ConvertedVideos -> ConvertedVideosScreen(
                        onNavigateBack = { currentDestination = AppDestination.Home },
                    )
                }
            }
        }
    }
}
