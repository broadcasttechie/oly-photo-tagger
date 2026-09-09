package com.olyphototagger.app.image

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.olyphototagger.app.BuildConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * OpenStreetMap's tile usage policy (operations.osmfoundation.org/policies/tiles/)
 * requires a valid User-Agent identifying the application — this is that identification,
 * not a generic/default client string. Also *why* a tile is only ever fetched for one
 * photo at a time, on an explicit tap (see MatchedRow's "show map" toggle in
 * DryRunScreen), never eagerly for every row in what can be a thousand-row list — bulk,
 * automatic tile fetching is exactly what that policy asks real applications not to do
 * against the free public tile server.
 */
private const val OSM_USER_AGENT = "OlyPhotoTagger/${BuildConfig.VERSION_NAME} (+https://github.com/broadcasttechie/oly-photo-tagger)"

/**
 * The standard Web Mercator "slippy map" tile address for [latitude]/[longitude] at
 * [zoom] — see wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Lon..2Flat._to_tile_numbers_2.
 * [zoom] 15 is a few hundred metres across, close enough to actually show where a photo
 * was taken without needing more than one tile.
 */
fun osmTileUrl(latitude: Double, longitude: Double, zoom: Int = 15): String {
    val latRad = Math.toRadians(latitude)
    val n = 2.0.pow(zoom)
    val x = ((longitude + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
    val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n.toInt() - 1)
    return "https://tile.openstreetmap.org/$zoom/$x/$y.png"
}

/** An [ImageRequest] for [osmTileUrl], carrying the required [OSM_USER_AGENT]. */
fun osmTileRequest(context: Context, latitude: Double, longitude: Double, zoom: Int = 15): ImageRequest =
    ImageRequest.Builder(context)
        .data(osmTileUrl(latitude, longitude, zoom))
        .httpHeaders(NetworkHeaders.Builder().set("User-Agent", OSM_USER_AGENT).build())
        .build()
