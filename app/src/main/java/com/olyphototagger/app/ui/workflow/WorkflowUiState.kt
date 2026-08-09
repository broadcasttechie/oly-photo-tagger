package com.olyphototagger.app.ui.workflow

import android.net.Uri
import com.olyphototagger.app.pipeline.PairWriteResult
import com.olyphototagger.app.pipeline.PreScanSummary
import com.olyphototagger.app.pipeline.ScanResult
import java.time.Instant

data class RunProgress(val completed: Int, val total: Int, val currentAction: String)

/**
 * All state for the Home -> dry-run -> progress -> summary journey, in one place since
 * it's genuinely one continuous workflow with accumulating state — not four independent
 * screens. Navigation between screens is driven by the UI layer (on a successful action,
 * the caller navigates); this state doesn't know which screen is currently showing.
 */
data class WorkflowUiState(
    val rootUri: Uri? = null,
    val rootDisplayName: String? = null,
    val cameraOffsetSeconds: Int = 0,
    val dateRangeStart: Instant? = null,
    val dateRangeEnd: Instant? = null,
    val isBusy: Boolean = false,
    val busyMessage: String? = null,
    val errorMessage: String? = null,
    val preScanSummary: PreScanSummary? = null,
    val scanResult: ScanResult? = null,
    val runProgress: RunProgress? = null,
    val runResults: List<PairWriteResult>? = null
) {
    val canScan: Boolean get() = rootUri != null && !isBusy
}
