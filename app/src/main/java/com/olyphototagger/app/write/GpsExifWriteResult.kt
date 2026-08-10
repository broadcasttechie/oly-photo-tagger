package com.olyphototagger.app.write

import java.time.Instant

/**
 * Everything a future persistent change log would need to record is captured here
 * deliberately, even though the log itself isn't built yet.
 */
sealed interface GpsExifWriteResult {

    data class Written(
        val previousLatLong: Pair<Double, Double>?,
        val newLatitude: Double,
        val newLongitude: Double,
        val newAltitudeMeters: Double?,
        val writtenAt: Instant,
        /** Non-null only if the now-redundant backup from [SafeFileSwap] couldn't be
         *  deleted — the file itself is already confirmed correctly tagged at this point,
         *  so this is informational (something [IncompleteWriteScanner] will clean up
         *  later), never a reason to treat the write as unsuccessful. */
        val strayBackupFileName: String? = null
    ) : GpsExifWriteResult

    data class SkippedAlreadyTagged(
        val existingLatitude: Double,
        val existingLongitude: Double
    ) : GpsExifWriteResult

    /** RAW formats (ORF) and anything else outside JPEG/PNG/WebP land here — never attempted. */
    data class UnsupportedFormat(val mimeType: String?) : GpsExifWriteResult

    /** The original was never touched; at worst a stray, harmless .tmp sits alongside it. */
    data class Failed(val reason: String, val cause: Throwable? = null) : GpsExifWriteResult

    /**
     * Refused to even start: a `.bak` file from an earlier interrupted write already sits
     * next to this photo. Writing again on top of an unresolved backup risks losing
     * whichever of the two (the old original, or the previous attempt's tagged result) the
     * user actually needed — so this file is skipped entirely until
     * [com.olyphototagger.app.write.IncompleteWriteRecoverer] resolves it.
     */
    data class BackupArtifactPresent(val backupFileName: String) : GpsExifWriteResult

    /**
     * The one state that needs attention: the original was renamed to [backupFileName], but
     * [SafeFileSwap] couldn't rename (or couldn't verify the rename of) the temp file into
     * the original's name. Nothing is corrupted — a known-good copy of the original is safe
     * under [backupFileName], and the new tagged data (if the rename call itself even ran)
     * may still be recoverable under [tempFileName] — but the photo needs
     * [com.olyphototagger.app.write.IncompleteWriteRecoverer] to sort out which one wins.
     */
    data class NeedsRecovery(val tempFileName: String, val backupFileName: String) : GpsExifWriteResult
}
