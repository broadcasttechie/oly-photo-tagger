package com.olyphototagger.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    SettingsScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onBaseUrlChange = viewModel::setBaseUrl,
        onApiTokenChange = viewModel::setApiToken,
        onGapThresholdChange = viewModel::setGapThresholdMinutes,
        onSave = viewModel::save
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onGapThresholdChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                placeholder = { Text(if (uiState.hasExistingToken) "Saved — enter a new one to replace" else "Paste your API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Text("Matching", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = uiState.gapThresholdMinutes,
                onValueChange = onGapThresholdChange,
                label = { Text("Max gap between GPS points (minutes)") },
                supportingText = { Text("If the nearest GPS points bracketing a photo are further apart than this, it's skipped and flagged rather than guessed at.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onSave,
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.isSaving) "Saving…" else "Save")
            }

            uiState.saveMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun SettingsScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        SettingsScreenContent(
            uiState = SettingsUiState(gapThresholdMinutes = "5"),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onBaseUrlChange = {}, onApiTokenChange = {}, onGapThresholdChange = {}, onSave = {}
        )
    }
}

@Preview(showBackground = true, name = "Configured")
@Composable
private fun SettingsScreenConfiguredPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        SettingsScreenContent(
            uiState = SettingsUiState(
                dawarichBaseUrl = "https://dawarich.home.example.com",
                hasExistingToken = true,
                gapThresholdMinutes = "5",
                saveMessage = "Settings saved"
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {}, onBaseUrlChange = {}, onApiTokenChange = {}, onGapThresholdChange = {}, onSave = {}
        )
    }
}
