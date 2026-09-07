package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recording_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingRecord>>

    @Insert
    suspend fun insert(record: RecordingRecord): Long

    @Query("UPDATE recording_records SET backupId = :backupId WHERE id = :id")
    suspend fun updateBackupId(id: Long, backupId: Long)

    /**
     * Keep path: the generated local row ID becomes the immutable backup identity in the same
     * Room transaction, so a crash cannot expose a newly kept row with a fabricated identity.
     */
    @Transaction
    suspend fun insertNewWithBackupId(record: RecordingRecord): RecordingRecord {
        val localId = insert(record.copy(id = 0, backupId = 0))
        updateBackupId(id = localId, backupId = localId)
        return record.copy(id = localId, backupId = localId)
    }

    /** Restore path: retain the pre-trash backup identity, including legacy zero. */
    @Transaction
    suspend fun insertRestored(record: RecordingRecord): RecordingRecord {
        val localId = insert(record.copy(id = 0))
        return record.copy(id = localId)
    }

    @Query("DELETE FROM recording_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE recording_records SET filePath = :filePath WHERE id = :id")
    suspend fun updateFilePath(id: Long, filePath: String)
}
