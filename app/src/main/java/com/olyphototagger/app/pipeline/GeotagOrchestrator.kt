package com.olyphototagger.app.pipeline

import androidx.documentfile.provider.DocumentFile
import com.olyphototagger.app.cache.GeoTagCacheDao
import com.olyphototagger.app.cache.GeoTagCacheEntity
import com.olyphototagger.app.cache.WriteLogDao
import com.olyphototagger.app.dcim.CameraFile
import com.olyphototagger.app.dcim.DcimScanResult
import com.olyphototagger.app.dcim.DcimScanner
import com.olyphototagger.app.dcim.PairingResult
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.dcim.PhotoPairer
import com.olyphototagger.app.dcim.identityKey
import com.olyphototagger.app.exif.PhotoExifStatus
import com.olyphototagger.app.exif.PhotoExifStatusReader
import com.olyphototagger.app.exif.toInstant
import com.olyphototagger.app.geotag.GeoInterpolator
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.geotag.GpsSource
import com.olyphototagger.app.write.GpsExifWriteResult
import com.olyphototagger.app.write.GpsExifWriter
import com.olyphototagger.app.write.StrayArtifactIndex
import com.olyphototagger.app.write.WriteLogMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ties the individual engines — scanner, pairer, EXIF readers, GPS source,
 * interpolator, writer — into the actual two-phase workflow: [scanForMatches] produces a
 * dry-run preview with no writes; [applyMatch] performs the write for one confirmed match.
 */
