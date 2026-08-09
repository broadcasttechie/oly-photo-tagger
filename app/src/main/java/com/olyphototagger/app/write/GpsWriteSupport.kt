package com.olyphototagger.app.write

import kotlin.math.abs

/**
 * Pure decision logic for [GpsExifWriter], kept separate so it's testable without SAF/
 * ExifInterface/DocumentFile.
 */
object GpsWriteSupport {

    /**
     * AndroidX ExifInterface's saveAttributes() only supports these three formats — confirmed
     * against the library source (androidx.exifinterface 1.3.x): everything else, including
     * RAW formats like ORF, throws IOException. This is a hard library limitation, not a
     * missing feature to work around — RAW needs a different strategy entirely (e.g. an XMP
     * sidecar file) rather than an in-place write attempt.
     */
    private val WRITABLE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

    fun isSupportedForWriting(mimeType: String?): Boolean = mimeType in WRITABLE_MIME_TYPES

    /**
     * EXIF GPS coordinates round-trip through a rational (fraction) encoding, so a
     * bit-for-bit comparison after a write-then-reread would be too strict. A tolerance of
     * 0.00001 degrees is about 1.1m at the equator — comfortably inside EXIF's rounding but
     * tight enough to catch a genuinely wrong value.
     */
    fun coordinatesMatch(
        actualLatitude: Double,
        actualLongitude: Double,
        expectedLatitude: Double,
        expectedLongitude: Double,
        toleranceDegrees: Double = 0.00001
    ): Boolean =
        abs(actualLatitude - expectedLatitude) < toleranceDegrees &&
            abs(actualLongitude - expectedLongitude) < toleranceDegrees
}
