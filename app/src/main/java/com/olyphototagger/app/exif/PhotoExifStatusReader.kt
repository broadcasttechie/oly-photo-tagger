package com.olyphototagger.app.exif

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

data class PhotoExifStatus(val hasGeoTag: Boolean, val captureTimestamp: CaptureTimestamp?)

/**
 * Reads GPS presence and capture timestamp together in a single file open. The scan needs
 * both, and opening the file twice — once per concern — roughly doubles I/O that matters
 * for RAW files accessed over USB.
 */
class PhotoExifStatusReader(private val contentResolver: ContentResolver) {

    fun read(uri: Uri): PhotoExifStatus? {
        val exif = contentResolver.openInputStream(uri)?.use(::ExifInterface) ?: return null
        val timestamp = ExifTimestampParser.parse(
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            subSecTimeOriginal = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
            offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        )
        return PhotoExifStatus(hasGeoTag = exif.latLong != null, captureTimestamp = timestamp)
    }
}
