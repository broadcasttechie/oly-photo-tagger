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
 * version 2 (was 1): added the gpx_imported_file/gpx_track_point tables. version 3 (was 2):
 * added the write_log table (see [WriteLogEntity]) — the persistent audit trail
 * GpsExifWriteResult's doc always said was coming. No Migration is written for either
 * bump — fallbackToDestructiveMigration() is the honest expression of this project's
 * current "pre-release, no real user data to preserve yet" stance, rather than an
 * unhandled crash on any device that already has an older database. Everything else in
 * this database is a rebuildable cache/import, never the original photos — write_log is
 * the first table where that's no longer quite true (losing it loses history, not just a
 * cache), worth keeping in mind if this policy is revisited before a real release.
 */
@Database(
    entities = [
        GeoTagCacheEntity::class,
        GpxImportedFileEntity::class,
        GpxTrackPointEntity::class,
        WriteLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun geoTagCacheDao(): GeoTagCacheDao
    abstract fun gpxTrackDao(): GpxTrackDao
    abstract fun writeLogDao(): WriteLogDao

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
