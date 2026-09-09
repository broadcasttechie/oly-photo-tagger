package com.olyphototagger.app.geotag

import java.time.Instant

/** Progress for a single [GpsSource.fetchTrackPoints] call. [page]/[totalPages] default
 *  to 1/1 for a source with no natural pagination (e.g. [GpxTrackSource]'s one-shot DB
 *  read) — a page-based ETA over that degenerates to "1 of 1" (no proportional info)
 *  rather than a divide-by-zero or a fabricated page count. */
data class FetchProgress(val fetchedSoFar: Int, val page: Int = 1, val totalPages: Int = 1)

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
     * [onProgress] reports cumulative points fetched so far, plus page/totalPages for a
     * naturally paginated source — there's no known point *total* up front (a paginated
     * source only learns its page count from the *first* response), so [FetchProgress.
     * fetchedSoFar] is a running count, not a completed/total pair, but [FetchProgress.
     * page]/[FetchProgress.totalPages] is a real completed/total pair once the first
     * response lands, and is what a caller should project an ETA from. A source that
     * can't meaningfully report partial progress (e.g. [GpxTrackSource]'s single DB
     * query) is free to never call it; the default no-op means no caller needs to
     * special-case that. Exists so a source that can legitimately take a while — a
     * wide-date-range Dawarich fetch spanning many paginated requests, confirmed for
     * real (2026-09-09) to take minutes when a scan's implied range spans weeks/months
     * of history — has a way to show it's actively working rather than looking
     * indistinguishable from a hang.
     */
    suspend fun fetchTrackPoints(
        startInclusive: Instant,
        endInclusive: Instant,
        onProgress: suspend (FetchProgress) -> Unit = {}
    ): List<TrackPoint>
}
