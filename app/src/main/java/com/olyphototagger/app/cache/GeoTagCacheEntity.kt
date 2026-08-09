package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/** fileKey is CameraFile.identityKey() — see FileIdentity.kt for why that's the key. */
@Entity(tableName = "geotag_cache")
data class GeoTagCacheEntity(
    @PrimaryKey val fileKey: String,
    val hasGeoTag: Boolean,
    val checkedAtEpochMillis: Long
)
