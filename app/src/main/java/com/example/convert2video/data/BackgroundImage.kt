package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backgrounds")
data class BackgroundImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val addedAt: Long,
    val isSelected: Boolean = false,
)
