package com.olyphototagger.app.ui.workflow

import android.net.Uri
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.pipeline.PairWriteResult
import com.olyphototagger.app.pipeline.PreScanSummary
import com.olyphototagger.app.pipeline.ScanResult
import com.olyphototagger.app.write.IncompleteWrite
import com.olyphototagger.app.write.IncompleteWriteScanResult
import java.time.Instant

/** [currentAction] reports the most recently *completed* pair, not one currently in
 *  progress — writes run several at a time (see [GeotagOrchestrator.applyMatches]), so
 *  "the current one" wouldn't mean anything once more than one is in flight at once.
 *  [startedAt] is when the whole run began, carried unchanged on every update — an ETA
 *  needs the real elapsed time since the batch started, not just since the screen showing
 *  it was opened (the write survives navigation, see [GeotagWorkflowViewModel.startRun]). */
data class RunProgress(val completed: Int, val total: Int, val currentAction: String, val startedAt: Instant)

/**
 * All state for the Home -> dry-run -> progress -> summary journey, in one place since
 * it's genuinely one continuous workflow with accumulating state — not four independent
 * screens. Navigation between screens is driven by the UI layer (on a successful action,
 * the caller navigates); this state doesn't know which screen is currently showing.
 *
 * Errors are deliberately NOT part of this state — see [GeotagWorkflowViewModel.events].
 * A one-shot notification like "this just failed" doesn't belong in a StateFlow: two
 * failures with the same message in a row (state goes message -> null -> same message)
 * can conflate into what looks like no change at all to a collector, silently dropping
 * the second notification.
 */
data class WorkflowUiState(
    val rootUri: Uri? = null,
    val rootDisplayName: String? = null,
    /** Interrupted writes found on the current root, awaiting an explicit recovery
     *  choice — see IncompleteWriteScanner. Empty in the common case (no crash ever
     *  happened). Dismissible by design: nothing here blocks the rest of the workflow,
     *  since GpsExifWriter itself refuses to touch a file with an unresolved backup
     *  regardless of whether this was ever seen or actioned. */
    val pendingRecoveries: List<IncompleteWrite> = emptyList(),
    /** Needed to resolve a pendingRecoveries item back to real files — see
     *  IncompleteWriteScanner.resolve(). Null until checkForIncompleteWrites() has run. */
    val incompleteWriteScanResult: IncompleteWriteScanResult? = null,
    val cameraOffsetSeconds: Int = 0,
    val dateRangeStart: Instant? = null,
    val dateRangeEnd: Instant? = null,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val preScanSummary: PreScanSummary? = null,
    val scanResult: ScanResult? = null,
    /** Pairs the user has explicitly excluded from the upcoming write, keyed by
     *  [PhotoPair.stableKey] — opt-out rather than opt-in, so everything a scan found
     *  eligible is selected by default and this stays empty in the common case. */
    val deselectedPairKeys: Set<String> = emptySet(),
    val runProgress: RunProgress? = null,
    val runResults: List<PairWriteResult>? = null
) {
    val canScan: Boolean get() = rootUri != null && !isBusy
}

/** Stable identity for a pair within one scan — folder + base name together, since base
 *  names alone could collide across two DCIM folders on the same card. */
fun PhotoPair.stableKey(): String = "$folderName/$baseName"
