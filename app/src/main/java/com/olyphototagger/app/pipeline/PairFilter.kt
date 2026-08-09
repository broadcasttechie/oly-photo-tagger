package com.olyphototagger.app.pipeline

import java.time.Instant

sealed interface PairDecision {
    data class Include(val timestamp: Instant) : PairDecision
    data object ExcludeAlreadyTagged : PairDecision
    data object ExcludeNoTimestamp : PairDecision
    data object ExcludeOutsideDateRange : PairDecision
}

data class PairGeoStatus(val hasExistingGeoTag: Boolean, val timestamp: Instant?)

/**
 * Pure decision logic for which pairs a scan includes, separated from
 * GeotagOrchestrator's I/O so the actual business rules are unit-testable.
 *
 * Main workflow: already-tagged pairs are skipped ([PairDecision.ExcludeAlreadyTagged]).
 * The stretch "tag reviewer" goal would set includeAlreadyTagged=true to surface them
 * instead, with a date range so a whole card isn't reviewed at once.
 */
object PairFilter {
    fun decide(
        status: PairGeoStatus,
        includeAlreadyTagged: Boolean,
        dateRange: ClosedRange<Instant>?
    ): PairDecision {
        if (status.hasExistingGeoTag && !includeAlreadyTagged) return PairDecision.ExcludeAlreadyTagged
        val timestamp = status.timestamp ?: return PairDecision.ExcludeNoTimestamp
        if (dateRange != null && timestamp !in dateRange) return PairDecision.ExcludeOutsideDateRange
        return PairDecision.Include(timestamp)
    }
}
