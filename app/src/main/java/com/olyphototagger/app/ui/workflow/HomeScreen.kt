package com.olyphototagger.app.ui.workflow

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olyphototagger.app.pipeline.PreScanSummary
import com.olyphototagger.app.pipeline.TrackFetchProgress
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: GeotagWorkflowViewModel,
    onNavigateToDryRun: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToGpsSources: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    onNavigateToChangeLog: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.setRoot(it) }
    }

    // So the write batch's WriteService notification (progress + when it's done) is
    // actually visible — never required for the batch itself to run or survive
    // backgrounding, only for the user to see it without opening the app. Requested here,
    // fired alongside the dry-run tap rather than the actual write-confirm tap on
    // DryRunScreen, so the OS dialog (if shown at all) is very likely already resolved by
    // the time the user reviews the preview and taps confirm — not fighting for attention
    // right when WriteService's first notification actually needs to post.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Errors were previously a Text buried at the bottom of a scrollable column — easy
    // to miss unless already scrolled down. A Snackbar floats above the content and
    // appears immediately regardless of scroll position. Collects viewModel.events (a
    // SharedFlow) rather than keying off a state field: two failures with the same
    // message in a row could otherwise conflate into what looks like no state change at
    // all and silently drop the second notification.
    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                // Deep-links straight to GPS Sources — that's where the actual fix
                // lives now that Dawarich's fields moved out of the general Settings
                // screen — rather than Settings, which no longer has anything to fix.
                actionLabel = if (message == GeotagWorkflowViewModel.MISSING_GPS_SOURCE_MESSAGE) "Fix" else null,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onNavigateToGpsSources()
        }
    }

    HomeScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onPickFolder = { pickFolder.launch(null) },
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToRecovery = onNavigateToRecovery,
        onNavigateToChangeLog = onNavigateToChangeLog,
        onPreScan = { scope.launch { viewModel.runPreScan() } },
        onCancelScan = viewModel::cancelScan,
        onDateRangeChange = viewModel::setDateRange,
        onLoadLocalOffset = viewModel::loadLocalOffset,
        onAdjustOffsetHours = viewModel::adjustOffsetHours,
        onSetOffsetSeconds = viewModel::setCameraOffsetSeconds,
        onDryRun = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            scope.launch { if (viewModel.runDryScan()) onNavigateToDryRun() }
        }
    )
}

