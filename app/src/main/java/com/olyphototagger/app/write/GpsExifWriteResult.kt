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
        val writtenAt: Instant
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
     * The one state that needs urgent attention: the original was deleted, but the verified
     * new data only exists under the temp filename because the final rename failed. Nothing
     * is corrupted — the good data is safe under [tempFileName] — but the photo is currently
     * missing from its expected filename until that's manually resolved.
     */
    data class RenameFailedAfterDelete(val tempFileName: String) : GpsExifWriteResult
}
