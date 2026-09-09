package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A single cached Dawarich point — see [DawarichFetchedRangeEntity]'s doc for how this
 *  cache decides whether a given range still needs a real network fetch. */
@Entity(tableName = "dawarich_track_point")
data class DawarichTrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochSeconds: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?
)
