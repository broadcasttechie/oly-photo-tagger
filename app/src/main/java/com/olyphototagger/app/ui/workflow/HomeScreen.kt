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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    onNavigateToSettings: () -> Unit
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
                actionLabel = if (message == GeotagWorkflowViewModel.MISSING_DAWARICH_CONFIG_MESSAGE) "Settings" else null,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) onNavigateToSettings()
        }
    }

    HomeScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onPickFolder = { pickFolder.launch(null) },
        onNavigateToSettings = onNavigateToSettings,
        onPreScan = { scope.launch { viewModel.runPreScan() } },
        onDateRangeChange = viewModel::setDateRange,
        onLoadLocalOffset = viewModel::loadLocalOffset,
        onAdjustOffsetHours = viewModel::adjustOffsetHours,
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
    onPreScan: () -> Unit,
    onDateRangeChange: (Instant?, Instant?) -> Unit,
    onLoadLocalOffset: () -> Unit,
    onAdjustOffsetHours: (Int) -> Unit,
    onDryRun: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Oly Photo Tagger") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
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
                onAdjustHours = onAdjustOffsetHours
            )

            Button(
                onClick = onDryRun,
                enabled = uiState.canScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Preview Changes (Dry Run)")
            }

            if (uiState.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(uiState.busyMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
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
            onPickFolder = {}, onNavigateToSettings = {}, onPreScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {}, onDryRun = {}
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
            onPickFolder = {}, onNavigateToSettings = {}, onPreScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {}, onDryRun = {}
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
            onPickFolder = {}, onNavigateToSettings = {}, onPreScan = {},
            onDateRangeChange = { _, _ -> }, onLoadLocalOffset = {}, onAdjustOffsetHours = {}, onDryRun = {}
        )
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
    onAdjustHours: (Int) -> Unit
) {
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
        }
    }
}

private fun formatOffset(totalSeconds: Int): String {
    val sign = if (totalSeconds < 0) "-" else "+"
    val absSeconds = abs(totalSeconds)
    val hours = absSeconds / 3600
    val minutes = (absSeconds % 3600) / 60
    return "%s%02d:%02d".format(sign, hours, minutes)
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