/**
 * The actual Home screen UI, taking plain state and callbacks rather than the
 * ViewModel directly — lets @Preview drive it with fixture data instead of a real
 * GeotagWorkflowViewModel, which would need live DataStore/Room access to construct.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    uiState: WorkflowUiState,
    snackbarHostState: SnackbarHostState,
    onPickFolder: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    onNavigateToChangeLog: () -> Unit,
    onPreScan: () -> Unit,
    onCancelScan: () -> Unit,
    onDateRangeChange: (Instant?, Instant?) -> Unit,
    onLoadLocalOffset: () -> Unit,
    onAdjustOffsetHours: (Int) -> Unit,
    onSetOffsetSeconds: (Int) -> Unit,
    onDryRun: () -> Unit
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Oly Photo Tagger") },
                    actions = {
                        // Promoted here from a button buried in Settings — a record of
                        // what this app actually did to real photos deserves to be
                        // reachable in one tap, not two.
                        IconButton(onClick = onNavigateToChangeLog) {
                            Icon(Icons.Default.History, contentDescription = "Change log")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
                // Pinned directly under the title bar, not scrolled with the rest of the
                // content below — this used to be the last item in the scrollable column,
                // after the recovery banner and every card, which meant a long-running scan
                // (e.g. checking hundreds of files for interrupted writes) had no visible
                // sign of life unless already scrolled all the way down.
                if (uiState.isBusy) {
                    val scanProgress = uiState.scanProgress
                    // Indeterminate until scanProgress has a real total to show a fraction
                    // of — true for every other busy operation (recovery check, dry-run
                    // match), and for a prescan's own brief folder-listing/pairing phase
                    // before its first per-pair status check completes.
                    if (scanProgress != null && scanProgress.total > 0) {
                        LinearProgressIndicator(
                            progress = { scanProgress.completed / scanProgress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                uiState.busyMessage ?: "Working…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (scanProgress != null) {
                                Text(
                                    "${scanProgress.completed} of ${scanProgress.total} checked",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                estimateRemaining(scanProgress.completed, scanProgress.total, scanProgress.startedAt)
                                    ?.let { remaining ->
                                        Text(
                                            "About ${formatDuration(remaining)} remaining",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                            }
                            uiState.trackFetchProgress?.let { progress ->
                                Text(
                                    formatTrackFetchStatus(progress),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Projected from page/totalPages *within the current
                                // cluster's own fetch*, not a whole-operation estimate —
                                // see TrackFetchProgress's own doc for why a cluster is
                                // the largest unit this can honestly be based on.
                                estimateRemaining(progress.page, progress.totalPages, progress.clusterStartedAt)
                                    ?.let { remaining ->
                                        Text(
                                            "About ${formatDuration(remaining)} remaining" +
                                                if (progress.clusterCount > 1) " for this session" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                            }
                        }
                        // Scoped to scan/preview only — the write batch (ProgressScreen) is
                        // deliberately not stoppable this way: it touches real photos, and
                        // each write's own crash-safety/Recovery flow is how that's handled.
                        TextButton(onClick = onCancelScan) { Text("Stop") }
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    actionColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) { padding ->
        var optionsExpanded by remember { mutableStateOf(false) }

        // Flat rows + hairline dividers instead of a card per section — five stacked
        // Cards (16dp internal padding, 16dp between) was the main reason this screen
        // needed a scroll to reach the button below at all. Only the two genuinely
        // optional, occasional-use sections (prescan, date range) fold into Options,
        // collapsed by default; Folder and Camera offset are needed on every run, so
        // they stay always-visible but each now costs one compact row, not a card.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (uiState.pendingRecoveries.isNotEmpty()) {
                Spacer(Modifier.size(16.dp))
                RecoveryBanner(count = uiState.pendingRecoveries.size, onClick = onNavigateToRecovery)
            }

            Spacer(Modifier.size(8.dp))
            FolderRow(displayName = uiState.rootDisplayName, onPick = onPickFolder)
            HorizontalDivider()
            CameraOffsetRow(
                offsetSeconds = uiState.cameraOffsetSeconds,
                onLoadLocal = onLoadLocalOffset,
                onAdjustHours = onAdjustOffsetHours,
                onSetOffsetSeconds = onSetOffsetSeconds
            )
            HorizontalDivider()
            OptionsSection(
                expanded = optionsExpanded,
                onToggleExpanded = { optionsExpanded = !optionsExpanded },
                prescanEnabled = uiState.canScan,
                prescanSummary = uiState.preScanSummary,
                onPreScan = onPreScan,
                dateRangeStart = uiState.dateRangeStart,
                dateRangeEnd = uiState.dateRangeEnd,
                onDateStartChange = { onDateRangeChange(it, uiState.dateRangeEnd) },
                onDateEndChange = { onDateRangeChange(uiState.dateRangeStart, it) }
            )
            HorizontalDivider()

            Button(
                onClick = onDryRun,
                enabled = uiState.canScan,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp)
            ) {
                Text("Preview changes")
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Populated")
@Composable
private fun HomeScreenPopulatedPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(
                rootUri = android.net.Uri.parse("content://fake/DCIM"),
                rootDisplayName = "DCIM",
                cameraOffsetSeconds = 3600,
                preScanSummary = PreviewFixtures.preScanSummary
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenDarkPreview() {
    OlyPhotoTaggerTheme(darkTheme = true, dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(
                rootUri = android.net.Uri.parse("content://fake/DCIM"),
                rootDisplayName = "DCIM",
                cameraOffsetSeconds = 3600,
                preScanSummary = PreviewFixtures.preScanSummary
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Needs recovery")
@Composable
private fun HomeScreenRecoveryPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(
                rootUri = android.net.Uri.parse("content://fake/DCIM"),
                rootDisplayName = "DCIM",
                pendingRecoveries = PreviewFixtures.pendingRecoveries
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Busy — checking for interrupted writes")
@Composable
private fun HomeScreenBusyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(
                rootUri = android.net.Uri.parse("content://fake/DCIM"),
                rootDisplayName = "DCIM",
                isBusy = true,
                busyMessage = "Checking for interrupted writes…"
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Busy — scanning with progress")
@Composable
private fun HomeScreenScanProgressPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(
                rootUri = android.net.Uri.parse("content://fake/DCIM"),
                rootDisplayName = "DCIM",
                isBusy = true,
                busyMessage = "Scanning for photos missing GPS tags…",
                scanProgress = PreviewFixtures.scanProgress
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Busy — fetching GPS track")
@Composable
private fun HomeScreenTrackFetchProgressPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        HomeScreenContent(
            uiState = WorkflowUiState(
                rootUri = android.net.Uri.parse("content://fake/DCIM"),
                rootDisplayName = "DCIM",
                isBusy = true,
                busyMessage = "Fetching your GPS track…",
                trackFetchProgress = PreviewFixtures.trackFetchProgress
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onNavigateToChangeLog = {}, onPreScan = {},
            onCancelScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Advanced offset dialog")
@Composable
private fun AdvancedOffsetDialogPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        AdvancedOffsetDialog(initialOffsetSeconds = 3_667, onUseLocal = {}, onDismiss = {}, onConfirm = {})
    }
}

/**
 * The dismissible flow's manual re-entry point: nothing forces the user here, but it's
 * impossible to miss and always leads straight to [RecoveryScreen] whenever
 * [WorkflowUiState.pendingRecoveries] is non-empty.
 */
