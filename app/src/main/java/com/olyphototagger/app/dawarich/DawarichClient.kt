package com.olyphototagger.app.dawarich

import com.olyphototagger.app.geotag.FetchProgress
import com.olyphototagger.app.geotag.GpsSource
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
 *
 * [log] defaults to a no-op deliberately, not `android.util.Log` — this class is JVM
 * unit-tested (see [DawarichClientTest]), and the stub `android.jar` JVM tests run
 * against throws on any real framework call, `Log.d` included. Real callers (currently
 * just [com.olyphototagger.app.pipeline.buildGpsSource]) supply a working one explicitly.
 */
class DawarichClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiToken: String,
    private val log: (String) -> Unit = {}
) : GpsSource {
    /**
     * Follows pagination via the X-Total-Pages response header until exhausted, and
     * always returns points sorted ascending by time — GeoInterpolator requires that
     * invariant, and an external API's ordering isn't something to trust blindly.
     *
     * Each page is a real, sequential round trip — no concurrency, since Dawarich's own
     * pagination has no natural parallel-fetch shape without first knowing totalPages,
     * which only the *first* response provides. This was assumed to cost "at most a day
     * or two of points" per run; confirmed for real (2026-09-09) that an unfiltered
     * whole-folder scan's implied range isn't bounded by that at all — one real 50-day
     * span came back as 3368 pages / ~337,000 points, each page a genuine ~1.1-1.4s
     * round trip with no errors and no server-side slowness, ~65 minutes total. Not a
     * timeout, not a hang — Dawarich answered every single request promptly; the
     * request itself was just enormous. [onProgress] and [HttpTimeout] (installed in
     * [createDawarichHttpClient]) exist because of that finding: progress so a
     * legitimately-large fetch doesn't look identical to a broken one, and a timeout so
     * an *actually* stuck connection fails cleanly instead of hanging indefinitely
     * (confirmed the same day: a receiver relying on this hanging past ~60s triggered a
     * real Android ANR dialog).
     */
    override suspend fun fetchTrackPoints(
        startInclusive: Instant,
        endInclusive: Instant,
        onProgress: suspend (FetchProgress) -> Unit
    ): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        var page = 1
        var totalPages = 1
        val startedAtMs = System.currentTimeMillis()

        do {
            val pageStartedAtMs = System.currentTimeMillis()
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/v1/points") {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                parameter("start_at", startInclusive.epochSecond)
                parameter("end_at", endInclusive.epochSecond)
                // Slim mode omits altitude entirely — see DawarichPointDto's doc for why
                // that matters. Costs ~4.6x the response bytes (measured against a live
                // instance: 14.7KB -> 67.5KB for 96 points) — fine for a genuinely
                // day-or-two range, part of the real cost on a much wider one (see this
                // function's own doc above).
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
            log(
                "page $page/$totalPages: ${points.size} points so far, " +
                    "${System.currentTimeMillis() - pageStartedAtMs}ms this page, " +
                    "${System.currentTimeMillis() - startedAtMs}ms total"
            )
            onProgress(FetchProgress(points.size, page, totalPages))
            page++
        } while (page <= totalPages)

        return points.sortedBy { it.time }
    }
}
