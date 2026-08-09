package com.olyphototagger.app.geotag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GeoInterpolatorTest {

    private val t0 = Instant.parse("2026-08-01T14:00:00Z")
    private fun pt(secondsAfterT0: Long, lat: Double, lon: Double, alt: Double? = null) =
        TrackPoint(t0.plusSeconds(secondsAfterT0), lat, lon, alt)

    private val interpolator = GeoInterpolator(maxBracketGap = Duration.ofMinutes(5))

    @Test
    fun `interpolates midway between two points`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(100, 54.0, -1.0))
        val match = interpolator.match(t0.plusSeconds(50), track) as GeoMatch.Matched
        assertEquals(53.5, match.latitude, 1e-9)
        assertEquals(-1.5, match.longitude, 1e-9)
        assertEquals(Duration.ofSeconds(100), match.bracketGap)
        assertEquals(Duration.ofSeconds(50), match.nearestPointOffset)
    }

    @Test
    fun `exact timestamp hit returns that point with zero gap`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(100, 54.0, -1.0))
        val match = interpolator.match(t0.plusSeconds(100), track) as GeoMatch.Matched
        assertEquals(54.0, match.latitude, 1e-9)
        assertEquals(Duration.ZERO, match.bracketGap)
    }

    @Test
    fun `interpolates altitude only when both points have it`() {
        val withAlt = listOf(pt(0, 53.0, -2.0, 100.0), pt(100, 54.0, -1.0, 200.0))
        val match = interpolator.match(t0.plusSeconds(50), withAlt) as GeoMatch.Matched
        assertEquals(150.0, match.altitudeMeters!!, 1e-9)

        val missingAlt = listOf(pt(0, 53.0, -2.0, 100.0), pt(100, 54.0, -1.0))
        val match2 = interpolator.match(t0.plusSeconds(50), missingAlt) as GeoMatch.Matched
        assertEquals(null, match2.altitudeMeters)
    }

    @Test
    fun `gap above threshold is flagged not interpolated`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(600, 54.0, -1.0))
        val match = interpolator.match(t0.plusSeconds(300), track)
        assertTrue(match is GeoMatch.GapTooLarge)
        match as GeoMatch.GapTooLarge
        assertEquals(Duration.ofSeconds(600), match.bracketGap)
        assertEquals(Duration.ofMinutes(5), match.threshold)
    }

    @Test
    fun `gap exactly at threshold still interpolates`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(300, 54.0, -1.0))
        val match = interpolator.match(t0.plusSeconds(150), track)
        assertTrue(match is GeoMatch.Matched)
    }

    @Test
    fun `photo slightly before track start snaps to first point`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(100, 54.0, -1.0))
        val match = interpolator.match(t0.minusSeconds(30), track) as GeoMatch.Matched
        assertEquals(53.0, match.latitude, 1e-9)
        assertEquals(Duration.ofSeconds(30), match.nearestPointOffset)
    }

    @Test
    fun `photo far before track start is outside track`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(100, 54.0, -1.0))
        val match = interpolator.match(t0.minusSeconds(3600), track)
        assertTrue(match is GeoMatch.OutsideTrack)
        assertEquals(
            Duration.ofSeconds(3600),
            (match as GeoMatch.OutsideTrack).nearestPointOffset
        )
    }

    @Test
    fun `photo slightly after track end snaps to last point`() {
        val track = listOf(pt(0, 53.0, -2.0), pt(100, 54.0, -1.0))
        val match = interpolator.match(t0.plusSeconds(130), track) as GeoMatch.Matched
        assertEquals(54.0, match.latitude, 1e-9)
    }

    @Test
    fun `empty track is outside track`() {
        val match = interpolator.match(t0, emptyList())
        assertTrue(match is GeoMatch.OutsideTrack)
    }

    @Test
    fun `single point track snaps within tolerance`() {
        val track = listOf(pt(0, 53.0, -2.0))
        val match = interpolator.match(t0.plusSeconds(10), track) as GeoMatch.Matched
        assertEquals(53.0, match.latitude, 1e-9)
    }

    @Test
    fun `longitude interpolation crosses antimeridian the short way`() {
        val track = listOf(pt(0, 0.0, 179.5), pt(100, 0.0, -179.5))
        val match = interpolator.match(t0.plusSeconds(50), track) as GeoMatch.Matched
        assertEquals(180.0, kotlin.math.abs(match.longitude), 1e-9)

        val quarter = interpolator.match(t0.plusSeconds(25), track) as GeoMatch.Matched
        assertEquals(179.75, quarter.longitude, 1e-9)
    }

    @Test
    fun `irregular cadence track matches within dense section`() {
        // Mirrors real Olympus GPSLOG cadence: 5s gaps then a multi-minute gap.
        val track = listOf(
            pt(0, 53.121, -2.074),
            pt(5, 53.122, -2.075),
            pt(10, 53.123, -2.076),
            pt(400, 53.200, -2.100)
        )
        val dense = interpolator.match(t0.plusSeconds(7), track)
        assertTrue(dense is GeoMatch.Matched)

        val sparse = interpolator.match(t0.plusSeconds(200), track)
        assertTrue(sparse is GeoMatch.GapTooLarge)
    }
}
