package com.olyphototagger.app.ui.workflow

import java.time.Duration

/** Shared by [ProgressScreen] (estimated time remaining) and [SummaryScreen] (elapsed time). */
fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds.coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
