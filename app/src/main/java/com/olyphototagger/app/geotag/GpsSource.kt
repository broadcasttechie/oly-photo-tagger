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
    /**
     * [onProgress] reports cumulative points fetched so far — there's no known total
     * up front (a paginated source only learns its page count from the *first*
     * response), so this is a running count, not a completed/total pair. A source
     * that can't meaningfully report partial progress (e.g. [GpxTrackSource]'s single
     * DB query) is free to never call it; the default no-op means no caller needs to
     * special-case that. Exists so a source that can legitimately take a while — a
     * wide-date-range Dawarich fetch spanning many paginated requests, confirmed for
     * real (2026-09-09) to take minutes when a scan's implied range spans weeks/months
     * of history — has a way to show it's actively working rather than looking
     * indistinguishable from a hang.
     */
    suspend fun fetchTrackPoints(
        startInclusive: Instant,
        endInclusive: Instant,
        onProgress: suspend (fetchedSoFar: Int) -> Unit = {}
    ): List<TrackPoint>
}