class GeotagOrchestrator(
    private val dcimScanner: DcimScanner,
    private val exifStatusReader: PhotoExifStatusReader,
    private val geoTagCacheDao: GeoTagCacheDao,
    private val gpsSource: GpsSource,
    private val geoInterpolator: GeoInterpolator,
    private val gpsExifWriter: GpsExifWriter,
    private val writeLogDao: WriteLogDao
) {

    /**
     * Scans [dcimRoot], resolves each pair's tagged-status and timestamp — using the
     * geotag cache to skip opening files already known tagged — and matches the
     * remaining candidates against the active GPS source's track for their combined
     * time span. No writes happen here.
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
        val c = classify(dcimRoot, assumedOffsetForNaiveTimestamps, dateRange, includeAlreadyTagged)

        if (c.included.isEmpty()) {
            return ScanResult(emptyList(), c.excluded, c.pairing.ignored, c.pairing.conflicts, c.scan::resolve)
        }

        val start = c.included.minOf { it.second }
        val end = c.included.maxOf { it.second }
        val track = gpsSource.fetchTrackPoints(
            start.minus(TRACK_FETCH_SLACK_MINUTES, ChronoUnit.MINUTES),
            end.plus(TRACK_FETCH_SLACK_MINUTES, ChronoUnit.MINUTES)
        )

        val matches = c.included.map { (pair, timestamp) ->
            ProposedMatch(pair, timestamp, geoInterpolator.match(timestamp, track))
        }

        return ScanResult(matches, c.excluded, c.pairing.ignored, c.pairing.conflicts, c.scan::resolve)
    }

    /**
     * A quick "how much is there to do" count for the Home screen's optional prescan
     * action — reuses the same cache-first classification as [scanForMatches] but skips
     * the GPS source fetch and interpolation entirely, since a UI showing counts has no
     * reason to hit the network.
     */
    suspend fun preScan(
        dcimRoot: DocumentFile,
        assumedOffsetForNaiveTimestamps: ZoneOffset,
        dateRange: ClosedRange<Instant>? = null,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): PreScanSummary {
        val c = classify(dcimRoot, assumedOffsetForNaiveTimestamps, dateRange, includeAlreadyTagged = false, onProgress = onProgress)
        return PreScanSummary(
            needsTagging = c.included.size,
            alreadyTagged = c.excluded.count { it.reason == ExcludeReason.ALREADY_TAGGED },
            noTimestamp = c.excluded.count { it.reason == ExcludeReason.NO_TIMESTAMP },
            outsideDateRange = c.excluded.count { it.reason == ExcludeReason.OUTSIDE_DATE_RANGE },
            ignoredFiles = c.pairing.ignored.size,
            conflicts = c.pairing.conflicts.size
        )
    }

    private data class Classification(
        val scan: DcimScanResult,
        val pairing: PairingResult,
        val included: List<Pair<PhotoPair, Instant>>,
        val excluded: List<ExcludedPair>
    )

    private suspend fun classify(
        dcimRoot: DocumentFile,
        assumedOffsetForNaiveTimestamps: ZoneOffset,
        dateRange: ClosedRange<Instant>?,
        includeAlreadyTagged: Boolean,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Classification {
        val scan = dcimScanner.scan(dcimRoot)
        val pairing = PhotoPairer.pair(scan.files)

        val statuses = resolveStatusesConcurrently(pairing.pairs, scan, assumedOffsetForNaiveTimestamps, onProgress)

        val included = mutableListOf<Pair<PhotoPair, Instant>>()
        val excluded = mutableListOf<ExcludedPair>()

        for ((pair, status) in statuses) {
            when (val decision = PairFilter.decide(status, includeAlreadyTagged, dateRange)) {
                is PairDecision.Include -> included += pair to decision.timestamp
                PairDecision.ExcludeAlreadyTagged -> excluded += ExcludedPair(pair, ExcludeReason.ALREADY_TAGGED, status.timestamp)
                PairDecision.ExcludeNoTimestamp -> excluded += ExcludedPair(pair, ExcludeReason.NO_TIMESTAMP)
                PairDecision.ExcludeOutsideDateRange -> excluded += ExcludedPair(pair, ExcludeReason.OUTSIDE_DATE_RANGE, status.timestamp)
            }
        }

        return Classification(scan, pairing, included, excluded)
    }

    /**
     * Resolving one pair's tagged-status is dominated by real I/O — a geotag-cache check,
     * and on a cache miss, actually opening the file to read its EXIF — that's completely
     * independent per pair, so this runs them concurrently rather than one at a time. Found
     * necessary by a 1000-photo stress test: sequential, this was several hundred
     * milliseconds per pair, adding up to minutes overall.
     *
     * A [Semaphore] caps how many run at once rather than firing all of them at once —
     * hundreds/thousands of simultaneous SAF file opens would mostly just queue up behind
     * the OS's own binder thread pool anyway, or risk overwhelming the storage provider
     * process for no real gain over a bounded number running at a time.
     *
     * [onProgress] fires once per resolved pair, in completion order rather than [pairs]'
     * order (same reasoning as [applyMatches]' [onEachResult]) — lets [preScan]'s caller
     * drive a live "N of M" + ETA without this function needing to know anything about it.
     */
    private suspend fun resolveStatusesConcurrently(
        pairs: List<PhotoPair>,
        scan: DcimScanResult,
        assumedOffsetForNaiveTimestamps: ZoneOffset,
        onProgress: suspend (completed: Int, total: Int) -> Unit
    ): List<Pair<PhotoPair, PairGeoStatus>> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_STATUS_CHECKS)
        val completedCount = AtomicInteger(0)
        pairs.map { pair ->
            async {
                val status = semaphore.withPermit { resolvePairStatus(pair, scan, assumedOffsetForNaiveTimestamps) }
                onProgress(completedCount.incrementAndGet(), pairs.size)
                pair to status
            }
        }.awaitAll()
    }

    /**
     * Writes a confirmed match's coordinates to both files in its pair (whichever are
     * present). [strayArtifacts] defaults to a fresh, single-use index for a standalone
     * call — [applyMatches] passes one shared instance instead, so a whole batch's worth of
     * pre-write safety checks scan each folder once rather than once per file; see
     * [StrayArtifactIndex]'s own doc for why that's safe.
     */
    suspend fun applyMatch(
        scan: ScanResult,
        match: ProposedMatch,
        overwriteExisting: Boolean = false,
        strayArtifacts: StrayArtifactIndex = gpsExifWriter.newStrayArtifactIndex()
    ): PairWriteResult {
        val geo = match.geoMatch as? GeoMatch.Matched
            ?: throw IllegalArgumentException("applyMatch requires a Matched GeoMatch, was ${match.geoMatch}")

        val jpegResult = match.pair.jpeg?.let { writeOne(scan, it, geo, overwriteExisting, strayArtifacts) }
        val rawResult = match.pair.raw?.let { writeOne(scan, it, geo, overwriteExisting, strayArtifacts) }
        return PairWriteResult(match.pair, jpegResult, rawResult)
    }

    /**
     * Writes every match in [matches] concurrently, bounded to [MAX_CONCURRENT_WRITES] at
     * once rather than [MAX_CONCURRENT_STATUS_CHECKS] like the read-side scan — each write
     * spawns a real exiftool process per file, a much heavier unit of work than a status
     * read, so this is bounded lower.
     *
     * Each pair is independent (a distinct original file, under its own `.tmp`/`.bak`
     * names — see [com.olyphototagger.app.write.GpsWriteSupport], and each write's
     * one-time [com.olyphototagger.app.exiftool.AssetExtractor.ensureInstalled] call is
     * now genuinely safe to race, see its own doc) — but an *unexpected* exception from
     * one (as opposed to a normal [GpsExifWriteResult.Failed] outcome, which
     * [applyMatch] already returns rather than throws) must never cancel every other
     * write already in flight the way plain [coroutineScope] structured-concurrency
     * cancellation would. Caught and converted to the same kind of [GpsExifWriteResult.Failed]
     * outcome instead, so the caller always gets a complete, same-size result list back —
     * if anything, *more* robust than the old sequential loop, which would have aborted
     * every match not yet attempted on the same kind of unexpected throw.
     *
     * [onEachResult] fires once per completed pair, in completion order rather than
     * [matches]' order (several can finish close together when running concurrently) —
     * lets the caller drive live progress UI without this function needing to know
     * anything about it.
     *
     * Once any write fails because its destination is out of space, every match not yet
     * started is skipped rather than attempted — confirmed on-device (2026-08-11, a real
     * SD card that filled up mid-testing) that without this, a full card means grinding
     * through the *entire* remaining batch, each one doing real (temp-file-creating,
     * exiftool-invoking) work only to fail the exact same way. [outOfSpace] is a plain
     * flag, not a hard guarantee: a few writes already past the check when the first
     * failure lands can still race through for real, which is fine — that's the batch
     * genuinely confirming the problem persists, not a bug to close.
     */
    suspend fun applyMatches(
        scan: ScanResult,
        matches: List<ProposedMatch>,
        overwriteExisting: Boolean = false,
        onEachResult: suspend (result: PairWriteResult, completed: Int, total: Int) -> Unit
    ): List<PairWriteResult> = coroutineScope {
        // One shared index for the whole batch — see StrayArtifactIndex's own doc for why
        // a start-of-batch snapshot is safe for every write in this same batch to share.
        val strayArtifacts = gpsExifWriter.newStrayArtifactIndex()
        val semaphore = Semaphore(MAX_CONCURRENT_WRITES)
        val completedCount = AtomicInteger(0)
        val outOfSpace = AtomicBoolean(false)
        matches.map { match ->
            async {
                val result = if (outOfSpace.get()) {
                    OutOfSpaceGuard.skipped(match)
                } else {
                    semaphore.withPermit {
                        applyMatchCatching(scan, match, overwriteExisting, strayArtifacts)
                            .also { if (OutOfSpaceGuard.indicatesOutOfSpace(it)) outOfSpace.set(true) }
                    }
                }
                onEachResult(result, completedCount.incrementAndGet(), matches.size)
                result
            }
        }.awaitAll()
    }

    private suspend fun applyMatchCatching(
        scan: ScanResult,
        match: ProposedMatch,
        overwriteExisting: Boolean,
        strayArtifacts: StrayArtifactIndex
    ): PairWriteResult = try {
        applyMatch(scan, match, overwriteExisting, strayArtifacts)
    } catch (e: CancellationException) {
        throw e // never swallow real cancellation (e.g. the whole batch's scope ending)
    } catch (e: Exception) {
        val failure = GpsExifWriteResult.Failed("Unexpected error: ${e.message}", e)
        PairWriteResult(
            pair = match.pair,
            jpegResult = match.pair.jpeg?.let { failure },
            rawResult = match.pair.raw?.let { failure }
        )
    }

    private suspend fun writeOne(
        scan: ScanResult,
        file: CameraFile,
        geo: GeoMatch.Matched,
        overwriteExisting: Boolean,
        strayArtifacts: StrayArtifactIndex
    ): GpsExifWriteResult {
        val result = gpsExifWriter.write(
            original = scan.resolve(file)
                ?: error("No DocumentFile for ${file.displayName} — was it resolved from this scan?"),
            latitude = geo.latitude,
            longitude = geo.longitude,
            altitudeMeters = geo.altitudeMeters,
            overwriteExisting = overwriteExisting,
            strayArtifacts = strayArtifacts
        )
        // Logged here, the one place every write attempt (whatever the outcome) funnels
        // through, rather than in the ViewModel — so any future caller of applyMatch()
        // gets a complete audit trail for free, not just today's one UI entry point.
        writeLogDao.insert(WriteLogMapper.from(file.folderName, file.displayName, result, Instant.now()))
        return result
    }

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

        /** Bounds concurrent per-pair status resolution in [resolveStatusesConcurrently] —
         *  see that function's doc for why this is bounded rather than unbounded. */
        private const val MAX_CONCURRENT_STATUS_CHECKS = 8

        /** Bounds concurrent writes in [applyMatches] — deliberately much lower than
         *  [MAX_CONCURRENT_STATUS_CHECKS]: each write spawns a real exiftool process
         *  rather than just opening a file, so this is sized to a plausible number of
         *  CPU cores actually doing useful work at once on a phone, not I/O concurrency
         *  headroom. */
        private const val MAX_CONCURRENT_WRITES = 3
    }
}
