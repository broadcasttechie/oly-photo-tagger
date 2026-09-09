package com.olyphototagger.app.geocode

import kotlin.math.round

/**
 * Rounds [latitude]/[longitude] to [precision] decimal places and combines them into a
 * single cache key — the whole point being that two *different* coordinates from the same
 * immediate area collapse onto the *same* key, so a dozen photos taken 20m apart across
 * one outing share one address lookup instead of one each. [precision] 3 is ~111m of
 * latitude at the equator (a little less for longitude at higher latitudes) — comfortably
 * finer than "which building," coarser than raw GPS jitter, and not meaningfully less
 * correct than the coordinates themselves: consumer GPS is rarely accurate to much better
 * than this anyway.
 */
fun bucketKey(latitude: Double, longitude: Double, precision: Int = 3): String {
    val factor = Math.pow(10.0, precision.toDouble())
    val roundedLat = round(latitude * factor) / factor
    val roundedLon = round(longitude * factor) / factor
    return "$roundedLat,$roundedLon"
}
