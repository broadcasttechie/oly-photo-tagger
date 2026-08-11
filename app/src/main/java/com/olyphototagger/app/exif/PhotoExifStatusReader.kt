package com.olyphototagger.app.exif

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhotoExifStatus(val hasGeoTag: Boolean, val captureTimestamp: CaptureTimestamp?)

/**
 * Reads GPS presence and capture timestamp together in a single file open. The scan needs
 * both, and opening the file twice — once per concern — roughly doubles I/O that matters
 * for RAW files accessed over USB.
 */
class PhotoExifStatusReader(private val contentResolver: ContentResolver) {

    /** Self-dispatches to IO — matches [com.olyphototagger.app.dcim.DcimScanner.scan] and
     *  [com.olyphototagger.app.write.GpsExifWriter.write]'s own pattern, so this is safe to
     *  call concurrently from any dispatcher without the caller needing to know that this
     *  does real blocking I/O (a SAF content-resolver stream open, not just an in-memory
     *  read). Previously a plain blocking function — real cost on a real device, found by a
     *  1000-photo stress test that showed the whole scan running with no dispatcher switch
     *  of its own between file opens. */
    suspend fun read(uri: Uri): PhotoExifStatus? = withContext(Dispatchers.IO) {
        val exif = contentResolver.openInputStream(uri)?.use(::ExifInterface) ?: return@withContext null
        val timestamp = ExifTimestampParser.parse(
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            subSecTimeOriginal = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
            offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        )
        PhotoExifStatus(hasGeoTag = exif.latLong != null, captureTimestamp = timestamp)
    }
}
