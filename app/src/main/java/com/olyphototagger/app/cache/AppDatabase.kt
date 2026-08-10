package com.olyphototagger.app.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * exportSchema = false for now, since there's no prior schema to migrate from yet and no
 * schema-export directory set up. Revisit before shipping a release that needs to migrate
 * real user data across versions.
 *
 * version 2 (was 1): added the gpx_imported_file/gpx_track_point tables. No Migration is
 * written for this bump — fallbackToDestructiveMigration() is the honest expression of
 * this project's current "pre-release, no real user data to preserve yet" stance, rather
 * than an unhandled crash on any device that already has a v1 database. Everything in
 * this database is a rebuildable cache/import, never the original photos.
 */
@Database(
    entities = [GeoTagCacheEntity::class, GpxImportedFileEntity::class, GpxTrackPointEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun geoTagCacheDao(): GeoTagCacheDao
    abstract fun gpxTrackDao(): GpxTrackDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "oly-photo-tagger.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
