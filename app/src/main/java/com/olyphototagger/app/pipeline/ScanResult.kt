package com.olyphototagger.app.pipeline

import androidx.documentfile.provider.DocumentFile
import com.olyphototagger.app.dcim.CameraFile
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.write.GpsExifWriteResult
import java.time.Instant

enum class ExcludeReason { ALREADY_TAGGED, NO_TIMESTAMP, OUTSIDE_DATE_RANGE }

data class ExcludedPair(val pair: PhotoPair, val reason: ExcludeReason)

/** A pair with a resolved timestamp and its position against the GPS track — the dry-run preview's data. */
data class ProposedMatch(val pair: PhotoPair, val timestamp: Instant, val geoMatch: GeoMatch)

/**
 * Output of [GeotagOrchestrator.scanForMatches] — no writes have happened yet. Carries
 * its own DocumentFile resolver so a caller can hold onto just this one object between
 * the scan and a later [GeotagOrchestrator.applyMatch] call, rather than separately
 * tracking the DcimScanResult it came from.
 */
class ScanResult(
    val matches: List<ProposedMatch>,
    val excluded: List<ExcludedPair>,
    val ignoredFiles: List<CameraFile>,
    val conflicts: List<CameraFile>,
    private val resolver: (CameraFile) -> DocumentFile?
) {
    fun resolve(file: CameraFile): DocumentFile? = resolver(file)
}

data class PairWriteResult(
    val pair: PhotoPair,
    val jpegResult: GpsExifWriteResult?,
    val rawResult: GpsExifWriteResult?
)
