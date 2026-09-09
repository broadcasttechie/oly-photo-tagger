package com.olyphototagger.app.gpx

import com.olyphototagger.app.cache.GpxTrackDao
import com.olyphototagger.app.cache.toTrackPoint
import com.olyphototagger.app.geotag.GpsSource
import com.olyphototagger.app.geotag.TrackPoint
import java.time.Instant

/**
 * Reads track points from every imported GPX file, pooled together — see [GpxTrackDao]'s
 * doc for why there's no per-file filtering here. The defensive re-sort mirrors
 * DawarichClient's own "an external source's ordering isn't something to trust blindly"
 * stance, even though the DAO's own query already asks for ascending order.
 */
class GpxTrackSource(private val dao: GpxTrackDao) : GpsSource {
    // onProgress unused: a single DB query has no meaningful partial-progress moment to
    // report — see GpsSource's own doc for why that's fine to just never call.
    override suspend fun fetchTrackPoints(
        startInclusive: Instant,
        endInclusive: Instant,
        onProgress: suspend (fetchedSoFar: Int) -> Unit
    ): List<TrackPoint> =
        dao.pointsInRange(startInclusive.epochSecond, endInclusive.epochSecond)
            .map { it.toTrackPoint() }
            .sortedBy { it.time }
}
