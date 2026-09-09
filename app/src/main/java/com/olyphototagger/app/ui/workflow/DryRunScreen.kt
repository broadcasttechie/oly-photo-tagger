package com.olyphototagger.app.ui.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.dcim.identityKey
import com.olyphototagger.app.geocode.AddressResolver
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.image.ThumbnailImageLoader
import com.olyphototagger.app.image.osmTileRequest
import com.olyphototagger.app.pipeline.ExcludeReason
import com.olyphototagger.app.pipeline.ExcludedPair
import com.olyphototagger.app.pipeline.ProposedMatch
import com.olyphototagger.app.pipeline.ScanResult
import com.olyphototagger.app.ui.PreviewFixtures
import com.olyphototagger.app.ui.theme.OlyPhotoTaggerTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    // Purely local, ephemeral display state — which rows have their map preview open —
    // not part of WorkflowUiState: nothing downstream needs it to survive navigating away
    // and back, unlike deselectedPairKeys which must (it feeds the actual write batch).
    var mapExpandedPairKeys by remember { mutableStateOf(emptySet<String>()) }
    DryRunScreenContent(
        scanResult = uiState.scanResult,
        deselectedPairKeys = uiState.deselectedPairKeys,
        mapExpandedPairKeys = mapExpandedPairKeys,
        onBack = onBack,
        onToggleSelection = viewModel::toggleMatchSelection,
        onSetAllSelected = viewModel::setAllMatchesSelected,
        onToggleMapExpanded = { pair ->
            val key = pair.stableKey()
            mapExpandedPairKeys = if (key in mapExpandedPairKeys) mapExpandedPairKeys - key else mapExpandedPairKeys + key
        },
        onConfirmRun = { viewModel.startRun(); onConfirmRun() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DryRunScreenContent(
    scanResult: ScanResult?,
    deselectedPairKeys: Set<String>,
    mapExpandedPairKeys: Set<String>,
    onBack: () -> Unit,
    onToggleSelection: (PhotoPair) -> Unit,
    onSetAllSelected: (Boolean) -> Unit,
    onToggleMapExpanded: (PhotoPair) -> Unit,
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
        val selectedWillWrite = willWrite.filterNot { it.pair.stableKey() in deselectedPairKeys }
        val gapTooLarge = scanResult.matches.filter { it.geoMatch is GeoMatch.GapTooLarge }
        val outsideTrack = scanResult.matches.filter { it.geoMatch is GeoMatch.OutsideTrack }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SummaryCard(scanResult, willWrite.size, selectedWillWrite.size, gapTooLarge.size, outsideTrack.size)

            // LazyColumn, not a plain Column: a real dry-run batch can run into the
            // hundreds or thousands of rows (see the 1000-photo stress test), and a plain
            // Column here doesn't scroll at all once its weighted space runs out — it just
            // clips, invisibly, since every small fixture/test batch used while building
            // this screen fit on one page. Keys must be Bundle-compatible strings, not the
            // raw data classes — see RecoveryScreen's own key fix for why.
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (willWrite.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeaderText("Will be tagged (${selectedWillWrite.size}/${willWrite.size} selected)")
                            TextButton(onClick = { onSetAllSelected(selectedWillWrite.size < willWrite.size) }) {
                                Text(if (selectedWillWrite.size < willWrite.size) "Select all" else "Deselect all")
                            }
                        }
                        HorizontalDivider()
                    }
                    items(willWrite, key = { it.pair.stableKey() }) { match ->
                        val key = match.pair.stableKey()
                        MatchedRow(
                            scanResult = scanResult,
                            match = match,
                            selected = key !in deselectedPairKeys,
                            mapExpanded = key in mapExpandedPairKeys,
                            onToggle = { onToggleSelection(match.pair) },
                            onToggleMapExpanded = { onToggleMapExpanded(match.pair) }
                        )
                    }
                }
                if (gapTooLarge.isNotEmpty()) {
                    item { SectionHeader("Skipped — GPS gap too large (${gapTooLarge.size})") }
                    items(gapTooLarge, key = { it.pair.stableKey() }) {
                        SkippedRow(scanResult, it, "GPS points too far apart in time")
                    }
                }
                if (outsideTrack.isNotEmpty()) {
                    item { SectionHeader("Skipped — outside GPS track (${outsideTrack.size})") }
                    items(outsideTrack, key = { it.pair.stableKey() }) {
                        SkippedRow(scanResult, it, "No nearby GPS data")
                    }
                }
                if (scanResult.excluded.isNotEmpty()) {
                    item { SectionHeader("Not considered (${scanResult.excluded.size})") }
                    items(scanResult.excluded, key = { it.pair.stableKey() }) { ExcludedRow(it) }
                }
                if (scanResult.conflicts.isNotEmpty()) {
                    item { SectionHeader("Ambiguous duplicates, skipped (${scanResult.conflicts.size})") }
                    items(scanResult.conflicts, key = { it.identityKey() }) {
                        Text("  ${it.displayName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = onConfirmRun,
                enabled = selectedWillWrite.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (selectedWillWrite.isEmpty()) "Nothing to write"
                    else "Write GPS to ${selectedWillWrite.size} photo${if (selectedWillWrite.size == 1) "" else "s"}"
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(scanResult: ScanResult, willWrite: Int, selectedWillWrite: Int, gapTooLarge: Int, outsideTrack: Int) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (selectedWillWrite == willWrite) {
                    "Will write GPS to $willWrite photo pair${if (willWrite == 1) "" else "s"}"
                } else {
                    "Will write GPS to $selectedWillWrite of $willWrite matched photo pairs — the rest were unchecked below"
                },
                style = MaterialTheme.typography.titleMedium
            )
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
private fun SectionHeaderText(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SectionHeader(text: String) {
    SectionHeaderText(text)
    HorizontalDivider()
}

// Two lines per row, not three — coordinates moved onto the same line as the capture
// time rather than a line of their own. A dry-run batch can run into the hundreds or
// thousands of rows (see the 1000-photo stress test), so this row height compounds a
// lot more than it looks like it should from any one row in isolation. The thumbnail
// and map preview below don't fight that: the thumbnail is a fixed 40dp regardless of
// list size (Coil decodes it downsampled and only for on-screen rows, same LazyColumn
// story as everything else here), and the map is opt-in per row, not shown by default.
@Composable
private fun MatchedRow(
    scanResult: ScanResult,
    match: ProposedMatch,
    selected: Boolean,
    mapExpanded: Boolean,
    onToggle: () -> Unit,
    onToggleMapExpanded: () -> Unit
) {
    val geo = match.geoMatch as GeoMatch.Matched
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = selected, onValueChange = { onToggle() }, role = Role.Checkbox)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
            PhotoThumbnail(scanResult, match.pair, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.padding(vertical = 2.dp).weight(1f)) {
                Text(match.pair.baseName, style = MaterialTheme.typography.bodyMedium)
                // Coordinates show immediately; rememberAddress swaps in a real address
                // once it resolves (near-instant on a cache hit, a background wait on a
                // genuine miss — see its own doc) rather than this row waiting on it.
                val address = rememberAddress(geo.latitude, geo.longitude)
                val locationText = address ?: "%.4f, %.4f".format(geo.latitude, geo.longitude)
                Text(
                    "${formatCaptureTime(match.timestamp)} · $locationText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleMapExpanded, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = if (mapExpanded) "Hide map" else "Show on map",
                    tint = if (mapExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (mapExpanded) {
            MapPreview(geo.latitude, geo.longitude)
        }
    }
}

@Composable
private fun SkippedRow(scanResult: ScanResult, match: ProposedMatch, reason: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoThumbnail(scanResult, match.pair, modifier = Modifier.padding(end = 12.dp))
        Column {
            Text(match.pair.baseName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatCaptureTime(match.timestamp)} · $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** A single static OpenStreetMap tile centered on [latitude]/[longitude] — see
 *  [com.olyphototagger.app.image.osmTileUrl]'s own doc for why this is only ever loaded
 *  on an explicit per-row tap, never automatically for every row in what can be a
 *  thousand-row list. */
@Composable
private fun MapPreview(latitude: Double, longitude: Double) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 8.dp)) {
        AsyncImage(
            model = remember(latitude, longitude) { osmTileRequest(context, latitude, longitude) },
            imageLoader = ThumbnailImageLoader.get(context),
            contentDescription = "Map showing where this photo was taken",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(160.dp)
        )
    }
}

@Composable
private fun ExcludedRow(excluded: ExcludedPair) {
    val reasonText = when (excluded.reason) {
        ExcludeReason.ALREADY_TAGGED -> "already has GPS data"
        ExcludeReason.NO_TIMESTAMP -> "no readable capture timestamp"
        ExcludeReason.OUTSIDE_DATE_RANGE -> "outside the selected date range"
    }
    val whenText = excluded.timestamp?.let { ", ${formatCaptureTime(it)}" }.orEmpty()
    Text(
        "${excluded.pair.baseName}$whenText — $reasonText",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatCaptureTime(instant: Instant): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(instant)

/**
 * Null until resolved — "resolved" usually means "an already-cached bucket," which
 * [AddressResolver.resolve] answers from a plain local Room read, fast enough that a
 * caller rarely even notices the null moment in between. A genuine cache miss instead
 * takes as long as its turn in [AddressResolver]'s rate-limited queue does — this row
 * just keeps showing coordinates until then, never blocking anything.
 *
 * Keyed on (latitude, longitude): a recycled LazyColumn row whose underlying photo
 * changes gets a fresh lookup rather than briefly showing the previous row's stale
 * address, and a row that scrolls away mid-lookup has that lookup cancelled for free
 * (LaunchedEffect leaves composition -> its coroutine is cancelled -> see
 * [AddressResolver]'s own doc for why that's a real, clean cancellation, not a leak).
 */
@Composable
private fun rememberAddress(latitude: Double, longitude: Double): String? {
    val context = LocalContext.current
    var address by remember(latitude, longitude) { mutableStateOf<String?>(null) }
    LaunchedEffect(latitude, longitude) {
        address = AddressResolver.get(context).resolve(latitude, longitude)
    }
    return address?.let(::shortenAddress)
}

/** Nominatim's display_name is a full address, often 6+ comma-separated parts and too
 *  wide for a compact list row ("10 Downing Street, Westminster, London, Greater London,
 *  England, SW1A 2AA, United Kingdom") — the first two parts are normally enough to place
 *  a photo at a glance ("10 Downing Street, Westminster"). The full string is still what's
 *  cached; only display trims it, so a future change here doesn't need a cache reset. */
private fun shortenAddress(fullAddress: String): String =
    fullAddress.split(",").take(2).joinToString(", ") { it.trim() }

@Preview(showBackground = true, name = "Mixed results")
@Composable
private fun DryRunScreenPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        DryRunScreenContent(
            scanResult = PreviewFixtures.scanResult,
            deselectedPairKeys = emptySet(),
            mapExpandedPairKeys = emptySet(),
            onBack = {}, onToggleSelection = {}, onSetAllSelected = {}, onToggleMapExpanded = {}, onConfirmRun = {}
        )
    }
}

@Preview(showBackground = true, name = "One photo unchecked")
@Composable
private fun DryRunScreenPartiallySelectedPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        DryRunScreenContent(
            scanResult = PreviewFixtures.scanResult,
            deselectedPairKeys = setOf(PreviewFixtures.matched.first().pair.stableKey()),
            mapExpandedPairKeys = emptySet(),
            onBack = {}, onToggleSelection = {}, onSetAllSelected = {}, onToggleMapExpanded = {}, onConfirmRun = {}
        )
    }
}

@Preview(showBackground = true, name = "No scan yet")
@Composable
private fun DryRunScreenEmptyPreview() {
    OlyPhotoTaggerTheme(dynamicColor = false) {
        DryRunScreenContent(
            scanResult = null,
            deselectedPairKeys = emptySet(),
            mapExpandedPairKeys = emptySet(),
            onBack = {}, onToggleSelection = {}, onSetAllSelected = {}, onToggleMapExpanded = {}, onConfirmRun = {}
        )
    }
}
