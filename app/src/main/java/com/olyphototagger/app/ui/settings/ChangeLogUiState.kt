package com.olyphototagger.app.ui.settings

import com.olyphototagger.app.cache.WriteLogResultType
import java.time.Instant

/** One row in the change log — a view-layer projection of [com.olyphototagger.app.cache.WriteLogEntity]. */
data class WriteLogEntryUiState(
    val id: Long,
    val loggedAt: Instant,
    val folderName: String,
    val displayName: String,
    val resultType: WriteLogResultType,
    val previousLatLong: Pair<Double, Double>?,
    val newLatLong: Pair<Double, Double>?,
    val newAltitudeMeters: Double?,
    val detail: String?
)

data class ChangeLogUiState(
    val entries: List<WriteLogEntryUiState> = emptyList(),
    /** Gates "Clear log" behind an explicit confirm — same principle as
     *  [com.olyphototagger.app.ui.workflow.RecoveryScreen]'s destructive-choice dialogs:
     *  this permanently discards history, so it isn't a single-tap action. */
    val pendingClearConfirmation: Boolean = false
)
