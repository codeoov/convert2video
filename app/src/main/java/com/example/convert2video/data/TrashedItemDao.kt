package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashedItemDao {

    @Query("SELECT * FROM trashed_items ORDER BY deletedAt DESC, id DESC")
    fun observeAll(): Flow<List<TrashedItem>>

    @Insert
    suspend fun insert(item: TrashedItem): Long

    @Query("DELETE FROM trashed_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM trashed_items WHERE deletedAt < :cutoffEpochMs")
    suspend fun listExpired(cutoffEpochMs: Long): List<TrashedItem>
}