@Composable
private fun RecoveryBanner(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "$count photo${if (count == 1) "" else "s"} need attention",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "An earlier write was interrupted — nothing was lost, but these need a quick decision.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** One compact row rather than a titled card — the folder is required on every run, so
 *  it stays always-visible, but doesn't need a card's weight to say so. */
@Composable
private fun FolderRow(displayName: String?, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            displayName ?: "No folder selected",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onPick) {
            Text(if (displayName == null) "Select" else "Change")
        }
    }
}

/**
 * Both the optional prescan and the optional date-range filter live here, collapsed by
 * default — folded together specifically because neither is needed on a typical run
 * (the default "process the whole folder, write everything untagged" path uses neither),
 * so showing them permanently expanded cost more scroll than the two features together
 * are worth on every single visit to this screen.
 */
@Composable
private fun OptionsSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    prescanEnabled: Boolean,
    prescanSummary: PreScanSummary?,
    onPreScan: () -> Unit,
    dateRangeStart: Instant?,
    dateRangeEnd: Instant?,
    onDateStartChange: (Instant?) -> Unit,
    onDateEndChange: (Instant?) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Options", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Pre-check, date range",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 32.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onPreScan, enabled = prescanEnabled) {
                        Text("Check for Untagged Photos")
                    }
                    if (prescanSummary != null) {
                        Text(
                            "Needs tagging: ${prescanSummary.needsTagging}   " +
                                "Already tagged: ${prescanSummary.alreadyTagged}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (prescanSummary.noTimestamp > 0) {
                            SummaryDetailText("Missing timestamp: ${prescanSummary.noTimestamp}")
                        }
                        if (prescanSummary.outsideDateRange > 0) {
                            SummaryDetailText("Outside date range: ${prescanSummary.outsideDateRange}")
                        }
                        if (prescanSummary.conflicts > 0) {
                            SummaryDetailText("Ambiguous duplicates: ${prescanSummary.conflicts}")
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Time range — leave unset to process the whole folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DateTimeField(label = "From", value = dateRangeStart, onValueChange = onDateStartChange)
                    DateTimeField(label = "To", value = dateRangeEnd, onValueChange = onDateEndChange)
                }
            }
        }
    }
}

@Composable
private fun SummaryDetailText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One row rather than a card with a headline-sized number, a paragraph of explanation,
 * and a three-button row — tapping the offset value itself now opens [AdvancedOffsetDialog]
 * (which also carries "use phone's local time", moved there from its own row-width button)
 * rather than needing a separate "Advanced…" link taking its own line.
 */
