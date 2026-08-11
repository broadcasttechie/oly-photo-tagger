package com.olyphototagger.app.ui.workflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
    onNavigateToRecovery: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.setRoot(it) }
    }

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
        onPreScan = { scope.launch { viewModel.runPreScan() } },
        onDateRangeChange = viewModel::setDateRange,
        onLoadLocalOffset = viewModel::loadLocalOffset,
        onAdjustOffsetHours = viewModel::adjustOffsetHours,
        onSetOffsetSeconds = viewModel::setCameraOffsetSeconds,
        onDryRun = { scope.launch { if (viewModel.runDryScan()) onNavigateToDryRun() } }
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
    onPreScan: () -> Unit,
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
                        Column {
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
                        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.pendingRecoveries.isNotEmpty()) {
                RecoveryBanner(count = uiState.pendingRecoveries.size, onClick = onNavigateToRecovery)
            }

            FolderCard(
                displayName = uiState.rootDisplayName,
                onPick = onPickFolder
            )

            PreScanCard(
                enabled = uiState.canScan,
                summary = uiState.preScanSummary,
                onPreScan = onPreScan
            )

            DateRangeCard(
                start = uiState.dateRangeStart,
                end = uiState.dateRangeEnd,
                onStartChange = { onDateRangeChange(it, uiState.dateRangeEnd) },
                onEndChange = { onDateRangeChange(uiState.dateRangeStart, it) }
            )

            CameraOffsetCard(
                offsetSeconds = uiState.cameraOffsetSeconds,
                onLoadLocal = onLoadLocalOffset,
                onAdjustHours = onAdjustOffsetHours,
                onSetOffsetSeconds = onSetOffsetSeconds
            )

            Button(
                onClick = onDryRun,
                enabled = uiState.canScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Preview Changes (Dry Run)")
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
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onPreScan = {},
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
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onPreScan = {},
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
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onPreScan = {},
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
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onPreScan = {},
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
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onPreScan = {},
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
            onPickFolder = {}, onNavigateToSettings = {}, onNavigateToRecovery = {}, onPreScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {},
            onSetOffsetSeconds = {}, onDryRun = {}
        )
    }
}

@Preview(showBackground = true, name = "Advanced offset dialog")
@Composable
private fun AdvancedOffsetDialogPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        AdvancedOffsetDialog(initialOffsetSeconds = 3_667, onDismiss = {}, onConfirm = {})
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

@Composable
private fun FolderCard(displayName: String?, onPick: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Photo folder", style = MaterialTheme.typography.titleMedium)
            Text(
                displayName ?: "No folder selected — pick the camera's DCIM folder",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onPick) {
                Text(if (displayName == null) "Select Folder" else "Change Folder")
            }
        }
    }
}

@Composable
private fun PreScanCard(enabled: Boolean, summary: PreScanSummary?, onPreScan: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check before running (optional)", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onPreScan, enabled = enabled) {
                Text("Check for Untagged Photos")
            }
            if (summary != null) {
                Text("Needs tagging: ${summary.needsTagging}")
                Text("Already tagged: ${summary.alreadyTagged}")
                if (summary.noTimestamp > 0) Text("Missing timestamp: ${summary.noTimestamp}")
                if (summary.outsideDateRange > 0) Text("Outside date range: ${summary.outsideDateRange}")
                if (summary.conflicts > 0) Text("Ambiguous duplicates: ${summary.conflicts}")
            }
        }
    }
}

@Composable
private fun DateRangeCard(
    start: Instant?,
    end: Instant?,
    onStartChange: (Instant?) -> Unit,
    onEndChange: (Instant?) -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Limit to a time range (optional)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Leave unset to process the whole folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DateTimeField(label = "From", value = start, onValueChange = onStartChange)
            DateTimeField(label = "To", value = end, onValueChange = onEndChange)
        }
    }
}

@Composable
private fun CameraOffsetCard(
    offsetSeconds: Int,
    onLoadLocal: () -> Unit,
    onAdjustHours: (Int) -> Unit,
    onSetOffsetSeconds: (Int) -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Camera clock offset from UTC", style = MaterialTheme.typography.titleMedium)
            Text(
                "Most cameras don't record their timezone — this tells the app what the " +
                    "camera's clock actually meant. Defaults to what you used last time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(formatOffset(offsetSeconds), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAdjustHours(-1) }) { Text("-1h") }
                OutlinedButton(onClick = onLoadLocal) { Text("Use phone's local time") }
                OutlinedButton(onClick = { onAdjustHours(1) }) { Text("+1h") }
            }
            TextButton(onClick = { showAdvanced = true }, modifier = Modifier.align(Alignment.End)) {
                Text("Advanced…")
            }
        }
    }

    if (showAdvanced) {
        AdvancedOffsetDialog(
            initialOffsetSeconds = offsetSeconds,
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
