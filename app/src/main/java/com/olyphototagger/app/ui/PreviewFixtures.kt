package com.olyphototagger.app.ui

import com.olyphototagger.app.dcim.CameraFile
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.pipeline.ExcludeReason
import com.olyphototagger.app.pipeline.ExcludedPair
import com.olyphototagger.app.pipeline.PairWriteResult
import com.olyphototagger.app.pipeline.PreScanSummary
import com.olyphototagger.app.pipeline.ProposedMatch
import com.olyphototagger.app.pipeline.ScanResult
import com.olyphototagger.app.cache.WriteLogResultType
import com.olyphototagger.app.ui.settings.GpxFileUiState
import com.olyphototagger.app.ui.settings.WriteLogEntryUiState
import com.olyphototagger.app.ui.workflow.RunProgress
import com.olyphototagger.app.ui.workflow.ScanProgress
import com.olyphototagger.app.write.GpsExifWriteResult
import com.olyphototagger.app.write.IncompleteWrite
import com.olyphototagger.app.write.IncompleteWriteClassification
import java.time.Duration
import java.time.Instant

/**
 * Sample data for @Preview composables only — never touches real photos, network, or
 * DataStore. Real screens take a ViewModel and collect its StateFlow; each screen's
 * -Content() composable takes plain state instead so these can drive it directly.
 */
internal object PreviewFixtures {

    private fun photo(baseName: String, hasRaw: Boolean = true) = PhotoPair(
        folderName = "100OLYMP",
        baseName = baseName,
        jpeg = CameraFile(
            uriString = "content://fake/$baseName.JPG",
            displayName = "$baseName.JPG",
            folderName = "100OLYMP",
            sizeBytes = 12_000_000,
            lastModified = Instant.parse("2026-08-08T13:24:56Z")
        ),
        raw = if (hasRaw) {
            CameraFile(
                uriString = "content://fake/$baseName.ORF",
                displayName = "$baseName.ORF",
                folderName = "100OLYMP",
                sizeBytes = 19_000_000,
                lastModified = Instant.parse("2026-08-08T13:24:56Z")
            )
        } else null
    )

    val matched = listOf(
        ProposedMatch(
            photo("P8080743"),
            Instant.parse("2026-08-08T13:24:56Z"),
            GeoMatch.Matched(53.122827, -2.075001, 38.0, Duration.ofSeconds(4), Duration.ofSeconds(2))
        ),
        ProposedMatch(
            photo("P8080744"),
            Instant.parse("2026-08-08T13:26:10Z"),
            GeoMatch.Matched(53.124833, -2.075267, 41.0, Duration.ofSeconds(6), Duration.ofSeconds(3))
        )
    )
    val gapTooLarge = listOf(
        ProposedMatch(
            photo("P8080757"),
            Instant.parse("2026-08-08T14:02:00Z"),
            GeoMatch.GapTooLarge(Duration.ofMinutes(14), Duration.ofMinutes(5))
        )
    )
    val outsideTrack = listOf(
        ProposedMatch(
            photo("P8080790"),
            Instant.parse("2026-08-08T18:40:00Z"),
            GeoMatch.OutsideTrack(Duration.ofHours(2))
        )
    )
    val excluded = listOf(
        ExcludedPair(photo("P8080700"), ExcludeReason.ALREADY_TAGGED),
        ExcludedPair(photo("P8080701", hasRaw = false), ExcludeReason.NO_TIMESTAMP)
    )

    val scanResult = ScanResult(
        matches = matched + gapTooLarge + outsideTrack,
        excluded = excluded,
        ignoredFiles = emptyList(),
        conflicts = emptyList(),
        resolver = { null }
    )

    val preScanSummary = PreScanSummary(
        needsTagging = matched.size,
        alreadyTagged = 3,
        noTimestamp = 1,
        outsideDateRange = 0,
        ignoredFiles = 0,
        conflicts = 0
    )

    val runProgress = RunProgress(
        completed = 3,
        total = 5,
        currentAction = "Wrote P8080744",
        startedAt = Instant.now().minusSeconds(12)
    )

    val scanProgress = ScanProgress(
        completed = 340,
        total = 1000,
        startedAt = Instant.now().minusSeconds(40)
    )

    val runDuration: Duration = Duration.ofSeconds(47)

    val runResultsAllSucceeded = matched.map {
        PairWriteResult(
            pair = it.pair,
            jpegResult = GpsExifWriteResult.Written(null, 53.1, -2.1, 38.0, Instant.now()),
            rawResult = GpsExifWriteResult.Written(null, 53.1, -2.1, 38.0, Instant.now())
        )
    }

