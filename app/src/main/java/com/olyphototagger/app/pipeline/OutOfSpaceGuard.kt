package com.olyphototagger.app.pipeline

import com.olyphototagger.app.write.GpsExifWriteResult

/**
 * Pure decision logic for [GeotagOrchestrator.applyMatches]'s fail-fast-on-no-space
 * behavior, separated from the orchestrator's I/O so the actual rule is unit-testable —
 * see that function's own doc for why grinding through every remaining file once storage
 * is confirmed full is worth avoiding.
 */
object OutOfSpaceGuard {

    /**
     * "ENOSPC" is the literal substring Android's own I/O layer puts in the exception
     * message for a real out-of-space write failure (confirmed against a real device,
     * 2026-08-11: `write failed: ENOSPC (No space left on device)`) — stable because it's
     * the actual POSIX errno name, not something this app controls or could drift from.
     */
    fun indicatesOutOfSpace(result: PairWriteResult): Boolean =
        listOfNotNull(result.jpegResult, result.rawResult).any {
            it is GpsExifWriteResult.Failed && it.reason.contains("ENOSPC", ignoreCase = true)
        }

    /** The result a match gets when it's skipped without ever being attempted. */
    fun skipped(match: ProposedMatch): PairWriteResult {
        val skippedResult = GpsExifWriteResult.Failed(
            "Skipped — an earlier file in this batch ran out of storage space, so the rest weren't attempted"
        )
        return PairWriteResult(
            pair = match.pair,
            jpegResult = match.pair.jpeg?.let { skippedResult },
            rawResult = match.pair.raw?.let { skippedResult }
        )
    }
}
