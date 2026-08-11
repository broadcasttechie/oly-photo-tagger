package com.olyphototagger.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olyphototagger.app.cache.WriteLogResultType
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The persistent write history GpsExifWriteResult's own doc always said was coming — every
 * file-level write *attempt* (not just successes), so "why wasn't this photo tagged" has an
 * answer even long after a run finishes. Reachable from Settings; nothing here is
 * time-sensitive or actionable the way [com.olyphototagger.app.ui.workflow.RecoveryScreen]
 * is, so unlike that screen this one has no banner/auto-navigation of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeLogScreen(viewModel: ChangeLogViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    ChangeLogScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRequestClear = viewModel::requestClearLog,
        onCancelClear = viewModel::cancelClearLog,
        onConfirmClear = viewModel::confirmClearLog
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeLogScreenContent(
    uiState: ChangeLogUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRequestClear: () -> Unit,
    onCancelClear: () -> Unit,
    onConfirmClear: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.entries.isNotEmpty()) {
                        IconButton(onClick = onRequestClear) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear log")
                        }
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) { padding ->
        if (uiState.entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Nothing logged yet — every write attempt will show up here once you run a tagging pass.")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                uiState.entries.forEach { entry ->
                    WriteLogEntryCard(entry)
                }
            }
        }
    }

    if (uiState.pendingClearConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelClear,
            title = { Text("Clear the change log?") },
            text = { Text("This permanently deletes all ${uiState.entries.size} logged entries. It doesn't touch any photos.") },
            confirmButton = { TextButton(onClick = onConfirmClear) { Text("Clear") } },
            dismissButton = { TextButton(onClick = onCancelClear) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WriteLogEntryCard(entry: WriteLogEntryUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val path = if (entry.folderName.isBlank()) entry.displayName else "${entry.folderName}/${entry.displayName}"
            Text(path, style = MaterialTheme.typography.titleMedium)
            Text(
                formatLoggedAt(entry.loggedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                labelFor(entry.resultType),
                style = MaterialTheme.typography.bodyMedium,
                color = colorFor(entry.resultType)
            )
            entry.newLatLong?.let { (lat, lon) ->
                val altitude = entry.newAltitudeMeters?.let { ", %.0fm".format(it) }.orEmpty()
                Text("New: %.6f, %.6f%s".format(lat, lon, altitude), style = MaterialTheme.typography.bodySmall)
            }
            entry.previousLatLong?.let { (lat, lon) ->
                Text(
                    "Previous: %.6f, %.6f".format(lat, lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entry.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun labelFor(resultType: WriteLogResultType): String = when (resultType) {
    WriteLogResultType.WRITTEN -> "Tagged"
    WriteLogResultType.SKIPPED_ALREADY_TAGGED -> "Skipped — already tagged"
    WriteLogResultType.UNSUPPORTED_FORMAT -> "Skipped — unsupported format"
    WriteLogResultType.FAILED -> "Failed"
    WriteLogResultType.BACKUP_ARTIFACT_PRESENT -> "Skipped — needs recovery"
    WriteLogResultType.NEEDS_RECOVERY -> "Interrupted — needs recovery"
}

@Composable
private fun colorFor(resultType: WriteLogResultType) = when (resultType) {
    WriteLogResultType.WRITTEN -> MaterialTheme.colorScheme.primary
    WriteLogResultType.SKIPPED_ALREADY_TAGGED, WriteLogResultType.UNSUPPORTED_FORMAT -> MaterialTheme.colorScheme.onSurfaceVariant
    WriteLogResultType.FAILED, WriteLogResultType.BACKUP_ARTIFACT_PRESENT, WriteLogResultType.NEEDS_RECOVERY -> MaterialTheme.colorScheme.error
}

private fun formatLoggedAt(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(instant)

@Preview(showBackground = true, name = "Populated")
@Composable
private fun ChangeLogScreenPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        ChangeLogScreenContent(
            uiState = ChangeLogUiState(entries = PreviewFixtures.writeLogEntries),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onRequestClear = {}, onCancelClear = {}, onConfirmClear = {}
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun ChangeLogScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        ChangeLogScreenContent(
            uiState = ChangeLogUiState(),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onRequestClear = {}, onCancelClear = {}, onConfirmClear = {}
        )
    }
}

@Preview(showBackground = true, name = "Clear confirmation")
@Composable
private fun ChangeLogScreenClearConfirmPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        ChangeLogScreenContent(
            uiState = ChangeLogUiState(entries = PreviewFixtures.writeLogEntries, pendingClearConfirmation = true),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onRequestClear = {}, onCancelClear = {}, onConfirmClear = {}
        )
    }
}
