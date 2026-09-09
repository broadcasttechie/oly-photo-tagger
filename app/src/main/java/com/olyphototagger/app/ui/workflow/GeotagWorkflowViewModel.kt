package com.olyphototagger.app.ui.workflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olyphototagger.app.dcim.DcimScanner
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.pipeline.GeotagOrchestrator
import com.olyphototagger.app.pipeline.buildGeotagOrchestrator
import com.olyphototagger.app.settings.SettingsRepository
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.service.WriteService
import com.olyphototagger.app.write.IncompleteWrite
import com.olyphototagger.app.write.IncompleteWriteClassification
import com.olyphototagger.app.write.IncompleteWriteScanner
import com.olyphototagger.app.write.RecoveryActionResult
import com.olyphototagger.app.write.RecoveryChoice
import com.olyphototagger.app.write.RecoveryOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * State + actions for the whole Home -> dry-run -> progress -> summary journey. No DI
 * framework in this project (matches the rest of the codebase's manual-wiring style —
 * see AssetExtractor/ExifToolInvoker/GpsExifWriter), so dependencies needing live
 * settings (Dawarich config, gap threshold) are (re)built fresh from current DataStore
 * values at the point they're used, rather than constructed once and risking staleness
 * if the user visits Settings mid-workflow.
 */
class GeotagWorkflowViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val settingsRepository = SettingsRepository(context)
    private val dcimScanner = DcimScanner(context.contentResolver)
    private val incompleteWriteScanner = IncompleteWriteScanner(dcimScanner)

    private val _uiState = MutableStateFlow(WorkflowUiState())
    val uiState: StateFlow<WorkflowUiState> = _uiState.asStateFlow()

    // One-shot user-facing messages (errors, mainly) — a SharedFlow rather than part of
    // WorkflowUiState because StateFlow only guarantees collectors see the latest value,
    // not every distinct one. Two failures with the same text in a row can conflate away
    // the second notification if a collector is a beat slow (see WorkflowUiState's doc).
    // extraBufferCapacity + DROP_OLDEST keeps every emit() call here non-suspending.
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        // WriteService.status is a process-wide StateFlow (see its own doc) — it replays its
        // current value to a new collector, so this correctly resumes showing live or
        // just-finished progress even for a ViewModel constructed fresh after the Activity
        // was recreated mid-batch, with no extra reattachment logic needed.
        viewModelScope.launch {
            WriteService.status.collect { status ->
                when (status) {
                    WriteService.Status.Idle -> Unit
                    is WriteService.Status.Running -> _uiState.update { it.copy(runProgress = status.progress) }
                    is WriteService.Status.Finished -> _uiState.update {
                        it.copy(runProgress = null, runResults = status.results, runDuration = status.duration)
                    }
                }
            }
        }
        viewModelScope.launch {
            val savedOffset = settingsRepository.lastCameraOffsetSeconds.first()
            _uiState.update { it.copy(cameraOffsetSeconds = savedOffset ?: currentLocalOffsetSeconds()) }

            settingsRepository.lastDcimRootUri.first()?.let { savedRoot ->
                val uri = Uri.parse(savedRoot)
                // Only offer it back if we still actually hold the permission grant — a
                // revoked/stale grant would otherwise fail confusingly later, mid-scan.
                val stillGranted = context.contentResolver.persistedUriPermissions
                    .any { it.uri == uri && it.isReadPermission }
                if (stillGranted) {
                    val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                    _uiState.update { it.copy(rootUri = uri, rootDisplayName = name) }
                    // The scenario this whole feature exists for: the app got killed
                    // mid-write, and the user is reopening it — checked here, on cold
                    // start with the restored root, not just when a folder is freshly
                    // picked.
                    checkForIncompleteWrites()
                }
            }
        }
    }

    fun setRoot(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        _uiState.update {
            it.copy(
                rootUri = uri,
                rootDisplayName = name,
                preScanSummary = null,
                scanResult = null,
                deselectedPairKeys = emptySet(),
                runResults = null,
                // Stale results from whatever root was previously selected — a fresh
                // check below will repopulate this for the new root.
                pendingRecoveries = emptyList(),
                incompleteWriteScanResult = null
            )
        }
        viewModelScope.launch { settingsRepository.saveLastDcimRootUri(uri.toString()) }
        viewModelScope.launch { checkForIncompleteWrites() }
    }

    fun loadLocalOffset() = setCameraOffsetSeconds(currentLocalOffsetSeconds())

    fun adjustOffsetHours(deltaHours: Int) =
        setCameraOffsetSeconds(_uiState.value.cameraOffsetSeconds + deltaHours * SECONDS_PER_HOUR)

    fun setCameraOffsetSeconds(seconds: Int) {
        // A prior scan result was computed against the old offset — showing it as if
        // still valid after the offset changes would misrepresent what a real run
        // would actually do to the photos.
        _uiState.update {
            it.copy(cameraOffsetSeconds = seconds, preScanSummary = null, scanResult = null, deselectedPairKeys = emptySet())
        }
    }

    fun setDateRange(start: Instant?, end: Instant?) {
        _uiState.update {
            it.copy(
                dateRangeStart = start,
                dateRangeEnd = end,
                preScanSummary = null,
                scanResult = null,
                deselectedPairKeys = emptySet()
            )
        }
    }

    /** Clears results from a previous run so the workflow can start over on the same root. */
    fun resetForNextRun() {
        // Without this, WriteService.status (a StateFlow that replays its latest value) would
        // hand a much-later, unrelated fresh ViewModel this same already-acknowledged Finished
        // result the instant it starts collecting.
        WriteService.resetIfFinished()
        _uiState.update {
            it.copy(
                preScanSummary = null,
                scanResult = null,
                deselectedPairKeys = emptySet(),
                runProgress = null,
                runResults = null
            )
        }
    }

    /** Toggles whether a matched pair is excluded from the next [startRun] — the dry-run
     *  screen's per-photo checkbox. */
    fun toggleMatchSelection(pair: PhotoPair) {
        val key = pair.stableKey()
        _uiState.update {
            val deselected = it.deselectedPairKeys
            it.copy(deselectedPairKeys = if (key in deselected) deselected - key else deselected + key)
        }
    }

    /** Bulk "select all" / "deselect all" for the dry-run screen's header toggle. */
    fun setAllMatchesSelected(selected: Boolean) {
        val matchedKeys = _uiState.value.scanResult?.matches
            ?.filter { it.geoMatch is GeoMatch.Matched }
            ?.map { it.pair.stableKey() }
            .orEmpty()
        _uiState.update { it.copy(deselectedPairKeys = if (selected) emptySet() else matchedKeys.toSet()) }
    }

    /**
     * Checks the current root for artifacts an earlier interrupted write left behind —
     * see [IncompleteWriteScanner]. Cheap (a folder listing + pure classification, no
     * network/exiftool involved) so the busy flash is brief, but still uses the same
     * busy-state pattern as [runPreScan]/[runDryScan] for consistency.
     */
    suspend fun checkForIncompleteWrites() {
        val root = _uiState.value.rootUri ?: return
        _uiState.update { it.copy(isBusy = true, busyMessage = "Checking for interrupted writes…") }
        try {
            val dcimRoot = requireNotNull(DocumentFile.fromTreeUri(context, root)) { "Could not open $root" }
            val result = incompleteWriteScanner.scan(dcimRoot)
            _uiState.update {
                it.copy(isBusy = false, busyMessage = null, pendingRecoveries = result.items, incompleteWriteScanResult = result)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            _events.tryEmit("Could not check for interrupted writes: ${e.message}")
        }
    }

    /** Applies [choice] to [item], resolving it, and removes it from [WorkflowUiState.pendingRecoveries] on success. */
    suspend fun resolveIncompleteWrite(item: IncompleteWrite, choice: RecoveryChoice) {
        val scanResult = _uiState.value.incompleteWriteScanResult
        if (scanResult == null) {
            _events.tryEmit("Could not resolve ${item.recoveredName}: no scan result to resolve it against.")
            return
        }
        when (val result = incompleteWriteScanner.resolve(scanResult, item, choice)) {
            RecoveryActionResult.Recovered -> {
                _uiState.update { it.copy(pendingRecoveries = it.pendingRecoveries - item) }
                _events.tryEmit("Resolved ${item.recoveredName}")
            }
            is RecoveryActionResult.ActionFailed -> _events.tryEmit("Could not resolve ${item.recoveredName}: ${result.reason}")
        }
    }

    /**
     * Resolves every current [WorkflowUiState.pendingRecoveries] item with [classification]
     * at once, using its one unambiguous [RecoveryOptions.unambiguousChoiceFor] choice —
     * found necessary the same real-device session that found the recovery flow itself: a
     * full SD card left over 200 stray temp files behind, all the same trivial
     * StaleTempOnly case, and resolving those one tap at a time doesn't scale. The
     * `require` is a genuine invariant, not defensive noise — the Recovery screen must
     * only ever offer this for classifications it already confirmed are single-choice
     * (see [RecoveryScreen]), so reaching the else branch would mean the UI let through a
     * classification with a real decision to make, silently picking one on the user's
     * behalf.
     *
     * Bounded concurrency for the same reason [GeotagOrchestrator.applyMatches] bounds its
     * own writes — hundreds of simultaneous SAF calls would just queue up behind the OS's
     * binder thread pool anyway.
     */
    suspend fun resolveAllUnambiguous(classification: IncompleteWriteClassification) {
        val choice = requireNotNull(RecoveryOptions.unambiguousChoiceFor(classification)) {
            "resolveAllUnambiguous called for $classification, which doesn't have exactly one choice"
        }
        val scanResult = _uiState.value.incompleteWriteScanResult
        if (scanResult == null) {
            _events.tryEmit("Could not resolve: no scan result to resolve against.")
            return
        }
        val items = _uiState.value.pendingRecoveries.filter { it.classification == classification }
        if (items.isEmpty()) return

        // Plain vars would race: several of these run concurrently (bounded by the
        // semaphore below), so a lost update here would under-report a real success/failure.
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_RECOVERY_ACTIONS)
            items.map { item ->
                async {
                    val result = semaphore.withPermit { incompleteWriteScanner.resolve(scanResult, item, choice) }
                    when (result) {
                        RecoveryActionResult.Recovered -> {
                            _uiState.update { it.copy(pendingRecoveries = it.pendingRecoveries - item) }
                            succeeded.incrementAndGet()
                        }
                        is RecoveryActionResult.ActionFailed -> failed.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        _events.tryEmit(
            if (failed.get() == 0) {
                "Resolved ${succeeded.get()} item${if (succeeded.get() == 1) "" else "s"}"
            } else {
                "Resolved ${succeeded.get()} item${if (succeeded.get() == 1) "" else "s"}, ${failed.get()} failed — see the list below"
            }
        )
    }

    suspend fun runPreScan(): Boolean {
        val root = _uiState.value.rootUri ?: return false
        val startedAt = Instant.now()
        _uiState.update {
            it.copy(isBusy = true, busyMessage = "Scanning for photos missing GPS tags…", scanProgress = null)
        }
        val orchestrator = buildOrchestrator()
        if (orchestrator == null) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            _events.tryEmit(MISSING_GPS_SOURCE_MESSAGE)
            return false
        }
        return try {
            val dcimRoot = requireNotNull(DocumentFile.fromTreeUri(context, root)) { "Could not open $root" }
            val summary = orchestrator.preScan(dcimRoot, currentOffset(), currentDateRange()) { completed, total ->
                _uiState.update { it.copy(scanProgress = ScanProgress(completed, total, startedAt)) }
            }
            _uiState.update { it.copy(isBusy = false, busyMessage = null, scanProgress = null, preScanSummary = summary) }
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null, scanProgress = null) }
            _events.tryEmit("Prescan failed: ${e.message}")
            false
        }
    }

    suspend fun runDryScan(): Boolean {
        val root = _uiState.value.rootUri ?: return false
        val startedAt = Instant.now()
        _uiState.update {
            it.copy(isBusy = true, busyMessage = "Matching photos against your GPS track…", scanProgress = null)
        }
        val orchestrator = buildOrchestrator()
        if (orchestrator == null) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            _events.tryEmit(MISSING_GPS_SOURCE_MESSAGE)
            return false
        }
        settingsRepository.saveLastCameraOffsetSeconds(_uiState.value.cameraOffsetSeconds)
        return try {
            val dcimRoot = requireNotNull(DocumentFile.fromTreeUri(context, root)) { "Could not open $root" }
            // Same per-pair progress plumbing as runPreScan() — this is the path an actual
            // folder-pick -> Dry Run normally takes, so it needs live feedback just as much
            // as the optional "check for untagged" button does, especially since this is
            // the phase real-device testing found could run long over USB.
            val result = orchestrator.scanForMatches(dcimRoot, currentOffset(), currentDateRange()) { completed, total ->
                _uiState.update { it.copy(scanProgress = ScanProgress(completed, total, startedAt)) }
            }
            _uiState.update {
                it.copy(isBusy = false, busyMessage = null, scanProgress = null, scanResult = result, deselectedPairKeys = emptySet())
            }
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null, scanProgress = null) }
            _events.tryEmit("Scan failed: ${e.message}")
            false
        }
    }

    /**
     * Writes every matched pair from the last dry-run scan. Only ever call this in
     * response to an explicit user confirmation on the dry-run screen — this is the one
     * function in the whole app that touches the original photos.
     *
     * Dispatches to [WriteService] rather than running the batch itself: a real batch can run
     * 10-15+ minutes (confirmed on real hardware), and viewModelScope survives in-app
     * navigation (Progress -> Summary) but not the app being merely backgrounded — nothing
     * stops the OS reclaiming the whole process once the Activity is stopped. A foreground
     * service is the only thing that actually protects this. [WriteService.status] is
     * collected back into [_uiState] from [init], so this function's own job just needs to
     * validate, compute the batch, and hand it off.
     */
    fun startRun() {
        if (_uiState.value.runProgress != null) return // already running
        viewModelScope.launch {
            val scanResult = _uiState.value.scanResult
            if (scanResult == null) {
                _events.tryEmit("Nothing to run — no dry-run scan yet.")
                return@launch
            }
            if (buildOrchestrator() == null) { // pre-flight only — WriteService builds its own
                _events.tryEmit(MISSING_GPS_SOURCE_MESSAGE)
                return@launch
            }

            // scanResult.matches can include GapTooLarge/OutsideTrack entries — a
            // candidate clearing the already-tagged/timestamp/date-range checks doesn't
            // mean the interpolator actually found a confident position for it.
            // applyMatch() requires Matched and throws otherwise; only ever pass it
            // entries that actually cleared that bar. Anything else is exactly what
            // "skip and flag rather than silently interpolate across a gap" means — it's
            // never written, matched or not. A pair the user explicitly unchecked on the
            // dry-run screen is excluded the same way, regardless of how it matched.
            val deselected = _uiState.value.deselectedPairKeys
            val matches = scanResult.matches.filter {
                it.geoMatch is GeoMatch.Matched && it.pair.stableKey() !in deselected
            }
            val startedAt = Instant.now()
            // Shown immediately, before WriteService has posted anything of its own — the
            // user taps through to the progress screen and should see it's actually started.
            _uiState.update {
                it.copy(runProgress = RunProgress(0, matches.size, "Starting…", startedAt), runResults = null)
            }
            WriteService.start(context, scanResult, matches, startedAt)
        }
    }

    private fun currentLocalOffsetSeconds(): Int = ZonedDateTime.now().offset.totalSeconds

    private fun currentOffset(): ZoneOffset = ZoneOffset.ofTotalSeconds(_uiState.value.cameraOffsetSeconds)

    private fun currentDateRange(): ClosedRange<Instant>? {
        val start = _uiState.value.dateRangeStart
        val end = _uiState.value.dateRangeEnd
        return if (start != null && end != null) start..end else null
    }

    // Delegates to the top-level factory (also used by WriteService) so there's exactly one
    // copy of the settings/DAO/GPS-source wiring — see its own doc for why.
    private suspend fun buildOrchestrator(): GeotagOrchestrator? = buildGeotagOrchestrator(context)

    companion object {
        private const val SECONDS_PER_HOUR = 3600

        // internal, not private: HomeScreen matches on this exact message to offer a
        // "Settings" action on the error snackbar rather than just a generic dismiss.
        internal const val MISSING_GPS_SOURCE_MESSAGE = "Set up a GPS source in Settings first."

        /** Bounds concurrent SAF calls in [resolveAllUnambiguous] — each one is a plain
         *  rename/delete, much lighter than a write, but hundreds at once would still just
         *  queue up behind the OS's own binder thread pool for no real gain over a bounded
         *  number running at a time. */
        private const val MAX_CONCURRENT_RECOVERY_ACTIONS = 8
    }
}
