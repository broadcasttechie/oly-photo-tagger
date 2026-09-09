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
 * GpsExifWriteResult's doc always said was coming. version 4 (was 3): geotag_cache gained
 * captureTimestampExactEpochMillis/captureTimestampNaiveLocal — the cache already skipped
 * re-opening a file once known *tagged*, but had nowhere to remember an as-yet-untagged
 * file's status, which on a fresh card is most of it, every single rescan. version 5 (was
 * 4): added dawarich_track_point/dawarich_fetched_range (see [DawarichFetchedRangeEntity])
 * — a rescan of an unchanged card was re-fetching the exact same Dawarich range from
 * scratch every time; this is what lets a repeat scan skip the network entirely, the same
 * way the v4 change did for the per-file EXIF read. No Migration is written for any bump —
 * fallbackToDestructiveMigration() is the honest expression of this project's current
 * "pre-release, no real user data to preserve yet" stance, rather than an unhandled crash
 * on any device that already has an older database. Everything else in this database is a
 * rebuildable cache/import, never the original photos — write_log is the first table
 * where that's no longer quite true (losing it loses history, not just a cache), worth
 * keeping in mind if this policy is revisited before a real release.
 */
@Database(
    entities = [
        GeoTagCacheEntity::class,
        GpxImportedFileEntity::class,
        GpxTrackPointEntity::class,
        WriteLogEntity::class,
        DawarichTrackPointEntity::class,
        DawarichFetchedRangeEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun geoTagCacheDao(): GeoTagCacheDao
    abstract fun gpxTrackDao(): GpxTrackDao
    abstract fun writeLogDao(): WriteLogDao
    abstract fun dawarichCacheDao(): DawarichCacheDao

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
