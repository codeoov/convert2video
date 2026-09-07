package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedAudioDao {

    @Query("SELECT * FROM imported_audio_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImportedAudioRecord>>

    @Insert
    suspend fun insert(record: ImportedAudioRecord): Long

    @Query("DELETE FROM imported_audio_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
