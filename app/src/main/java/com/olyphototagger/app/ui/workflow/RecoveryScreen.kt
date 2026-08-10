package com.olyphototagger.app.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme
import com.olyphototagger.app.write.IncompleteWrite
import com.olyphototagger.app.write.IncompleteWriteClassification
import com.olyphototagger.app.write.RecoveryChoice
import com.olyphototagger.app.write.RecoveryOptions
import kotlinx.coroutines.launch

/**
 * Lets the user resolve any interrupted writes GeotagWorkflowViewModel found — see
 * IncompleteWriteScanner. Reachable from Home whenever
 * [com.olyphototagger.app.ui.workflow.WorkflowUiState.pendingRecoveries] is non-empty;
 * dismissible by design (back navigation is always available, nothing here blocks the
 * rest of the app), since GpsExifWriter itself refuses to touch a file with an unresolved
 * backup regardless of whether this screen was ever seen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    viewModel: GeotagWorkflowViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RecoveryScreenContent(
        pendingRecoveries = uiState.pendingRecoveries,
        onBack = onBack,
        onResolve = { item, choice -> scope.launch { viewModel.resolveIncompleteWrite(item, choice) } }
    )
}

/**
 * The actual Recovery UI, taking plain state and callbacks rather than the ViewModel
 * directly — lets @Preview drive it with fixture data, same split as every other screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryScreenContent(
    pendingRecoveries: List<IncompleteWrite>,
    onBack: () -> Unit,
    onResolve: (IncompleteWrite, RecoveryChoice) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (pendingRecoveries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Nothing to recover — every photo is in a clean, known-good state.")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "An earlier write on ${pendingRecoveries.size} photo${if (pendingRecoveries.size == 1) "" else "s"} " +
                    "didn't finish — most likely the app was closed or crashed mid-write. Nothing has been lost; " +
                    "choose how to resolve each one below.",
                style = MaterialTheme.typography.bodyMedium
            )
            pendingRecoveries.forEach { item ->
                // Keyed by the item itself (a data class, so folderName+recoveredName+...
                // determine equality), not the loop's positional index — without this,
                // RecoveryItemCard's own remembered pendingConfirmChoice can attach to the
                // wrong card after an earlier item in the list is resolved and removed,
                // since Compose would otherwise reuse slot state by position alone.
                key(item) {
                    RecoveryItemCard(item = item, onResolve = { choice -> onResolve(item, choice) })
                }
            }
        }
    }
}

@Composable
private fun RecoveryItemCard(item: IncompleteWrite, onResolve: (RecoveryChoice) -> Unit) {
    var pendingConfirmChoice by remember { mutableStateOf<RecoveryChoice?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.recoveredName, style = MaterialTheme.typography.titleMedium)
            Text(
                explanationFor(item.classification),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val suggestedDefault = RecoveryOptions.suggestedDefault(item.classification)
                RecoveryOptions.choicesFor(item.classification).forEach { choice ->
                    val onClick = {
                        if (isDestructiveChoice(choice)) pendingConfirmChoice = choice else onResolve(choice)
                    }
                    if (choice == suggestedDefault) {
                        Button(onClick = onClick) { Text(labelFor(choice)) }
                    } else {
                        OutlinedButton(onClick = onClick) { Text(labelFor(choice)) }
                    }
                }
            }
        }
    }

    pendingConfirmChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = { pendingConfirmChoice = null },
            title = { Text(labelFor(choice) + "?") },
            text = { Text(confirmationTextFor(choice)) },
            confirmButton = {
                TextButton(onClick = { onResolve(choice); pendingConfirmChoice = null }) { Text(labelFor(choice)) }
            },
            dismissButton = { TextButton(onClick = { pendingConfirmChoice = null }) { Text("Cancel") } }
        )
    }
}

/** Choices that pick one of several genuinely-uncertain files and discard the rest —
 *  gated behind an explicit confirmation, unlike the choices that only ever discard a
 *  file already known to be redundant (a stray temp once the original's confirmed
 *  intact, or a backup once the temp's already been verified). */
private fun isDestructiveChoice(choice: RecoveryChoice): Boolean = choice in setOf(
    RecoveryChoice.RESTORE_ORIGINAL,
    RecoveryChoice.FLAG_KEEP_ORIGINAL,
    RecoveryChoice.FLAG_KEEP_BACKUP,
    RecoveryChoice.FLAG_KEEP_TEMP
)

private fun explanationFor(classification: IncompleteWriteClassification): String = when (classification) {
    IncompleteWriteClassification.StaleTempOnly ->
        "A leftover temporary file was found — the photo itself is untouched and safe."
    IncompleteWriteClassification.AwaitingChoice ->
        "An earlier write was interrupted partway through. Nothing was lost — choose whether to finish tagging or restore the original."
    IncompleteWriteClassification.BackupOnly ->
        "Only a backup of the original was found under its usual name — restoring it brings the photo back."
    IncompleteWriteClassification.OriginalAndBackupPresent ->
        "A backup was found alongside the current photo. Choose which one to keep."
    IncompleteWriteClassification.AllThreePresent ->
        "Multiple versions of this photo were found. Choose which one to keep."
    IncompleteWriteClassification.TempOnly ->
        "An unexplained leftover file was found with no matching photo — its origin isn't known. Choose whether to trust it or discard it."
}

private fun labelFor(choice: RecoveryChoice): String = when (choice) {
    RecoveryChoice.DISCARD_TEMP -> "Discard leftover file"
    RecoveryChoice.RESTORE_ORIGINAL -> "Restore original"
    RecoveryChoice.COMPLETE_TAGGING -> "Finish tagging"
    RecoveryChoice.RESTORE_FROM_BACKUP -> "Restore from backup"
    RecoveryChoice.FLAG_KEEP_ORIGINAL -> "Keep current photo"
    RecoveryChoice.FLAG_KEEP_BACKUP -> "Keep backup instead"
    RecoveryChoice.FLAG_KEEP_TEMP -> "Keep this file"
}

private fun confirmationTextFor(choice: RecoveryChoice): String = when (choice) {
    RecoveryChoice.RESTORE_ORIGINAL ->
        "This keeps the original untouched photo and discards the already-tagged result. This can't be undone."
    RecoveryChoice.FLAG_KEEP_ORIGINAL ->
        "This keeps the current photo as-is and discards the other file(s) found alongside it. This can't be undone."
    RecoveryChoice.FLAG_KEEP_BACKUP ->
        "This replaces the current photo with the backup and discards the rest. This can't be undone."
    RecoveryChoice.FLAG_KEEP_TEMP ->
        "This makes this leftover file the photo and discards the rest. This can't be undone."
    else -> "This can't be undone."
}

@Preview(showBackground = true, name = "Mixed recovery cases")
@Composable
private fun RecoveryScreenPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        RecoveryScreenContent(pendingRecoveries = PreviewFixtures.pendingRecoveries, onBack = {}, onResolve = { _, _ -> })
    }
}

@Preview(showBackground = true, name = "Nothing to recover")
@Composable
private fun RecoveryScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        RecoveryScreenContent(pendingRecoveries = emptyList(), onBack = {}, onResolve = { _, _ -> })
    }
}
