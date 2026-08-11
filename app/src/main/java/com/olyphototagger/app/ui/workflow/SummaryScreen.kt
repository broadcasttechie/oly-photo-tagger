package com.olyphototagger.app.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.olyphototagger.app.pipeline.PairWriteResult
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme
import com.olyphototagger.app.write.GpsExifWriteResult
import java.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    viewModel: GeotagWorkflowViewModel,
    onDone: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SummaryScreenContent(results = uiState.runResults.orEmpty(), duration = uiState.runDuration, onDone = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryScreenContent(
    results: List<PairWriteResult>,
    duration: Duration?,
    onDone: () -> Unit
) {
    val succeeded = results.count { it.isFullySuccessful }
    val needsAttention = results.filterNot { it.isFullySuccessful }
    val fileCount = results.sumOf { listOfNotNull(it.jpegResult, it.rawResult).size }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Summary") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "$succeeded of ${results.size} photo pairs tagged successfully",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (needsAttention.isNotEmpty()) {
                        Text(
                            "${needsAttention.size} need your attention — see below",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // "processed", not "written" — fileCount includes files that ended up
                    // skipped/failed/needing recovery too, and the line above already
                    // covers success/failure; this is purely the time/volume stats.
                    // duration is null only if results were somehow set without ever going
                    // through startRun() (never happens in the real app) — the fallback
                    // just omits the stats line rather than showing something odd.
                    if (duration != null) {
                        Text(
                            "$fileCount file${if (fileCount == 1) "" else "s"} processed in ${formatDuration(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (needsAttention.isNotEmpty()) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Needs attention", style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider()
                    needsAttention.forEach { AttentionCard(it) }
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

private val PairWriteResult.isFullySuccessful: Boolean
    get() = listOfNotNull(jpegResult, rawResult).all { it is GpsExifWriteResult.Written }

@Composable
private fun AttentionCard(result: PairWriteResult) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.pair.baseName, style = MaterialTheme.typography.bodyMedium)
            result.jpegResult?.let { DetailLine("JPEG", it) }
            result.rawResult?.let { DetailLine("RAW", it) }
        }
    }
}

@Composable
private fun DetailLine(label: String, result: GpsExifWriteResult) {
    val text = when (result) {
        is GpsExifWriteResult.Written -> "$label: written"
        is GpsExifWriteResult.SkippedAlreadyTagged -> "$label: skipped — already had GPS data"
        is GpsExifWriteResult.UnsupportedFormat -> "$label: unsupported format (${result.mimeType ?: "unknown"})"
        is GpsExifWriteResult.Failed -> "$label: failed — ${result.reason}. Original file was not touched."
        is GpsExifWriteResult.BackupArtifactPresent ->
            "$label: skipped — an earlier interrupted write left \"${result.backupFileName}\" behind. " +
                "Resolve it from the recovery screen before this file can be tagged."
        is GpsExifWriteResult.NeedsRecovery ->
            "$label: interrupted partway through. Nothing is lost — a good copy of the original is " +
                "safe as \"${result.backupFileName}\" and the tagged result may be recoverable as " +
                "\"${result.tempFileName}\" — resolve it from the recovery screen."
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
}

@Preview(showBackground = true, name = "All succeeded")
@Composable
private fun SummaryScreenSuccessPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        SummaryScreenContent(results = PreviewFixtures.runResultsAllSucceeded, duration = PreviewFixtures.runDuration, onDone = {})
    }
}

@Preview(showBackground = true, name = "Needs attention")
@Composable
private fun SummaryScreenAttentionPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        SummaryScreenContent(results = PreviewFixtures.runResultsNeedingAttention, duration = PreviewFixtures.runDuration, onDone = {})
    }
}
