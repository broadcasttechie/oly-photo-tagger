package com.olyphototagger.app.geocode

import com.olyphototagger.app.osm.OSM_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class NominatimApiException(message: String, val statusCode: Int? = null) : Exception(message)

@Serializable
private data class NominatimReverseResponse(
    @SerialName("display_name") val displayName: String? = null
)

/**
 * One reverse-geocode lookup against Nominatim's public `/reverse` endpoint — a full
 * `display_name` string (e.g. "10 Downing Street, Westminster, London, ... United
 * Kingdom"), not the shorter label actually shown in the UI (see [com.olyphototagger.app.
 * ui.workflow.shortenAddress]) — the full string is what's cached, so a later shortening-
 * logic change doesn't need every cached row re-fetched to benefit from it.
 *
 * Deliberately no pagination, no retry, no rate-limiting here — this class only ever
 * makes the one HTTP call it's asked to; [AddressResolver] is where the real 1
 * request/second ceiling Nominatim's usage policy requires is enforced, the same
 * separation [com.olyphototagger.app.dawarich.DawarichClient] has from [com.
 * olyphototagger.app.dawarich.CachingDawarichSource].
 */
class NominatimClient(
    private val httpClient: HttpClient,
    private val log: (String) -> Unit = {}
) {
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        val response = httpClient.get("https://nominatim.openstreetmap.org/reverse") {
            header(HttpHeaders.UserAgent, OSM_USER_AGENT)
            parameter("format", "jsonv2")
            parameter("lat", latitude)
            parameter("lon", longitude)
        }
        if (!response.status.isSuccess()) {
            throw NominatimApiException("Nominatim request failed: ${response.status}", response.status.value)
        }
        val displayName = response.body<NominatimReverseResponse>().displayName
        log("reverseGeocode($latitude, $longitude) -> $displayName")
        return displayName
    }
}
