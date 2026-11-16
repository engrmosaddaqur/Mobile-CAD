package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDrawingDao {
    @Query("SELECT * FROM recent_drawings ORDER BY lastModifiedMs DESC")
    fun observeAll(): Flow<List<RecentDrawingEntity>>

    @Query("SELECT * FROM recent_drawings WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RecentDrawingEntity?

    @Query("SELECT * FROM recent_drawings WHERE hasUnsavedRecovery = 1 ORDER BY lastModifiedMs DESC LIMIT 1")
    suspend fun getLatestPendingRecovery(): RecentDrawingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentDrawingEntity)

    @Query("UPDATE recent_drawings SET name = :newName, lastModifiedMs = :modifiedMs WHERE id = :id")
    suspend fun rename(id: String, newName: String, modifiedMs: Long)

    @Query("UPDATE recent_drawings SET hasUnsavedRecovery = :hasRecovery WHERE id = :id")
    suspend fun setRecoveryFlag(id: String, hasRecovery: Boolean)

    @Query("DELETE FROM recent_drawings WHERE id = :id")
    suspend fun deleteById(id: String)
}
