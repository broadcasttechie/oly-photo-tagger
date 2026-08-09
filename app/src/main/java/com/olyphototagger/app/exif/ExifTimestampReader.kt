package com.olyphototagger.app.exif

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Reads a capture timestamp from a photo's EXIF via the Storage Access Framework. Supports
 * JPEG and, per AndroidX ExifInterface's documented format list, Olympus ORF — though only
 * for reading; ORF *write* support is unvalidated (see project notes) and out of scope here.
 */
class ExifTimestampReader(private val contentResolver: ContentResolver) {

    fun read(uri: Uri): CaptureTimestamp? {
        val exif = contentResolver.openInputStream(uri)?.use(::ExifInterface) ?: return null
        return ExifTimestampParser.parse(
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            subSecTimeOriginal = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
            offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        )
    }
}
