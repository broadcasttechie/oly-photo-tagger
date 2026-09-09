package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * fileKey is CameraFile.identityKey() — see FileIdentity.kt for why that's the key.
 *
 * At most one of [captureTimestampExactEpochMillis]/[captureTimestampNaiveLocal] is ever
 * non-null, mirroring CaptureTimestamp's own two cases (see com.olyphototagger.app.exif.
 * GeoTagCacheMapper, which is the only code that reads or writes them) — a resolved Instant
 * isn't enough on its own because the naive case still needs the camera-offset setting
 * applied at read time, and that's user-adjustable between scans of the same card.
 */
@Entity(tableName = "geotag_cache")
data class GeoTagCacheEntity(
    @PrimaryKey val fileKey: String,
    val hasGeoTag: Boolean,
    val checkedAtEpochMillis: Long,
    val captureTimestampExactEpochMillis: Long? = null,
    val captureTimestampNaiveLocal: String? = null
)
