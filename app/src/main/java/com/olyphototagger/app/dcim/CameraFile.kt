package com.olyphototagger.app.dcim

import java.time.Instant

enum class PhotoKind { JPEG, RAW, OTHER }

/**
 * A file found on the camera volume. Deliberately holds [uriString] rather than
 * android.net.Uri so this model — and the pairing logic that consumes it — stays plain
 * Kotlin, testable on the JVM without Robolectric. Callers resolve it back via
 * `Uri.parse(uriString)` when they need to open the file.
 */
data class CameraFile(
    val uriString: String,
    val displayName: String,
    /** Immediate parent folder name, e.g. "100OLYMP", or "" if directly under DCIM. */
    val folderName: String,
    val sizeBytes: Long,
    val lastModified: Instant?
) {
    val baseName: String get() = displayName.substringBeforeLast('.', displayName)
    val extension: String get() = displayName.substringAfterLast('.', "").uppercase()

    val kind: PhotoKind
        get() = when (extension) {
            "JPG", "JPEG" -> PhotoKind.JPEG
            "ORF" -> PhotoKind.RAW
            else -> PhotoKind.OTHER
        }
}
