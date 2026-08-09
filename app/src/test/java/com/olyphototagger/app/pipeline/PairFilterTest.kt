package com.olyphototagger.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PairFilterTest {

    private val t = Instant.parse("2026-08-09T12:00:00Z")

    @Test
    fun `already tagged is excluded by default`() {
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = true, timestamp = t),
            includeAlreadyTagged = false,
            dateRange = null
        )
        assertEquals(PairDecision.ExcludeAlreadyTagged, decision)
    }

    @Test
    fun `already tagged is included when reviewer mode requests it`() {
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = true, timestamp = t),
            includeAlreadyTagged = true,
            dateRange = null
        )
        assertEquals(PairDecision.Include(t), decision)
    }

    @Test
    fun `missing timestamp is excluded regardless of tag status`() {
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = false, timestamp = null),
            includeAlreadyTagged = false,
            dateRange = null
        )
        assertEquals(PairDecision.ExcludeNoTimestamp, decision)
    }

    @Test
    fun `not tagged and no date range is included`() {
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = false, timestamp = t),
            includeAlreadyTagged = false,
            dateRange = null
        )
        assertEquals(PairDecision.Include(t), decision)
    }

    @Test
    fun `timestamp outside the date range is excluded`() {
        val range = Instant.parse("2026-08-01T00:00:00Z")..Instant.parse("2026-08-05T00:00:00Z")
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = false, timestamp = t),
            includeAlreadyTagged = false,
            dateRange = range
        )
        assertEquals(PairDecision.ExcludeOutsideDateRange, decision)
    }

    @Test
    fun `timestamp on the date range boundary is included`() {
        val range = Instant.parse("2026-08-01T00:00:00Z")..t
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = false, timestamp = t),
            includeAlreadyTagged = false,
            dateRange = range
        )
        assertTrue(decision is PairDecision.Include)
    }

    @Test
    fun `already-tagged check happens before the date range check`() {
        // Excluding as "already tagged" rather than "outside range" is the more useful
        // signal to surface — this is what a UI would report back to the user.
        val range = Instant.parse("2020-01-01T00:00:00Z")..Instant.parse("2020-01-02T00:00:00Z")
        val decision = PairFilter.decide(
            status = PairGeoStatus(hasExistingGeoTag = true, timestamp = t),
            includeAlreadyTagged = false,
            dateRange = range
        )
        assertEquals(PairDecision.ExcludeAlreadyTagged, decision)
    }
}
