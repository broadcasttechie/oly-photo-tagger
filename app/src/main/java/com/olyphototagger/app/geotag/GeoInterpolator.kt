package com.olyphototagger.app.geotag

import java.time.Duration
import java.time.Instant

/** Outcome of matching a photo timestamp against a GPS track. */
sealed interface GeoMatch {
    /** A usable position, with the bracketing gap so the UI can show confidence. */
    data class Matched(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double?,
        /** Time between the bracketing track points (zero for an exact/endpoint hit). */
        val bracketGap: Duration,
        /** Distance in time from the photo to the nearest track point. */
        val nearestPointOffset: Duration
    ) : GeoMatch

    /** Bracketing points exist but are further apart than the configured threshold. */
    data class GapTooLarge(val bracketGap: Duration, val threshold: Duration) : GeoMatch

    /** Photo timestamp falls outside the track's time range (beyond edge tolerance). */
    data class OutsideTrack(val nearestPointOffset: Duration?) : GeoMatch
}

/**
 * Matches photo timestamps to positions on a GPS track by linear interpolation between
 * the two bracketing points — the same approach as exiftool's `-geotag`.
 *
 * @param maxBracketGap if the two bracketing points are further apart in time than this,
 *   the match is flagged [GeoMatch.GapTooLarge] instead of silently interpolated.
 * @param edgeTolerance how far outside the track's first/last point a photo may fall and
 *   still snap to that endpoint. Beyond it → [GeoMatch.OutsideTrack].
 */
class GeoInterpolator(
    private val maxBracketGap: Duration,
    private val edgeTolerance: Duration = Duration.ofSeconds(60)
) {

    /**
     * @param track must be sorted by time ascending; duplicates in time are tolerated.
     */
    fun match(photoTime: Instant, track: List<TrackPoint>): GeoMatch {
        if (track.isEmpty()) return GeoMatch.OutsideTrack(nearestPointOffset = null)

        val first = track.first()
        val last = track.last()

        if (photoTime < first.time) {
            val offset = Duration.between(photoTime, first.time)
            return if (offset <= edgeTolerance) first.asEndpointMatch(offset)
            else GeoMatch.OutsideTrack(offset)
        }
        if (photoTime > last.time) {
            val offset = Duration.between(last.time, photoTime)
            return if (offset <= edgeTolerance) last.asEndpointMatch(offset)
            else GeoMatch.OutsideTrack(offset)
        }

        // Binary search for the first point at or after photoTime.
        var lo = 0
        var hi = track.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (track[mid].time < photoTime) lo = mid + 1 else hi = mid
        }
        val after = track[lo]
        if (after.time == photoTime) {
            return GeoMatch.Matched(
                after.latitude, after.longitude, after.altitudeMeters,
                bracketGap = Duration.ZERO,
                nearestPointOffset = Duration.ZERO
            )
        }
        val before = track[lo - 1]

        val gap = Duration.between(before.time, after.time)
        if (gap > maxBracketGap) return GeoMatch.GapTooLarge(gap, maxBracketGap)

        val fraction =
            Duration.between(before.time, photoTime).toMillis().toDouble() / gap.toMillis()
        val altitude = if (before.altitudeMeters != null && after.altitudeMeters != null) {
            before.altitudeMeters + (after.altitudeMeters - before.altitudeMeters) * fraction
        } else null

        val toBefore = Duration.between(before.time, photoTime)
        val toAfter = Duration.between(photoTime, after.time)
        return GeoMatch.Matched(
            latitude = before.latitude + (after.latitude - before.latitude) * fraction,
            longitude = interpolateLongitude(before.longitude, after.longitude, fraction),
            altitudeMeters = altitude,
            bracketGap = gap,
            nearestPointOffset = minOf(toBefore, toAfter)
        )
    }

    private fun TrackPoint.asEndpointMatch(offset: Duration) = GeoMatch.Matched(
        latitude, longitude, altitudeMeters,
        bracketGap = Duration.ZERO,
        nearestPointOffset = offset
    )

    /** Interpolate longitude via the short way around the antimeridian. */
    private fun interpolateLongitude(from: Double, to: Double, fraction: Double): Double {
        var delta = to - from
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        val result = from + delta * fraction
        return when {
            result > 180 -> result - 360
            result < -180 -> result + 360
            else -> result
        }
    }
}
