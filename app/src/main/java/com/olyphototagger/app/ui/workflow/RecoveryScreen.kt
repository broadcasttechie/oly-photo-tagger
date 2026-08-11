package com.olyphototagger.app.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * Stable identity for a LazyColumn item key — needed because [IncompleteWrite] itself
 * isn't Bundle-compatible (Compose requires a key be storable in a Bundle for its own
 * scroll-position/state saving, and a plain data class holding [CameraFile]/[java.time
 * .Instant] properties doesn't qualify). Confirmed on a real device (2026-08-11, a batch
 * of 220 real recoveries): passing the whole item as the key threw
 * `IllegalArgumentException: Type of the key ... is not supported` immediately on
 * opening this screen — every classification groups by (folderName, recoveredName), see
 * IncompleteWriteDetector, so this is already guaranteed unique per item in the list.
 */
private fun IncompleteWrite.stableKey(): String = "$folderName/$recoveredName"

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
    // Tracks which bulk actions are currently in flight, purely to disable/spin their own
    // button — the actual resolving happens item-by-item in the ViewModel regardless, this
    // is just to stop a double-tap firing the same bulk action twice while it's still
    // running (harmless either way — resolving an already-resolved item is a no-op — but
    // wasteful and would double-count the summary message). Set right before launching and
    // cleared in `finally` once resolveAllUnambiguous genuinely finishes, so this always
    // reflects the real coroutine state rather than something inferred from the list.
    var resolvingAll by remember { mutableStateOf<Set<IncompleteWriteClassification>>(emptySet()) }
    RecoveryScreenContent(
        pendingRecoveries = uiState.pendingRecoveries,
        resolvingAll = resolvingAll,
        onBack = onBack,
        onResolve = { item, choice -> scope.launch { viewModel.resolveIncompleteWrite(item, choice) } },
        onResolveAll = { classification ->
            resolvingAll = resolvingAll + classification
            scope.launch {
                try {
                    viewModel.resolveAllUnambiguous(classification)
                } finally {
                    resolvingAll = resolvingAll - classification
                }
            }
        }
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
    resolvingAll: Set<IncompleteWriteClassification>,
    onBack: () -> Unit,
    onResolve: (IncompleteWrite, RecoveryChoice) -> Unit,
    onResolveAll: (IncompleteWriteClassification) -> Unit
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

        // Grouped so a batch that's overwhelmingly one trivial case (e.g. 200 stray temp
        // files from a card that ran out of space mid-batch) gets a single one-tap fix at
        // the top, instead of forcing the same tap 200 times over — see
        // GeotagWorkflowViewModel.resolveAllUnambiguous's own doc for why this matters.
        val groups = pendingRecoveries.groupBy { it.classification }
        val bulkEligibleGroups = groups.filterKeys { RecoveryOptions.unambiguousChoiceFor(it) != null }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "An earlier write on ${pendingRecoveries.size} photo${if (pendingRecoveries.size == 1) "" else "s"} " +
                        "didn't finish — most likely the app was closed or crashed mid-write. Nothing has been lost; " +
                        "choose how to resolve each one below.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (bulkEligibleGroups.isNotEmpty()) {
                items(bulkEligibleGroups.entries.toList(), key = { "bulk-${it.key}" }) { (classification, items) ->
                    BulkActionCard(
                        classification = classification,
                        count = items.size,
                        isResolving = classification in resolvingAll,
                        onResolveAll = { onResolveAll(classification) }
                    )
                }
            }

            items(pendingRecoveries, key = { it.stableKey() }) { item ->
                RecoveryItemCard(item = item, onResolve = { choice -> onResolve(item, choice) })
            }
        }
    }
}

@Composable
private fun BulkActionCard(
    classification: IncompleteWriteClassification,
    count: Int,
    isResolving: Boolean,
    onResolveAll: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    val choice = RecoveryOptions.unambiguousChoiceFor(classification) ?: return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$count file${if (count == 1) "" else "s"} — same situation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                explanationFor(classification),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showConfirm = true }, enabled = !isResolving) {
                    Text("${labelFor(choice)} — all $count")
                }
                if (isResolving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("${labelFor(choice)} for all $count files?") },
            text = { Text("This applies to every file in this group at once. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onResolveAll(); showConfirm = false }) { Text(labelFor(choice)) }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } }
        )
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
        RecoveryScreenContent(
            pendingRecoveries = PreviewFixtures.pendingRecoveries,
            resolvingAll = emptySet(),
            onBack = {},
            onResolve = { _, _ -> },
            onResolveAll = {}
        )
    }
}

@Preview(showBackground = true, name = "Large batch, bulk-eligible")
@Composable
private fun RecoveryScreenLargeBatchPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        RecoveryScreenContent(
            pendingRecoveries = PreviewFixtures.manyPendingRecoveries,
            resolvingAll = emptySet(),
            onBack = {},
            onResolve = { _, _ -> },
            onResolveAll = {}
        )
    }
}

@Preview(showBackground = true, name = "Nothing to recover")
@Composable
private fun RecoveryScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        RecoveryScreenContent(
            pendingRecoveries = emptyList(),
            resolvingAll = emptySet(),
            onBack = {},
            onResolve = { _, _ -> },
            onResolveAll = {}
        )
    }
}
