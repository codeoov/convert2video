package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionRecordDao {
    @Query("SELECT * FROM conversion_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ConversionRecord>>

    @Query("SELECT * FROM conversion_records ORDER BY createdAt DESC LIMIT :limit")
    fun findRecent(limit: Int): Flow<List<ConversionRecord>>

    @Query("SELECT * FROM conversion_records WHERE audioUri = :audioUri ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByAudioUri(audioUri: String): ConversionRecord?

    @Insert
    suspend fun insert(record: ConversionRecord): Long

    @Query("DELETE FROM conversion_records WHERE videoUri = :videoUri")
    suspend fun deleteByVideoUri(videoUri: String)

    @Query("DELETE FROM conversion_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}
