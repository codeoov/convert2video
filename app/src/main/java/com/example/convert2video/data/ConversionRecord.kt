package com.example.convert2video.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_records")
data class ConversionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audioUri: String,
    val videoUri: String,
    /** Epoch milliseconds ([System.currentTimeMillis]). */
    val createdAt: Long,
    /** Shared UUID for a segment batch; null when not a segmented conversion. */
    val segmentBatchId: String? = null,
    /** 1-based index within the batch; null when not a segmented conversion. */
    val segmentIndex: Int? = null,
    /** Total segments in the batch; null when not a segmented conversion. */
    val segmentTotal: Int? = null,
)
