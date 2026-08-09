package com.olyphototagger.app.write

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/**
 * Writes GPS coordinates into a photo's EXIF in place, via SAF, following the required
 * safety sequence: copy to a sibling temp file → write → reread and verify → only then
 * delete the original and rename the temp file over it. A failure at any point leaves
 * either the untouched original or an orphaned `.tmp` — never a corrupted real filename.
 *
 * Only JPEG/PNG/WebP are attempted (see [GpsWriteSupport]) — RAW formats like ORF are
 * read-only in AndroidX ExifInterface and are routed to [GpsExifWriteResult.UnsupportedFormat]
 * without ever touching the file.
 */
class GpsExifWriter(private val contentResolver: ContentResolver) {

    suspend fun write(
        original: DocumentFile,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = null,
        overwriteExisting: Boolean = false
    ): GpsExifWriteResult = withContext(Dispatchers.IO) {
        val mimeType = original.type
        if (!GpsWriteSupport.isSupportedForWriting(mimeType)) {
            return@withContext GpsExifWriteResult.UnsupportedFormat(mimeType)
        }

        val originalName = original.name
            ?: return@withContext GpsExifWriteResult.Failed("Original file has no name")
        val parent = original.parentFile
            ?: return@withContext GpsExifWriteResult.Failed("No parent folder for $originalName")

        val existing = readLatLong(original.uri)
        if (existing != null && !overwriteExisting) {
            return@withContext GpsExifWriteResult.SkippedAlreadyTagged(existing.first, existing.second)
        }

        val tempName = "$originalName.tmp"
        // Clear out any orphan left by a previous interrupted run before starting fresh.
        parent.findFile(tempName)?.delete()

        val temp = parent.createFile(mimeType ?: "application/octet-stream", tempName)
            ?: return@withContext GpsExifWriteResult.Failed("Could not create temp file $tempName")

        try {
            copyBytes(original.uri, temp.uri)
            writeGpsAttributes(temp.uri, latitude, longitude, altitudeMeters)

            val verified = readLatLong(temp.uri)
                ?: return@withContext GpsExifWriteResult.Failed(
                    "Verification read GPS tags back as null after writing"
                )
            if (!GpsWriteSupport.coordinatesMatch(verified.first, verified.second, latitude, longitude)) {
                return@withContext GpsExifWriteResult.Failed(
                    "Verification mismatch: wrote ($latitude, $longitude), read back $verified"
                )
            }
            if (temp.length() <= 0L) {
                return@withContext GpsExifWriteResult.Failed("Temp file is empty after write")
            }

            if (!original.delete()) {
                return@withContext GpsExifWriteResult.Failed("Could not delete original $originalName")
            }
            if (!temp.renameTo(originalName)) {
                return@withContext GpsExifWriteResult.RenameFailedAfterDelete(tempName)
            }

            GpsExifWriteResult.Written(existing, latitude, longitude, altitudeMeters, Instant.now())
        } catch (e: IOException) {
            GpsExifWriteResult.Failed("I/O error: ${e.message}", e)
        }
    }

    private fun copyBytes(source: Uri, destination: Uri) {
        val input = contentResolver.openInputStream(source)
            ?: throw IOException("Could not open input stream for $source")
        input.use { inStream ->
            val output = contentResolver.openOutputStream(destination)
                ?: throw IOException("Could not open output stream for $destination")
            output.use { outStream -> inStream.copyTo(outStream) }
        }
    }

    private fun writeGpsAttributes(uri: Uri, latitude: Double, longitude: Double, altitudeMeters: Double?) {
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("Could not open writable file descriptor for $uri")
        pfd.use {
            val exif = ExifInterface(it.fileDescriptor)
            exif.setLatLong(latitude, longitude)
            if (altitudeMeters != null) {
                exif.setAltitude(altitudeMeters)
            }
            exif.saveAttributes()
        }
    }

    private fun readLatLong(uri: Uri): Pair<Double, Double>? {
        val values = contentResolver.openInputStream(uri)?.use { ExifInterface(it).latLong }
        return values?.let { it[0] to it[1] }
    }
}