@Composable
private fun CameraOffsetRow(
    offsetSeconds: Int,
    onLoadLocal: () -> Unit,
    onAdjustHours: (Int) -> Unit,
    onSetOffsetSeconds: (Int) -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Column(Modifier.weight(1f)) {
            Text("Camera clock offset", style = MaterialTheme.typography.bodyMedium)
            Text(
                "From UTC",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { onAdjustHours(-1) }, modifier = Modifier.size(32.dp)) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { showAdvanced = true }) {
            Text(formatOffset(offsetSeconds), style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { onAdjustHours(1) }, modifier = Modifier.size(32.dp)) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (showAdvanced) {
        AdvancedOffsetDialog(
            initialOffsetSeconds = offsetSeconds,
            onUseLocal = onLoadLocal,
            onDismiss = { showAdvanced = false },
            onConfirm = {
                onSetOffsetSeconds(it)
                showAdvanced = false
            }
        )
    }
}

/**
 * HH:MM:SS precision on top of the +-1h buttons above — those cover the common case (the
 * camera's clock was just left on the wrong whole-hour timezone), this covers a clock that
 * was slightly off to begin with, e.g. never set precisely at all. No ViewModel changes
 * needed: [WorkflowUiState.cameraOffsetSeconds] and [GeotagWorkflowViewModel.setCameraOffsetSeconds]
 * are already second-precision throughout — this is purely a more precise way to produce
 * the same Int.
 */
@Composable
private fun AdvancedOffsetDialog(
    initialOffsetSeconds: Int,
    onUseLocal: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val initialAbsSeconds = abs(initialOffsetSeconds)
    var isNegative by remember { mutableStateOf(initialOffsetSeconds < 0) }
    var hoursText by remember { mutableStateOf((initialAbsSeconds / 3600).toString()) }
    var minutesText by remember { mutableStateOf(((initialAbsSeconds % 3600) / 60).toString()) }
    var secondsText by remember { mutableStateOf((initialAbsSeconds % 60).toString()) }

    val magnitude = (hoursText.toIntOrNull() ?: 0) * 3600 +
        (minutesText.toIntOrNull() ?: 0) * 60 +
        (secondsText.toIntOrNull() ?: 0)
    // ZoneOffset only supports up to +/-18:00:00 (ZoneOffset.ofTotalSeconds throws outside
    // that) — clamped here, the one place free-form input enters this value, rather than
    // relied on downstream.
    val totalSeconds = (if (isNegative) -magnitude else magnitude).coerceIn(-64_800, 64_800)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advanced offset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Set the camera clock's offset from UTC precisely, down to the second.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onUseLocal, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Use phone's local time", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignToggleButton(
                        label = "Ahead (+)",
                        selected = !isNegative,
                        onClick = { isNegative = false },
                        modifier = Modifier.weight(1f)
                    )
                    SignToggleButton(
                        label = "Behind (−)",
                        selected = isNegative,
                        onClick = { isNegative = true },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OffsetNumberField(
                        label = "HH",
                        value = hoursText,
                        onValueChange = { hoursText = sanitizeOffsetDigits(it, max = 18) },
                        modifier = Modifier.weight(1f)
                    )
                    OffsetNumberField(
                        label = "MM",
                        value = minutesText,
                        onValueChange = { minutesText = sanitizeOffsetDigits(it, max = 59) },
                        modifier = Modifier.weight(1f)
                    )
                    OffsetNumberField(
                        label = "SS",
                        value = secondsText,
                        onValueChange = { secondsText = sanitizeOffsetDigits(it, max = 59) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("= ${formatOffset(totalSeconds)}", style = MaterialTheme.typography.titleMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(totalSeconds) }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SignToggleButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun OffsetNumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

/** Keeps offset text fields numeric and within [max], clamping rather than rejecting a
 *  keystroke that overshoots — simpler than cross-field validation, and the dialog's own
 *  live "=" preview (fed by the already-clamped value) makes the result obvious either way. */
private fun sanitizeOffsetDigits(input: String, max: Int): String {
    val digitsOnly = input.filter(Char::isDigit).take(2)
    val value = digitsOnly.toIntOrNull() ?: return digitsOnly
    return if (value > max) max.toString() else digitsOnly
}

/** "3 Jun 2026 — 142 points so far", or with a session suffix once there's more than one
 *  cluster to distinguish: "3–5 Jun 2026, session 2 of 3 — 142 points so far". */
private fun formatTrackFetchStatus(progress: TrackFetchProgress): String {
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
    val startDate = fmt.format(progress.rangeStart)
    val endDate = fmt.format(progress.rangeEnd)
    val dateText = if (startDate == endDate) startDate else "$startDate – $endDate"
    val sessionText = if (progress.clusterCount > 1) ", session ${progress.clusterIndex} of ${progress.clusterCount}" else ""
    val pointsText = "${progress.pointsSoFar} point${if (progress.pointsSoFar == 1) "" else "s"} so far"
    return "$dateText$sessionText — $pointsText"
}

private fun formatOffset(totalSeconds: Int): String {
    val sign = if (totalSeconds < 0) "-" else "+"
    val absSeconds = abs(totalSeconds)
    val hours = absSeconds / 3600
    val minutes = (absSeconds % 3600) / 60
    val seconds = absSeconds % 60
    return if (seconds == 0) {
        "%s%02d:%02d".format(sign, hours, minutes)
    } else {
        "%s%02d:%02d:%02d".format(sign, hours, minutes, seconds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(label: String, value: Instant?, onValueChange: (Instant?) -> Unit) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMillis by remember { mutableStateOf<Long?>(null) }

    val zone = ZoneId.systemDefault()
    val displayText = value?.let {
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(zone).format(it)
    } ?: "Not set"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(displayText, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { showDatePicker = true }) { Text(if (value == null) "Set" else "Change") }
        if (value != null) {
            IconButton(onClick = { onValueChange(null) }) {
                Icon(Icons.Default.Clear, contentDescription = "Clear $label")
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDateMillis = state.selectedDateMillis
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val existingZoned = value?.atZone(zone) ?: ZonedDateTime.now(zone)
        val timeState = rememberTimePickerState(
            initialHour = existingZoned.hour,
            initialMinute = existingZoned.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val dateMillis = pendingDateMillis ?: value?.toEpochMilli() ?: System.currentTimeMillis()
                    // DatePicker reports the selected date as UTC-midnight epoch millis;
                    // combine just the date part with the time picked in the local zone.
                    val datePart = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    val combined = datePart.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant()
                    onValueChange(combined)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = timeState)
                    Spacer(Modifier.size(4.dp))
                }
            }
        )
    }
}
