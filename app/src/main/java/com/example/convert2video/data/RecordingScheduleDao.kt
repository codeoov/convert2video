package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingScheduleDao {

    @Query("SELECT * FROM recording_schedules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingSchedule>>

    @Query("SELECT * FROM recording_schedules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecordingSchedule?

    @Insert
    suspend fun insert(schedule: RecordingSchedule): Long

    @Update
    suspend fun update(schedule: RecordingSchedule)

    @Query("DELETE FROM recording_schedules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE recording_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("SELECT * FROM recording_schedules WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getAllEnabled(): List<RecordingSchedule>
}
