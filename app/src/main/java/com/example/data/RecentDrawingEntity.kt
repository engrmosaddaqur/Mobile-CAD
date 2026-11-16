package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_drawings")
data class RecentDrawingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val projectGroup: String,
    val format: String,
    val entityCount: Int,
    val filePath: String,
    val autosavePath: String,
    val thumbnailPath: String,
    val hasUnsavedRecovery: Boolean,
    val lastModifiedMs: Long
)
