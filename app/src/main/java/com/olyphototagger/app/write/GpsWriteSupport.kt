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

    /**
     * Centralized here — not inlined separately in [SafeFileSwap], [GpsExifWriter], and the
     * incomplete-write detector — because those three each need to agree on this exact
     * convention for crash recovery to work at all; a drift between them would silently
     * break it.
     */
    fun tempNameFor(originalName: String): String = "$originalName.tmp"
    fun backupNameFor(originalName: String): String = "$originalName.bak"

    /**
     * Recognizes a `.tmp`/`.bak` artifact and recovers the original name it belongs to,
     * tolerating a real quirk confirmed on-device (2026-08-11, genuine crash simulation
     * against a real SAF-backed folder — not the raw-`File`-backed `DocumentFile`s this
     * write path is otherwise JVM-unit-tested against): `DocumentFile.createFile()` on a
     * real SAF tree silently appends its own extra extension matching the given MIME type
     * whenever the requested display name doesn't already end in one it recognizes — asking
     * for `tempNameFor("P8080743.JPG")` ("P8080743.JPG.tmp") with mimeType image/jpeg
     * actually creates "P8080743.JPG.tmp.jpg" on disk. A naive check for "does this file's
     * *last* extension equal tmp/bak" — which is what a plain [tempNameFor]/[backupNameFor]
     * round-trip assumes — silently fails to recognize that file at all, which is a real
     * safety problem, not just cosmetic: the orphaned temp file holds fully-written,
     * already-verified tagged data, and a caller that can't find it has no way to offer
     * "finish tagging" as a recovery option, or to clean it up on a later write attempt to
     * the same file — it's just permanently invisible clutter.
     *
     * (`renameTo()` does not have this quirk — confirmed the `.bak` side, produced via
     * rename rather than createFile, always keeps its exact requested name. Both are matched
     * the same way here regardless, since nothing about relying on that holding on every
     * provider is guaranteed.)
     *
     * Matches a bare "tmp"/"bak" dot-segment anywhere in [displayName] after the first, not
     * just as the literal final extension — the *first* such segment is used, since SAF only
     * ever appends *after* what this app asks for, never before. This handles the idealized
     * "X.tmp" form (what this app's own JVM tests produce, since real-`File`-backed
     * `DocumentFile`s don't have this quirk) and the real on-device "X.tmp.jpg" form
     * identically.
     */
    fun parseArtifactName(displayName: String): ArtifactName? {
        val segments = displayName.split('.')
        val markerIndex = (1 until segments.size).firstOrNull { i ->
            segments[i].equals("tmp", ignoreCase = true) || segments[i].equals("bak", ignoreCase = true)
        } ?: return null
        return ArtifactName(
            recoveredName = segments.subList(0, markerIndex).joinToString("."),
            isTemp = segments[markerIndex].equals("tmp", ignoreCase = true)
        )
    }
}

/** Result of [GpsWriteSupport.parseArtifactName]. [isTemp] is false when the artifact is a backup. */
data class ArtifactName(val recoveredName: String, val isTemp: Boolean)
