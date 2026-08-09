package com.olyphototagger.app.dawarich

import com.olyphototagger.app.geotag.TrackPoint
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Shape of a point from `GET /api/v1/points?slim=true` — verified against a live
 * Dawarich 1.10.1 instance. Slim mode omits altitude entirely (only the standard,
 * non-slim serializer includes it), so this deliberately doesn't model altitude; add it
 * if a future need justifies the heavier standard payload.
 */
@Serializable
data class DawarichPointDto(
    val latitude: String,
    val longitude: String,
    val timestamp: Long
)

/** Null on unparsable coordinates rather than throwing — one bad point shouldn't fail the batch. */
fun DawarichPointDto.toTrackPointOrNull(): TrackPoint? {
    val lat = latitude.toDoubleOrNull() ?: return null
    val lon = longitude.toDoubleOrNull() ?: return null
    return TrackPoint(time = Instant.ofEpochSecond(timestamp), latitude = lat, longitude = lon)
}
