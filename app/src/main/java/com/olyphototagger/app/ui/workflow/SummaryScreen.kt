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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    viewModel: GeotagWorkflowViewModel,
    onDone: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SummaryScreenContent(results = uiState.runResults.orEmpty(), onDone = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryScreenContent(
    results: List<PairWriteResult>,
    onDone: () -> Unit
) {
    val succeeded = results.count { it.isFullySuccessful }
    val needsAttention = results.filterNot { it.isFullySuccessful }

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
        is GpsExifWriteResult.RenameFailedAfterDelete ->
            "$label: WRITE SUCCEEDED but the final rename failed. The tagged file exists " +
                "as \"${result.tempFileName}\" instead of its normal name — rename it back manually."
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
}

@Preview(showBackground = true, name = "All succeeded")
@Composable
private fun SummaryScreenSuccessPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        SummaryScreenContent(results = PreviewFixtures.runResultsAllSucceeded, onDone = {})
    }
}

@Preview(showBackground = true, name = "Needs attention")
@Composable
private fun SummaryScreenAttentionPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        SummaryScreenContent(results = PreviewFixtures.runResultsNeedingAttention, onDone = {})
    }
}
