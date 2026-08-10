package com.olyphototagger.app.dawarich

import com.olyphototagger.app.geotag.TrackPoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.time.Instant

class DawarichApiException(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * Fetches GPS track points from a self-hosted Dawarich instance for a time range.
 * baseUrl and apiToken are supplied by the caller (from [com.olyphototagger.app.settings.SettingsRepository])
 * rather than hardcoded.
 */
class DawarichClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiToken: String
) {
    /**
     * Follows pagination via the X-Total-Pages response header until exhausted, and
     * always returns points sorted ascending by time — GeoInterpolator requires that
     * invariant, and an external API's ordering isn't something to trust blindly.
     */
    suspend fun fetchTrackPoints(startInclusive: Instant, endInclusive: Instant): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        var page = 1
        var totalPages = 1

        do {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/v1/points") {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                parameter("start_at", startInclusive.epochSecond)
                parameter("end_at", endInclusive.epochSecond)
                // Slim mode omits altitude entirely — see DawarichPointDto's doc for why
                // that matters. Costs ~4.6x the response bytes (measured against a live
                // instance: 14.7KB -> 67.5KB for 96 points), acceptable for a per-run
                // track fetch that's at most a day or two of points.
                parameter("slim", false)
                parameter("order", "asc")
                parameter("page", page)
            }

            if (!response.status.isSuccess()) {
                throw DawarichApiException(
                    "Dawarich request failed: ${response.status}",
                    response.status.value
                )
            }

            points += response.body<List<DawarichPointDto>>().mapNotNull { it.toTrackPointOrNull() }
            totalPages = response.headers["X-Total-Pages"]?.toIntOrNull() ?: 1
            page++
        } while (page <= totalPages)

        return points.sortedBy { it.time }
    }
}
