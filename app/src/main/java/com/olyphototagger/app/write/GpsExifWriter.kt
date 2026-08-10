package com.olyphototagger.app.write

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.olyphototagger.app.dcim.RawFormats
import com.olyphototagger.app.exiftool.ExifToolInvoker
import com.olyphototagger.app.exiftool.GpsExifToolCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Writes GPS coordinates into a photo's EXIF in place, via SAF, following the required
 * safety sequence: copy to a sibling temp file → write → reread and verify → only then
 * delete the original and rename the temp file over it. A failure at any point leaves
 * either the untouched original or an orphaned `.tmp` — never a corrupted real filename.
 *
 * Two write paths, chosen by format:
 *  - JPEG/PNG/WebP ([GpsWriteSupport]) go straight through AndroidX ExifInterface against
 *    the SAF temp file's writable file descriptor.
 *  - RAW formats ExifTool documents write support for ([RawFormats]) can't be written via
 *    ExifInterface at all (confirmed from its source: saveAttributes() only supports
 *    those three formats) and can't be handed to perl/exiftool as a content:// URI either
 *    (perl needs a real filesystem path) — so the original is staged to a real scratch
 *    file, exiftool writes it there, and the result is copied into the SAF temp file
 *    before the same verify/delete/rename sequence takes over. Everything after "how do
 *    the right bytes end up in the temp file" is identical regardless of format, since
 *    reading GPS back for verification is fully supported for both.
 *
 * Anything outside both lists — including RAW formats ExifTool can only read, like 3FR —
 * is routed to [GpsExifWriteResult.UnsupportedFormat] without ever being touched.
 */
class GpsExifWriter(
    private val contentResolver: ContentResolver,
    private val exifToolInvoker: ExifToolInvoker,
    private val scratchDir: File
) {

    suspend fun write(
        original: DocumentFile,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = null,
        overwriteExisting: Boolean = false
    ): GpsExifWriteResult = withContext(Dispatchers.IO) {
        val originalName = original.name
            ?: return@withContext GpsExifWriteResult.Failed("Original file has no name")
        val extension = originalName.substringAfterLast('.', "")
        val mimeType = original.type

        val useExifTool = RawFormats.isWritable(extension)
        if (!useExifTool && !GpsWriteSupport.isSupportedForWriting(mimeType)) {
            return@withContext GpsExifWriteResult.UnsupportedFormat(mimeType)
        }

        val parent = original.parentFile
            ?: return@withContext GpsExifWriteResult.Failed("No parent folder for $originalName")

        val existing = readLatLong(original.uri)
        if (existing != null && !overwriteExisting) {
            return@withContext GpsExifWriteResult.SkippedAlreadyTagged(existing.first, existing.second)
        }

        if (useExifTool) {
            try {
                exifToolInvoker.ensureInstalled()
            } catch (e: IOException) {
                return@withContext GpsExifWriteResult.Failed("Could not install exiftool runtime: ${e.message}", e)
            }
        }

        val tempName = "$originalName.tmp"
        // Clear out any orphan left by a previous interrupted run before starting fresh.
        parent.findFile(tempName)?.delete()

        val temp = parent.createFile(mimeType ?: "application/octet-stream", tempName)
            ?: return@withContext GpsExifWriteResult.Failed("Could not create temp file $tempName")

        try {
            if (useExifTool) {
                writeRawViaExifTool(original.uri, temp.uri, extension, latitude, longitude, altitudeMeters)
            } else {
                copyBytes(original.uri, temp.uri)
                writeGpsAttributes(temp.uri, latitude, longitude, altitudeMeters)
            }

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

    /**
     * Stages [sourceUri] to a real file (perl can't read content:// URIs), runs exiftool
     * against it, then copies the result into [tempUri]. The scratch file always gets
     * cleaned up, success or failure.
     */
    private suspend fun writeRawViaExifTool(
        sourceUri: Uri,
        tempUri: Uri,
        extension: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?
    ) {
        scratchDir.mkdirs()
        val scratch = File(scratchDir, "exiftool_${System.nanoTime()}.$extension")
        try {
            copyUriToFile(sourceUri, scratch)

            val args = GpsExifToolCommand.build(scratch.absolutePath, latitude, longitude, altitudeMeters)
            val result = exifToolInvoker.run(args)
            if (!result.succeeded) {
                throw IOException("exiftool exited ${result.exitCode}: ${result.output}")
            }

            copyFileToUri(scratch, tempUri)
        } finally {
            scratch.delete()
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

    private fun copyUriToFile(source: Uri, destination: File) {
        val input = contentResolver.openInputStream(source)
            ?: throw IOException("Could not open input stream for $source")
        input.use { inStream -> destination.outputStream().use { outStream -> inStream.copyTo(outStream) } }
    }

    private fun copyFileToUri(source: File, destination: Uri) {
        val output = contentResolver.openOutputStream(destination)
            ?: throw IOException("Could not open output stream for $destination")
        output.use { outStream -> source.inputStream().use { inStream -> inStream.copyTo(outStream) } }
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
            // See GpsExifToolCommand's GPSStatus comment: Olympus's own apps/firmware
            // only recognize a photo as geotagged when this is set, even though the
            // lat/long alone are fully valid EXIF. Kept in sync with the RAW write path.
            exif.setAttribute(ExifInterface.TAG_GPS_STATUS, ExifInterface.GPS_MEASUREMENT_IN_PROGRESS)
            exif.saveAttributes()
        }
    }

    private fun readLatLong(uri: Uri): Pair<Double, Double>? {
        val values = contentResolver.openInputStream(uri)?.use { ExifInterface(it).latLong }
        return values?.let { it[0] to it[1] }
    }
}
