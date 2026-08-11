package com.olyphototagger.app.ui.workflow

import java.time.Duration
import java.time.Instant

/** Shared by [ProgressScreen] (estimated time remaining) and [SummaryScreen] (elapsed time). */
fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds.coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

/**
 * Projects remaining duration from the observed rate so far (elapsed since [startedAt],
 * divided by [completed], times whatever's left) rather than any fixed per-item
 * assumption — actual per-item cost varies too much (file size/format for writes, cache
 * hits/misses for scans) for a hardcoded guess to be honest. Null before the first
 * completion, when there's no rate yet to project from. Shared by [ProgressScreen]
 * (write batches) and [HomeScreen] (the prescan's per-pair status checks).
 */
fun estimateRemaining(completed: Int, total: Int, startedAt: Instant): Duration? {
    if (completed <= 0) return null
    val remaining = total - completed
    if (remaining <= 0) return Duration.ZERO
    val elapsed = Duration.between(startedAt, Instant.now())
    return elapsed.dividedBy(completed.toLong()).multipliedBy(remaining.toLong())
}
