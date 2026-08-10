package com.olyphototagger.app.ui.workflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Purely observational — the write batch is already running in the ViewModel's own
 * scope (started from the dry-run screen before navigating here), so this screen just
 * reflects live state. Navigating away and back doesn't restart or interrupt anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    viewModel: GeotagWorkflowViewModel,
    onFinished: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.runResults) {
        if (uiState.runResults != null) onFinished()
    }

    // startRun() can fail before ever producing progress/results (e.g. Dawarich becomes
    // unconfigured in the moment between confirming the dry run and the write actually
    // starting) — without this, that failure was previously silent and the screen just
    // sat on "Starting…" forever with no explanation.
    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // The write itself survives navigation regardless (it runs in the ViewModel's own
    // scope), but letting the user navigate back to a "confirm run" screen while a batch
    // is actively writing is a confusing state worth just not allowing.
    BackHandler(enabled = uiState.runProgress != null) {}

    Scaffold(
        topBar = { TopAppBar(title = { Text("Writing GPS Tags") }) },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val progress = uiState.runProgress
            if (progress == null) {
                Text("Starting…", style = MaterialTheme.typography.titleMedium)
            } else {
                val fraction = if (progress.total == 0) 0f else progress.completed / progress.total.toFloat()
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${progress.completed} of ${progress.total}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    progress.currentAction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "Don't close the app while this is running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }
}
