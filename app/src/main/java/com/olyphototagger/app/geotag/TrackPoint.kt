package com.olyphototagger.app.geotag

import java.time.Instant

/**
 * A single GPS fix from a track source (Dawarich or imported GPX).
 * Altitude is optional — Dawarich points may omit it.
 */
data class TrackPoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null
)
