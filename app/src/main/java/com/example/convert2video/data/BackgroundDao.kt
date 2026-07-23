package com.example.convert2video.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BackgroundDao {
    @Query("SELECT * FROM backgrounds ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<BackgroundImage>>

    @Query("SELECT * FROM backgrounds WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelected(): BackgroundImage?

    @Insert
    suspend fun insert(background: BackgroundImage): Long

    @Query("DELETE FROM backgrounds WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE backgrounds SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE backgrounds SET isSelected = 1 WHERE id = :id")
    suspend fun setSelected(id: Long)

    /** Selects [id] exclusively, unselecting every other row. */
    @Transaction
    suspend fun selectExclusively(id: Long) {
        clearSelection()
        setSelected(id)
    }

    /** If nothing is selected, makes [id] the selection. No-op otherwise. */
    @Transaction
    suspend fun selectIfNoneSelected(id: Long) {
        if (getSelected() == null) {
            setSelected(id)
        }
    }
}
