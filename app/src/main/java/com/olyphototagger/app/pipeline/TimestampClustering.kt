package com.olyphototagger.app.pipeline

import java.time.Duration
import java.time.Instant

/**
 * Groups timestamps into clusters, splitting wherever consecutive timestamps (by time) are
 * more than [gap] apart — the "is there a real break in activity here" question behind
 * [GeotagOrchestrator.fetchClusteredTrack]'s per-cluster GPS-track fetching, kept as its own
 * pure function so the clustering decision itself is unit-testable without a GpsSource.
 */
object TimestampClustering {

    /** A day's worth of shooting can have a real multi-hour gap in the middle (lunch, an
     *  indoor exhibit, a long drive) without that meaning "two separate outings" — 6 hours
     *  comfortably covers that while still splitting distinct days apart (an evening's last
     *  photo to the next morning's first is reliably a bigger gap than this). Not a
     *  scientifically derived number, just a reasonable balance between "don't fragment one
     *  day into many small fetches" and "don't span weeks of dead time in one fetch" — the
     *  actual problem this exists to fix (see fetchClusteredTrack's own doc). */
    val DEFAULT_GAP: Duration = Duration.ofHours(6)

    /** [timestamps] need not be pre-sorted. Each returned cluster is sorted ascending;
     *  clusters themselves are in chronological order. Empty input returns an empty list,
     *  never a list containing one empty cluster. */
    fun cluster(timestamps: List<Instant>, gap: Duration = DEFAULT_GAP): List<List<Instant>> {
        if (timestamps.isEmpty()) return emptyList()
        val sorted = timestamps.sorted()

        val clusters = mutableListOf<MutableList<Instant>>()
        var current = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            if (Duration.between(sorted[i - 1], sorted[i]) > gap) {
                clusters += current
                current = mutableListOf(sorted[i])
            } else {
                current += sorted[i]
            }
        }
        clusters += current
        return clusters
    }
}
