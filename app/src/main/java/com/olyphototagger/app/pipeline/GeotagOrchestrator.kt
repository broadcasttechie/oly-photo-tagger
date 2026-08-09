package com.olyphototagger.app.pipeline

import androidx.documentfile.provider.DocumentFile
import com.olyphototagger.app.cache.GeoTagCacheDao
import com.olyphototagger.app.cache.GeoTagCacheEntity
import com.olyphototagger.app.dawarich.DawarichClient
import com.olyphototagger.app.dcim.CameraFile
import com.olyphototagger.app.dcim.DcimScanResult
import com.olyphototagger.app.dcim.DcimScanner
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.dcim.PhotoPairer
import com.olyphototagger.app.dcim.identityKey
import com.olyphototagger.app.exif.PhotoExifStatus
import com.olyphototagger.app.exif.PhotoExifStatusReader
import com.olyphototagger.app.exif.toInstant
import com.olyphototagger.app.geotag.GeoInterpolator
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.write.GpsExifWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Ties the individual engines — scanner, pairer, EXIF readers, Dawarich client,
 * interpolator, writer — into the actual two-phase workflow: [scanForMatches] produces a
 * dry-run preview with no writes; [applyMatch] performs the write for one confirmed match.
 */
class GeotagOrchestrator(
    private val dcimScanner: DcimScanner,
    private val exifStatusReader: PhotoExifStatusReader,
    private val geoTagCacheDao: GeoTagCacheDao,
    private val dawarichClient: DawarichClient,
    private val geoInterpolator: GeoInterpolator,
    private val gpsExifWriter: GpsExifWriter
) {

    /**
     * Scans [dcimRoot], resolves each pair's tagged-status and timestamp — using the
     * geotag cache to skip opening files already known tagged — and matches the
     * remaining candidates against the Dawarich track for their combined time span. No
     * writes happen here.
     *
     * @param includeAlreadyTagged main workflow leaves this false: already-tagged pairs
     *   are skipped, and if the cache already knows that, never even opened. The stretch
     *   "tag reviewer" goal would set this true to surface them instead.
     * @param dateRange restricts which pairs are considered by capture timestamp, so a
     *   whole card doesn't get processed — or even track-fetched for — at once.
     */
    suspend fun scanForMatches(
        dcimRoot: DocumentFile,
        assumedOffsetForNaiveTimestamps: ZoneOffset,
        dateRange: ClosedRange<Instant>? = null,
        includeAlreadyTagged: Boolean = false
    ): ScanResult {
        val scan = dcimScanner.scan(dcimRoot)
        val pairing = PhotoPairer.pair(scan.files)

        val included = mutableListOf<Pair<PhotoPair, Instant>>()
        val excluded = mutableListOf<ExcludedPair>()

        for (pair in pairing.pairs) {
            val status = resolvePairStatus(pair, scan, assumedOffsetForNaiveTimestamps)
            when (val decision = PairFilter.decide(status, includeAlreadyTagged, dateRange)) {
                is PairDecision.Include -> included += pair to decision.timestamp
                PairDecision.ExcludeAlreadyTagged -> excluded += ExcludedPair(pair, ExcludeReason.ALREADY_TAGGED)
                PairDecision.ExcludeNoTimestamp -> excluded += ExcludedPair(pair, ExcludeReason.NO_TIMESTAMP)
                PairDecision.ExcludeOutsideDateRange -> excluded += ExcludedPair(pair, ExcludeReason.OUTSIDE_DATE_RANGE)
            }
        }

        if (included.isEmpty()) {
            return ScanResult(emptyList(), excluded, pairing.ignored, pairing.conflicts, scan::resolve)
        }

        val start = included.minOf { it.second }
        val end = included.maxOf { it.second }
        val track = dawarichClient.fetchTrackPoints(
            start.minus(TRACK_FETCH_SLACK_MINUTES, ChronoUnit.MINUTES),
            end.plus(TRACK_FETCH_SLACK_MINUTES, ChronoUnit.MINUTES)
        )

        val matches = included.map { (pair, timestamp) ->
            ProposedMatch(pair, timestamp, geoInterpolator.match(timestamp, track))
        }

        return ScanResult(matches, excluded, pairing.ignored, pairing.conflicts, scan::resolve)
    }

    /** Writes a confirmed match's coordinates to both files in its pair (whichever are present). */
    suspend fun applyMatch(
        scan: ScanResult,
        match: ProposedMatch,
        overwriteExisting: Boolean = false
    ): PairWriteResult {
        val geo = match.geoMatch as? GeoMatch.Matched
            ?: throw IllegalArgumentException("applyMatch requires a Matched GeoMatch, was ${match.geoMatch}")

        val jpegResult = match.pair.jpeg?.let { writeOne(scan, it, geo, overwriteExisting) }
        val rawResult = match.pair.raw?.let { writeOne(scan, it, geo, overwriteExisting) }
        return PairWriteResult(match.pair, jpegResult, rawResult)
    }

    private suspend fun writeOne(
        scan: ScanResult,
        file: CameraFile,
        geo: GeoMatch.Matched,
        overwriteExisting: Boolean
    ) = gpsExifWriter.write(
        original = scan.resolve(file)
            ?: error("No DocumentFile for ${file.displayName} — was it resolved from this scan?"),
        latitude = geo.latitude,
        longitude = geo.longitude,
        altitudeMeters = geo.altitudeMeters,
        overwriteExisting = overwriteExisting
    )

    /**
     * Resolves a pair's combined tagged-status + timestamp. Checks the geotag cache
     * first — a file the cache already knows is tagged is never opened at all. JPEG is
     * checked before RAW: cheaper to open, and it short-circuits the RAW check entirely
     * whenever the JPEG side alone already settles "this pair is tagged."
     */
    private suspend fun resolvePairStatus(
        pair: PhotoPair,
        scan: DcimScanResult,
        assumedOffsetForNaiveTimestamps: ZoneOffset
    ): PairGeoStatus {
        val jpegStatus = pair.jpeg?.let { resolveFileStatus(it, scan) }
        if (jpegStatus?.hasGeoTag == true) {
            return PairGeoStatus(hasExistingGeoTag = true, timestamp = null)
        }

        val rawStatus = pair.raw?.let { resolveFileStatus(it, scan) }
        val hasGeoTag = jpegStatus?.hasGeoTag == true || rawStatus?.hasGeoTag == true
        val timestamp = (jpegStatus?.captureTimestamp ?: rawStatus?.captureTimestamp)
            ?.toInstant(assumedOffsetForNaiveTimestamps)
        return PairGeoStatus(hasGeoTag, timestamp)
    }

    private suspend fun resolveFileStatus(file: CameraFile, scan: DcimScanResult): PhotoExifStatus? {
        val key = file.identityKey()
        val cached = geoTagCacheDao.get(key)
        if (cached?.hasGeoTag == true) {
            // Known tagged from a previous scan — skip opening the file entirely.
            return PhotoExifStatus(hasGeoTag = true, captureTimestamp = null)
        }

        val documentFile = scan.resolve(file) ?: return null
        val status = exifStatusReader.read(documentFile.uri) ?: return null
        geoTagCacheDao.upsert(GeoTagCacheEntity(key, status.hasGeoTag, Instant.now().toEpochMilli()))
        return status
    }

    companion object {
        private const val TRACK_FETCH_SLACK_MINUTES = 30L
    }
}
