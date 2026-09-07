package com.example.convert2video.data

import android.content.Context
import android.net.Uri
import com.example.convert2video.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "BackgroundRepository"

class BackgroundRepository(
    private val context: Context,
    private val dao: BackgroundDao,
    private val settingsRepository: SettingsRepository = SettingsRepository(context),
) {
    val backgrounds: Flow<List<BackgroundImage>> = dao.observeAll()

    suspend fun addBackground(sourceUri: Uri) {
        val destFile = File(backgroundsDir(context), "bg_${UUID.randomUUID()}.jpg")
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open picked image")
        }
        val id = dao.insert(BackgroundImage(filePath = destFile.absolutePath, addedAt = System.currentTimeMillis()))
        // First background saved becomes the default selection; later ones require an explicit tap.
        dao.selectIfNoneSelected(id)
    }

    suspend fun selectBackground(id: Long) = dao.selectExclusively(id)

    suspend fun deleteBackground(background: BackgroundImage) {
        dao.deleteById(background.id)
        withContext(Dispatchers.IO) { File(background.filePath).delete() }
        val lastUsed = try {
            settingsRepository.lastUsedBackgroundPath.first()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read lastUsedBackgroundPath on delete: ${e.javaClass.simpleName}")
            null
        }
        if (lastUsed != null && lastUsed == background.filePath) {
            settingsRepository.clearLastUsedBackgroundPathCatching()
        }
    }

    companion object {
        fun backgroundsDir(context: Context): File =
            File(context.filesDir, "backgrounds").apply { mkdirs() }
    }
}
