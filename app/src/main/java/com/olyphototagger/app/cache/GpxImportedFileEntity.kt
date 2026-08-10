package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for one imported GPX file. Points themselves live in [GpxTrackPointEntity],
 * linked via [GpxTrackPointEntity.importedFileId] with `ON DELETE CASCADE`, so deleting a
 * file row deletes its points too. Multiple imported files can coexist — importing a new
 * one doesn't replace an existing one, per the user's decision that separate trips'
 * GPX logs should pool together at match time rather than each import wiping the last.
 */
@Entity(tableName = "gpx_imported_file")
data class GpxImportedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val importedAtEpochMillis: Long,
    val pointCount: Int,
    val earliestPointEpochSeconds: Long,
    val latestPointEpochSeconds: Long
)
