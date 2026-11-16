package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecentDrawingEntity::class], version = 1, exportSchema = false)
abstract class CadDatabase : RoomDatabase() {
    abstract fun recentDrawingDao(): RecentDrawingDao

    companion object {
        @Volatile
        private var INSTANCE: CadDatabase? = null

        fun getInstance(context: Context): CadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CadDatabase::class.java,
                    "mobile_cad.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
