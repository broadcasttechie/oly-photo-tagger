package com.olyphototagger.app.dawarich

import com.olyphototagger.app.cache.DawarichCacheDao
import com.olyphototagger.app.cache.DawarichFetchedRangeEntity
import com.olyphototagger.app.cache.toDawarichEntity
import com.olyphototagger.app.cache.toTrackPoint
import com.olyphototagger.app.geotag.GpsSource
import com.olyphototagger.app.geotag.TrackPoint
import java.time.Duration
import java.time.Instant

/**
 * Wraps a real [DawarichClient] with a local cache, so a later scan whose fetch range was
 * already fully covered by an earlier one never touches the network at all. This is
 * specifically about *repeat* scans — [com.olyphototagger.app.pipeline.GeotagOrchestrator]'s
 * own per-cluster fetching (see its `fetchClusteredTrack` doc) is the separate fix for a
 * *single* scan not requesting a wastefully wide range in the first place. Only Dawarich
 * gets wrapped this way — [com.olyphototagger.app.gpx.GpxTrackSource] is already a fast
 * local read with nothing worth caching against.
 *
 * A cache hit is a containment check against [DawarichFetchedRangeEntity], not general
 * interval merging — see that entity's own doc for why.
 *
 * [recentWindow] (see [com.olyphototagger.app.settings.SettingsRepository.
 * dawarichCacheRecentHours]'s own doc for the reasoning) guards specifically against
 * caching a *false negative*: Dawarich can lag behind real time, so a request whose
 * [endInclusive] falls inside this window never gets marked as a completed fetch, even
 * when it comes back empty — a later request for the same range will always ask again
 * for real, until it's old enough that an empty answer can be trusted as final. Real
 * points that *do* come back are cached regardless of how recent the range is; they're
 * never a false claim, only "confirmed nothing here" can be premature.
 */
class CachingDawarichSource(
    private val delegate: DawarichClient,
    private val cacheDao: DawarichCacheDao,
    private val recentWindow: Duration,
    private val now: () -> Instant = Instant::now
) : GpsSource {

    override suspend fun fetchTrackPoints(
        startInclusive: Instant,
        endInclusive: Instant,
        onProgress: suspend (fetchedSoFar: Int) -> Unit
    ): List<TrackPoint> {
        val startEpoch = startInclusive.epochSecond
        val endEpoch = endInclusive.epochSecond

        if (cacheDao.findCoveringRange(startEpoch, endEpoch) != null) {
            val cached = cacheDao.pointsInRange(startEpoch, endEpoch).map { it.toTrackPoint() }
            onProgress(cached.size)
            return cached
        }

        val fetched = delegate.fetchTrackPoints(startInclusive, endInclusive, onProgress)
        cacheDao.insertPoints(fetched.map { it.toDawarichEntity() })
        if (endInclusive.isBefore(now().minus(recentWindow))) {
            cacheDao.insertFetchedRange(DawarichFetchedRangeEntity(startEpochSeconds = startEpoch, endEpochSeconds = endEpoch))
        }
        return fetched
    }
}
