package com.olyphototagger.app.dawarich

import com.olyphototagger.app.geotag.TrackPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant

/**
 * Shape of a point from `GET /api/v1/points?slim=false` — the standard (non-slim)
 * serializer, needed because slim mode omits altitude entirely. Everything else this
 * doesn't model (battery, wifi, geocoding, motion data, ~20 more fields) is silently
 * dropped by the client's `ignoreUnknownKeys` config.
 *
 * `altitude` is deliberately typed as [JsonElement], not `Double`: Dawarich's
 * PointSerializer emits it straight from `Point#altitude`, which reads whichever of two
 * backing columns is populated — the legacy integer `altitude` (serializes as a bare
 * JSON number) or the newer `altitude_decimal` during their in-progress migration
 * (Rails serializes BigDecimal as a JSON *string* to avoid float precision loss). Which
 * one a given row has depends on whether it's been backfilled, so both shapes can appear
 * across points from the same instance. A plain `Double` field would throw on whichever
 * shape it didn't guess.
 */
@Serializable
data class DawarichPointDto(
    val latitude: String,
    val longitude: String,
    val timestamp: Long,
    val altitude: JsonElement? = null
)

/** Null on unparsable coordinates rather than throwing — one bad point shouldn't fail the batch. */
fun DawarichPointDto.toTrackPointOrNull(): TrackPoint? {
    val lat = latitude.toDoubleOrNull() ?: return null
    val lon = longitude.toDoubleOrNull() ?: return null
    val alt = (altitude as? JsonPrimitive)?.doubleOrNull
    return TrackPoint(time = Instant.ofEpochSecond(timestamp), latitude = lat, longitude = lon, altitudeMeters = alt)
}
