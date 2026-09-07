package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ErrorLogDao {

    @Insert
    suspend fun insert(entry: ErrorLogEntry)

    @Query("SELECT * FROM error_log_entries ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<ErrorLogEntry>>

    @Query(
        "DELETE FROM error_log_entries WHERE id IN (" +
            "SELECT id FROM error_log_entries " +
            "ORDER BY createdAt DESC, id DESC LIMIT -1 OFFSET :retentionLimit" +
            ")",
    )
    suspend fun deleteOutsideRetention(retentionLimit: Int)

    /** Room rolls back both statements when insertion or retention trimming fails. */
    @Transaction
    suspend fun insertAndTrim(entry: ErrorLogEntry, retentionLimit: Int) {
        insert(entry)
        deleteOutsideRetention(retentionLimit)
    }

    @Query("DELETE FROM error_log_entries")
    suspend fun deleteAll()

    @Query("DELETE FROM error_log_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}
