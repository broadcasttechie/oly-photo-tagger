package com.olyphototagger.app.geotag

import java.time.Instant

/**
 * A source of GPS track points for a time range — Dawarich, an imported GPX file, or
 * (in the future) some other online tracking API. [GeotagOrchestrator] and everything
 * below it only ever see [TrackPoint]s through this interface, so adding a new source
 * never touches the interpolation/matching core.
 *
 * Implementations must return points sorted ascending by time — [GeoInterpolator]
 * requires that invariant, and no source's own ordering should be trusted blindly.
 */
interface GpsSource {
    suspend fun fetchTrackPoints(startInclusive: Instant, endInclusive: Instant): List<TrackPoint>
}
