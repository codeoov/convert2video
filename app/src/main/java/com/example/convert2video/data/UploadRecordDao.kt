package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadRecordDao {
    @Query("SELECT * FROM upload_records ORDER BY uploadedAt DESC, id DESC")
    fun observeAll(): Flow<List<UploadRecord>>

    @Query("SELECT * FROM upload_records ORDER BY uploadedAt DESC, id DESC LIMIT :limit")
    fun findRecent(limit: Int): Flow<List<UploadRecord>>

    @Query(
        "SELECT * FROM upload_records WHERE videoUri = :videoUri " +
            "ORDER BY uploadedAt DESC, id DESC LIMIT 1",
    )
    suspend fun findLatestForVideo(videoUri: String): UploadRecord?

    @Insert
    suspend fun insert(record: UploadRecord): Long

    @Query("DELETE FROM upload_records WHERE videoUri = :videoUri")
    suspend fun deleteByVideoUri(videoUri: String)

    @Query("DELETE FROM upload_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM upload_records WHERE uploadedAt >= :startMillis")
    suspend fun countUploadsSince(startMillis: Long): Int

    @Query("SELECT COUNT(*) FROM upload_records WHERE uploadedAt >= :startMillis")
    fun observeCountSince(startMillis: Long): Flow<Int>
}
