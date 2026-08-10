package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS fix from an imported GPX file. Deliberately no per-file filtering baked into
 * the read path ([GpxTrackDao.pointsInRange]) — points from every imported file pool
 * together within a time range, matching the decision that multiple imports should
 * merge rather than one replacing another.
 */
@Entity(
    tableName = "gpx_track_point",
    foreignKeys = [
        ForeignKey(
            entity = GpxImportedFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["importedFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("importedFileId"), Index("epochSeconds")]
)
data class GpxTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val importedFileId: Long,
    val epochSeconds: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?
)
