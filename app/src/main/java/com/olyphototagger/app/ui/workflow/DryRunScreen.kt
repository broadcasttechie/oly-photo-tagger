package com.olyphototagger.app.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.pipeline.ExcludeReason
import com.olyphototagger.app.pipeline.ExcludedPair
import com.olyphototagger.app.pipeline.ProposedMatch
import com.olyphototagger.app.pipeline.ScanResult
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme

/**
 * Shows exactly what a real run would do — and, just as importantly, exactly what it
 * would skip and why — before anything is written. This screen never itself writes
 * anything; [onConfirmRun] is the only path from here into an actual write, and it's
 * gated on the user pressing the button below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DryRunScreen(
    viewModel: GeotagWorkflowViewModel,
    onBack: () -> Unit,
    onConfirmRun: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DryRunScreenContent(
        scanResult = uiState.scanResult,
        onBack = onBack,
        onConfirmRun = { viewModel.startRun(); onConfirmRun() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DryRunScreenContent(
    scanResult: ScanResult?,
    onBack: () -> Unit,
    onConfirmRun: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (scanResult == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("No preview yet — go back and run a dry run first.")
            }
            return@Scaffold
        }

        val willWrite = scanResult.matches.filter { it.geoMatch is GeoMatch.Matched }
        val gapTooLarge = scanResult.matches.filter { it.geoMatch is GeoMatch.GapTooLarge }
        val outsideTrack = scanResult.matches.filter { it.geoMatch is GeoMatch.OutsideTrack }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SummaryCard(scanResult, willWrite.size, gapTooLarge.size, outsideTrack.size)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (willWrite.isNotEmpty()) {
                    SectionHeader("Will be tagged (${willWrite.size})")
                    willWrite.forEach { MatchedRow(it) }
                }
                if (gapTooLarge.isNotEmpty()) {
                    SectionHeader("Skipped — GPS gap too large (${gapTooLarge.size})")
                    gapTooLarge.forEach { SkippedRow(it, "GPS points too far apart in time") }
                }
                if (outsideTrack.isNotEmpty()) {
                    SectionHeader("Skipped — outside GPS track (${outsideTrack.size})")
                    outsideTrack.forEach { SkippedRow(it, "No nearby GPS data") }
                }
                if (scanResult.excluded.isNotEmpty()) {
                    SectionHeader("Not considered (${scanResult.excluded.size})")
                    scanResult.excluded.forEach { ExcludedRow(it) }
                }
                if (scanResult.conflicts.isNotEmpty()) {
                    SectionHeader("Ambiguous duplicates, skipped (${scanResult.conflicts.size})")
                    scanResult.conflicts.forEach { Text("  ${it.displayName}", style = MaterialTheme.typography.bodySmall) }
                }
            }

            Button(
                onClick = onConfirmRun,
                enabled = willWrite.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (willWrite.isEmpty()) "Nothing to write"
                    else "Write GPS to ${willWrite.size} photo${if (willWrite.size == 1) "" else "s"}"
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(scanResult: ScanResult, willWrite: Int, gapTooLarge: Int, outsideTrack: Int) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Will write GPS to $willWrite photo pair${if (willWrite == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
            if (gapTooLarge + outsideTrack > 0) {
                Text(
                    "${gapTooLarge + outsideTrack} will be skipped (shown below with why) — not silently guessed at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (scanResult.ignoredFiles.isNotEmpty()) {
                Text(
                    "${scanResult.ignoredFiles.size} non-photo file(s) on the card were ignored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    HorizontalDivider()
}

@Composable
private fun MatchedRow(match: ProposedMatch) {
    val geo = match.geoMatch as GeoMatch.Matched
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(match.pair.baseName, style = MaterialTheme.typography.bodyMedium)
        Text(
            "%.6f, %.6f".format(geo.latitude, geo.longitude),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SkippedRow(match: ProposedMatch, reason: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(match.pair.baseName, style = MaterialTheme.typography.bodyMedium)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ExcludedRow(excluded: ExcludedPair) {
    val reasonText = when (excluded.reason) {
        ExcludeReason.ALREADY_TAGGED -> "already has GPS data"
        ExcludeReason.NO_TIMESTAMP -> "no readable capture timestamp"
        ExcludeReason.OUTSIDE_DATE_RANGE -> "outside the selected date range"
    }
    Text(
        "${excluded.pair.baseName} — $reasonText",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Preview(showBackground = true, name = "Mixed results")
@Composable
private fun DryRunScreenPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        DryRunScreenContent(scanResult = PreviewFixtures.scanResult, onBack = {}, onConfirmRun = {})
    }
}

@Preview(showBackground = true, name = "No scan yet")
@Composable
private fun DryRunScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        DryRunScreenContent(scanResult = null, onBack = {}, onConfirmRun = {})
    }
}
