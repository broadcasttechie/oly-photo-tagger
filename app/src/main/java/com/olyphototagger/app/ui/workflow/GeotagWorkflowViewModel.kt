package com.olyphototagger.app.ui.workflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olyphototagger.app.cache.AppDatabase
import com.olyphototagger.app.dawarich.DawarichClient
import com.olyphototagger.app.dawarich.createDawarichHttpClient
import com.olyphototagger.app.dcim.DcimScanner
import com.olyphototagger.app.exif.PhotoExifStatusReader
import com.olyphototagger.app.exiftool.ExifToolInvoker
import com.olyphototagger.app.geotag.GeoInterpolator
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.geotag.GpsSource
import com.olyphototagger.app.gpx.GpxTrackSource
import com.olyphototagger.app.pipeline.GeotagOrchestrator
import com.olyphototagger.app.pipeline.PairWriteResult
import com.olyphototagger.app.settings.ActiveGpsSourceResolver
import com.olyphototagger.app.settings.GpsSourceType
import com.olyphototagger.app.settings.SettingsRepository
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.write.GpsExifWriter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

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
    private val dcimScanner = DcimScanner()
    private val exifStatusReader = PhotoExifStatusReader(context.contentResolver)
    private val gpsExifWriter = GpsExifWriter(
        context.contentResolver,
        ExifToolInvoker(context),
        context.cacheDir
    )

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
                runResults = null
            )
        }
        viewModelScope.launch { settingsRepository.saveLastDcimRootUri(uri.toString()) }
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

    suspend fun runPreScan(): Boolean {
        val root = _uiState.value.rootUri ?: return false
        _uiState.update {
            it.copy(isBusy = true, busyMessage = "Scanning for photos missing GPS tags…")
        }
        val orchestrator = buildOrchestrator()
        if (orchestrator == null) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            _events.tryEmit(MISSING_GPS_SOURCE_MESSAGE)
            return false
        }
        return try {
            val dcimRoot = requireNotNull(DocumentFile.fromTreeUri(context, root)) { "Could not open $root" }
            val summary = orchestrator.preScan(dcimRoot, currentOffset(), currentDateRange())
            _uiState.update { it.copy(isBusy = false, busyMessage = null, preScanSummary = summary) }
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            _events.tryEmit("Prescan failed: ${e.message}")
            false
        }
    }

    suspend fun runDryScan(): Boolean {
        val root = _uiState.value.rootUri ?: return false
        _uiState.update {
            it.copy(isBusy = true, busyMessage = "Matching photos against your GPS track…")
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
            val result = orchestrator.scanForMatches(dcimRoot, currentOffset(), currentDateRange())
            _uiState.update {
                it.copy(isBusy = false, busyMessage = null, scanResult = result, deselectedPairKeys = emptySet())
            }
            true
        } catch (e: Exception) {
            _uiState.update { it.copy(isBusy = false, busyMessage = null) }
            _events.tryEmit("Scan failed: ${e.message}")
            false
        }
    }

    /**
     * Writes every matched pair from the last dry-run scan. Only ever call this in
     * response to an explicit user confirmation on the dry-run screen — this is the one
     * function in the whole app that touches the original photos.
     *
     * Launches in viewModelScope rather than being a plain suspend function the caller
     * awaits directly: the caller navigates to the progress screen right after starting
     * this, and a screen-scoped coroutine (rememberCoroutineScope()) would be cancelled
     * by that navigation, aborting the write batch mid-flight. This must survive
     * navigation regardless of what the UI does next.
     */
    fun startRun() {
        if (_uiState.value.runProgress != null) return // already running
        viewModelScope.launch {
            val scanResult = _uiState.value.scanResult
            if (scanResult == null) {
                _events.tryEmit("Nothing to run — no dry-run scan yet.")
                return@launch
            }
            val orchestrator = buildOrchestrator()
            if (orchestrator == null) {
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
            _uiState.update {
                it.copy(runProgress = RunProgress(0, matches.size, "Starting…"), runResults = null)
            }

            val results = mutableListOf<PairWriteResult>()
            try {
                for ((index, match) in matches.withIndex()) {
                    val label = match.pair.baseName
                    _uiState.update { it.copy(runProgress = RunProgress(index, matches.size, "Writing GPS to $label…")) }
                    results += orchestrator.applyMatch(scanResult, match)
                    _uiState.update { it.copy(runProgress = RunProgress(index + 1, matches.size, "Wrote $label")) }
                }
            } finally {
                // Always land on a definite result, even if something threw partway
                // through — an indefinitely "in progress" state would leave the user
                // unable to tell whether their photos were actually touched.
                _uiState.update { it.copy(runProgress = null, runResults = results) }
            }
        }
    }

    private fun currentLocalOffsetSeconds(): Int = ZonedDateTime.now().offset.totalSeconds

    private fun currentOffset(): ZoneOffset = ZoneOffset.ofTotalSeconds(_uiState.value.cameraOffsetSeconds)

    private fun currentDateRange(): ClosedRange<Instant>? {
        val start = _uiState.value.dateRangeStart
        val end = _uiState.value.dateRangeEnd
        return if (start != null && end != null) start..end else null
    }

    private suspend fun buildOrchestrator(): GeotagOrchestrator? {
        val dawarichConfig = settingsRepository.dawarichConfig.first()
        val activeSource = settingsRepository.activeGpsSource.first()
        val resolved = ActiveGpsSourceResolver.resolve(activeSource, hasDawarichConfig = dawarichConfig != null)
            ?: return null
        val gpsSource: GpsSource = when (resolved) {
            GpsSourceType.DAWARICH -> DawarichClient(
                createDawarichHttpClient(),
                requireNotNull(dawarichConfig).baseUrl,
                dawarichConfig.apiToken
            )
            GpsSourceType.GPX -> GpxTrackSource(AppDatabase.getInstance(context).gpxTrackDao())
        }
        val gapMinutes = settingsRepository.gapThresholdMinutes.first()
        return GeotagOrchestrator(
            dcimScanner = dcimScanner,
            exifStatusReader = exifStatusReader,
            geoTagCacheDao = AppDatabase.getInstance(context).geoTagCacheDao(),
            gpsSource = gpsSource,
            geoInterpolator = GeoInterpolator(maxBracketGap = Duration.ofMinutes(gapMinutes.toLong())),
            gpsExifWriter = gpsExifWriter
        )
    }

    companion object {
        private const val SECONDS_PER_HOUR = 3600

        // internal, not private: HomeScreen matches on this exact message to offer a
        // "Settings" action on the error snackbar rather than just a generic dismiss.
        internal const val MISSING_GPS_SOURCE_MESSAGE = "Set up a GPS source in Settings first."
    }
}
