package com.olyphototagger.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olyphototagger.app.settings.GpsSourceType
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fileRangeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsSourcesScreen(
    viewModel: GpsSourcesViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // GPX has no single standard MIME type in the wild — "*/*" is deliberate; the
    // parser is what actually validates the file, not the picker's filter.
    val importGpxFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importGpxFile(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    GpsSourcesScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSelectSource = viewModel::selectSource,
        onBaseUrlChange = viewModel::setBaseUrl,
        onApiTokenChange = viewModel::setApiToken,
        onSaveDawarich = viewModel::saveDawarichConfig,
        onImportGpxFile = { importGpxFile.launch(arrayOf("*/*")) },
        onDeleteGpxFile = viewModel::deleteGpxFile,
        onConfirmPendingShareImport = viewModel::confirmPendingShareImport,
        onDismissPendingShareImport = viewModel::dismissPendingShareImport
    )
}

/**
 * The actual GPS Sources UI, taking plain state and callbacks rather than the ViewModel
 * directly — lets @Preview drive it with fixture data, same split as every other screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpsSourcesScreenContent(
    uiState: GpsSourcesUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSelectSource: (GpsSourceType) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onSaveDawarich: () -> Unit,
    onImportGpxFile: () -> Unit,
    onDeleteGpxFile: (Long) -> Unit,
    onConfirmPendingShareImport: () -> Unit,
    onDismissPendingShareImport: () -> Unit
) {
    uiState.pendingShareImport?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissPendingShareImport,
            title = { Text("Import shared GPX file?") },
            text = { Text("\"${pending.suggestedName}\" was shared with Oly Photo Tagger. Import it as a GPS source?") },
            confirmButton = { TextButton(onClick = onConfirmPendingShareImport) { Text("Import") } },
            dismissButton = { TextButton(onClick = onDismissPendingShareImport) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPS Sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Active source", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Which GPS track feeds photo matching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SourceOptionRow(
                    label = "Dawarich",
                    selected = uiState.activeSource == GpsSourceType.DAWARICH,
                    onClick = { onSelectSource(GpsSourceType.DAWARICH) }
                )
                SourceOptionRow(
                    label = "Imported GPX files",
                    selected = uiState.activeSource == GpsSourceType.GPX,
                    onClick = { onSelectSource(GpsSourceType.GPX) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Dawarich", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = uiState.dawarichBaseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Base URL") },
                    placeholder = { Text("dawarich.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = uiState.dawarichApiToken,
                    onValueChange = onApiTokenChange,
                    label = { Text("API token") },
                    placeholder = {
                        Text(if (uiState.hasExistingDawarichToken) "Saved — enter a new one to replace" else "Paste your API token")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = onSaveDawarich,
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isSaving) "Saving…" else "Save Dawarich settings")
                }

                uiState.saveMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Imported GPX files", style = MaterialTheme.typography.titleMedium)

                if (uiState.importedGpxFiles.isEmpty()) {
                    Text(
                        "No GPX files imported yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.importedGpxFiles.forEach { file ->
                            GpxFileRow(file = file, onDelete = { onDeleteGpxFile(file.id) })
                        }
                    }
                }

                OutlinedButton(onClick = onImportGpxFile, modifier = Modifier.fillMaxWidth()) {
                    Text("Import a GPX file")
                }
            }
        }
    }
}

@Composable
private fun SourceOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun GpxFileRow(file: GpxFileUiState, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${file.pointCount} points, ${fileRangeFormatter.format(file.earliest)}–${fileRangeFormatter.format(file.latest)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${file.displayName}")
            }
        }
    }
}

@Preview(showBackground = true, name = "No source configured")
@Composable
private fun GpsSourcesScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        GpsSourcesScreenContent(
            uiState = GpsSourcesUiState(),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onSelectSource = {}, onBaseUrlChange = {}, onApiTokenChange = {},
            onSaveDawarich = {}, onImportGpxFile = {}, onDeleteGpxFile = {},
            onConfirmPendingShareImport = {}, onDismissPendingShareImport = {}
        )
    }
}

@Preview(showBackground = true, name = "Dawarich active")
@Composable
private fun GpsSourcesScreenDawarichPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        GpsSourcesScreenContent(
            uiState = GpsSourcesUiState(
                activeSource = GpsSourceType.DAWARICH,
                dawarichBaseUrl = "https://dawarich.home.example.com",
                hasExistingDawarichToken = true
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onSelectSource = {}, onBaseUrlChange = {}, onApiTokenChange = {},
            onSaveDawarich = {}, onImportGpxFile = {}, onDeleteGpxFile = {},
            onConfirmPendingShareImport = {}, onDismissPendingShareImport = {}
        )
    }
}

@Preview(showBackground = true, name = "GPX active, 2 files imported")
@Composable
private fun GpsSourcesScreenGpxPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        GpsSourcesScreenContent(
            uiState = GpsSourcesUiState(
                activeSource = GpsSourceType.GPX,
                importedGpxFiles = PreviewFixtures.gpxFilesImported
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onSelectSource = {}, onBaseUrlChange = {}, onApiTokenChange = {},
            onSaveDawarich = {}, onImportGpxFile = {}, onDeleteGpxFile = {},
            onConfirmPendingShareImport = {}, onDismissPendingShareImport = {}
        )
    }
}

@Preview(showBackground = true, name = "Confirming a shared file")
@Composable
private fun GpsSourcesScreenPendingSharePreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        GpsSourcesScreenContent(
            uiState = GpsSourcesUiState(
                activeSource = GpsSourceType.GPX,
                importedGpxFiles = PreviewFixtures.gpxFilesImported,
                pendingShareImport = PendingShareImport(
                    uri = android.net.Uri.parse("content://com.mendhak.gpslogger.fileprovider/gpx/20260810.gpx"),
                    suggestedName = "20260810.gpx"
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onSelectSource = {}, onBaseUrlChange = {}, onApiTokenChange = {},
            onSaveDawarich = {}, onImportGpxFile = {}, onDeleteGpxFile = {},
            onConfirmPendingShareImport = {}, onDismissPendingShareImport = {}
        )
    }
}
