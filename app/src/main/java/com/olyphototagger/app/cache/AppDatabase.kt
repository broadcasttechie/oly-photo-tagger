package com.olyphototagger.app.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * exportSchema = false for now, since there's no prior schema to migrate from yet and no
 * schema-export directory set up. Revisit before shipping a release that needs to migrate
 * real user data across versions.
 */
@Database(entities = [GeoTagCacheEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun geoTagCacheDao(): GeoTagCacheDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oly-photo-tagger.db"
                ).build().also { instance = it }
            }
    }
}
