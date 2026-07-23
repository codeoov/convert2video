package com.example.convert2video.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.convert2video.data.AppDatabase
import com.example.convert2video.data.BackgroundImage
import com.example.convert2video.data.BackgroundRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BackgroundLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BackgroundRepository(
        context = application,
        dao = AppDatabase.getInstance(application).backgroundDao(),
    )

    val backgrounds: StateFlow<List<BackgroundImage>> = repository.backgrounds.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun addBackground(uri: Uri) {
        viewModelScope.launch { repository.addBackground(uri) }
    }

    fun selectBackground(id: Long) {
        viewModelScope.launch { repository.selectBackground(id) }
    }

    fun deleteBackground(background: BackgroundImage) {
        viewModelScope.launch { repository.deleteBackground(background) }
    }
}