    val runResultsNeedingAttention = runResultsAllSucceeded + listOf(
        PairWriteResult(
            pair = photo("P8080757"),
            jpegResult = GpsExifWriteResult.Written(null, 53.1, -2.1, 38.0, Instant.now()),
            rawResult = GpsExifWriteResult.NeedsRecovery("P8080757.ORF.tmp", "P8080757.ORF.bak")
        ),
        PairWriteResult(
            pair = photo("P8080760", hasRaw = false),
            jpegResult = GpsExifWriteResult.Failed("Verification mismatch after write"),
            rawResult = null
        ),
        PairWriteResult(
            pair = photo("P8080774"),
            jpegResult = GpsExifWriteResult.BackupArtifactPresent("P8080774.JPG.bak"),
            rawResult = GpsExifWriteResult.BackupArtifactPresent("P8080774.ORF.bak")
        )
    )

    val gpxFilesImported = listOf(
        GpxFileUiState(
            id = 1,
            displayName = "saturday.gpx",
            pointCount = 842,
            earliest = Instant.parse("2026-08-08T08:00:00Z"),
            latest = Instant.parse("2026-08-08T18:30:00Z")
        ),
        GpxFileUiState(
            id = 2,
            displayName = "sunday.gpx",
            pointCount = 630,
            earliest = Instant.parse("2026-08-09T09:15:00Z"),
            latest = Instant.parse("2026-08-09T17:45:00Z")
        )
    )

    private fun recoveryFile(name: String) = CameraFile(
        uriString = "content://fake/100OLYMP/$name",
        displayName = name,
        folderName = "100OLYMP",
        sizeBytes = 12_000_000,
        lastModified = Instant.parse("2026-08-08T13:24:56Z")
    )

    val pendingRecoveries = listOf(
        IncompleteWrite(
            folderName = "100OLYMP",
            recoveredName = "P8080743.JPG",
            original = null,
            temp = recoveryFile("P8080743.JPG.tmp"),
            backup = recoveryFile("P8080743.JPG.bak"),
            classification = IncompleteWriteClassification.AwaitingChoice
        ),
        IncompleteWrite(
            folderName = "100OLYMP",
            recoveredName = "P8080744.ORF",
            original = recoveryFile("P8080744.ORF"),
            temp = recoveryFile("P8080744.ORF.tmp"),
            backup = null,
            classification = IncompleteWriteClassification.StaleTempOnly
        ),
        IncompleteWrite(
            folderName = "100OLYMP",
            recoveredName = "P8080757.JPG",
            original = recoveryFile("P8080757.JPG"),
            temp = null,
            backup = recoveryFile("P8080757.JPG.bak"),
            classification = IncompleteWriteClassification.OriginalAndBackupPresent
        )
    )

    /** A batch dominated by one trivial, bulk-eligible case — the real-world shape found
     *  2026-08-11 when a full SD card left ~200 stray temp files behind, all StaleTempOnly. */
    val manyPendingRecoveries = (1..12).map { i ->
        IncompleteWrite(
            folderName = "100OLYMP",
            recoveredName = "P811%04d.JPG".format(i),
            original = recoveryFile("P811%04d.JPG".format(i)),
            temp = recoveryFile("P811%04d.JPG.tmp".format(i)),
            backup = null,
            classification = IncompleteWriteClassification.StaleTempOnly
        )
    } + pendingRecoveries

    val writeLogEntries = listOf(
        WriteLogEntryUiState(
            id = 1,
            loggedAt = Instant.parse("2026-08-11T09:24:56Z"),
            folderName = "100OLYMP",
            displayName = "P8080743.JPG",
            resultType = WriteLogResultType.WRITTEN,
            previousLatLong = null,
            newLatLong = 53.122827 to -2.075001,
            newAltitudeMeters = 38.0,
            detail = null
        ),
        WriteLogEntryUiState(
            id = 2,
            loggedAt = Instant.parse("2026-08-11T09:24:58Z"),
            folderName = "100OLYMP",
            displayName = "P8080743.ORF",
            resultType = WriteLogResultType.UNSUPPORTED_FORMAT,
            previousLatLong = null,
            newLatLong = null,
            newAltitudeMeters = null,
            detail = "Unsupported format (image/x-olympus-orf)"
        ),
        WriteLogEntryUiState(
            id = 3,
            loggedAt = Instant.parse("2026-08-11T09:25:10Z"),
            folderName = "100OLYMP",
            displayName = "P8080744.JPG",
            resultType = WriteLogResultType.SKIPPED_ALREADY_TAGGED,
            previousLatLong = 53.124833 to -2.075267,
            newLatLong = null,
            newAltitudeMeters = null,
            detail = null
        ),
        WriteLogEntryUiState(
            id = 4,
            loggedAt = Instant.parse("2026-08-11T09:25:22Z"),
            folderName = "100OLYMP",
            displayName = "P8080760.JPG",
            resultType = WriteLogResultType.FAILED,
            previousLatLong = null,
            newLatLong = null,
            newAltitudeMeters = null,
            detail = "Verification mismatch after write"
        )
    )
}
