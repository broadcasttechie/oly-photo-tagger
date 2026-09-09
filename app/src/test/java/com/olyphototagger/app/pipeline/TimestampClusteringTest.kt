package com.olyphototagger.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TimestampClusteringTest {

    @Test
    fun `empty input produces no clusters`() {
        assertTrue(TimestampClustering.cluster(emptyList()).isEmpty())
    }

    @Test
    fun `a single timestamp is its own cluster`() {
        val t = Instant.parse("2026-06-01T10:00:00Z")
        assertEquals(listOf(listOf(t)), TimestampClustering.cluster(listOf(t)))
    }

    @Test
    fun `timestamps within the gap merge into one cluster`() {
        val t1 = Instant.parse("2026-06-01T10:00:00Z")
        val t2 = Instant.parse("2026-06-01T14:00:00Z")
        val t3 = Instant.parse("2026-06-01T15:59:00Z")

        val clusters = TimestampClustering.cluster(listOf(t1, t2, t3), gap = Duration.ofHours(6))

        assertEquals(listOf(listOf(t1, t2, t3)), clusters)
    }

    @Test
    fun `a gap wider than the threshold starts a new cluster`() {
        val t1 = Instant.parse("2026-06-01T10:00:00Z")
        val t2 = Instant.parse("2026-08-20T14:00:00Z") // the user's own reported example

        val clusters = TimestampClustering.cluster(listOf(t1, t2), gap = Duration.ofHours(6))

        assertEquals(listOf(listOf(t1), listOf(t2)), clusters)
    }

    @Test
    fun `a gap exactly equal to the threshold does not split`() {
        val t1 = Instant.parse("2026-06-01T10:00:00Z")
        val t2 = t1.plus(Duration.ofHours(6))

        val clusters = TimestampClustering.cluster(listOf(t1, t2), gap = Duration.ofHours(6))

        assertEquals(listOf(listOf(t1, t2)), clusters)
    }

    @Test
    fun `input order does not matter, and each cluster comes back sorted`() {
        val t1 = Instant.parse("2026-06-01T10:00:00Z")
        val t2 = Instant.parse("2026-06-01T11:00:00Z")
        val t3 = Instant.parse("2026-06-01T12:00:00Z")

        val clusters = TimestampClustering.cluster(listOf(t3, t1, t2), gap = Duration.ofHours(6))

        assertEquals(listOf(listOf(t1, t2, t3)), clusters)
    }

    @Test
    fun `three sessions with real gaps produce three separate clusters in chronological order`() {
        val session1 = listOf("2026-06-01T09:00:00Z", "2026-06-01T09:05:00Z").map(Instant::parse)
        val session2 = listOf("2026-06-01T20:00:00Z").map(Instant::parse) // same day, but an 11h gap
        val session3 = listOf("2026-08-20T14:00:00Z").map(Instant::parse)
        val shuffled = (session3 + session1 + session2)

        val clusters = TimestampClustering.cluster(shuffled, gap = Duration.ofHours(6))

        assertEquals(listOf(session1, session2, session3), clusters)
    }
}
