package com.olyphototagger.app.exiftool

import java.util.Locale
import kotlin.math.abs

/**
 * Builds the exiftool argv tail for writing GPS coordinates to a file already sitting at
 * a real filesystem path — perl/exiftool can't read content:// SAF URIs directly, so the
 * caller is responsible for staging the file there first (see [ExifToolInvoker]).
 *
 * Always Locale.ROOT-formatted: the platform default locale can use a comma decimal
 * separator (e.g. "53,4808"), which would silently corrupt every coordinate on affected
 * devices if left to the default locale.
 *
 * There's no free-text user input anywhere near this — the app always constructs this
 * exact fixed set of flags itself from numeric values it already validated — so unlike
 * a general-purpose exiftool command builder, there's no injection surface to sanitize
 * against here in the first place.
 */
object GpsExifToolCommand {

    fun build(
        targetPath: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = null
    ): List<String> = buildList {
        require(latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude in -180.0..180.0) { "Invalid longitude: $longitude" }

        add("-GPSLatitude=${decimal(abs(latitude))}")
        add("-GPSLatitudeRef=${if (latitude >= 0) "N" else "S"}")
        add("-GPSLongitude=${decimal(abs(longitude))}")
        add("-GPSLongitudeRef=${if (longitude >= 0) "E" else "W"}")
        if (altitudeMeters != null) {
            add("-GPSAltitude=${decimal(abs(altitudeMeters))}")
            add("-GPSAltitudeRef=${if (altitudeMeters >= 0) "0" else "1"}")
        }
        // 'A' = measurement active/in-progress, per the EXIF spec. Every coordinate this
        // app writes comes from a real interpolated GPS fix, so this is always true — but
        // it also turns out to matter beyond spec compliance: Olympus's own camera
        // firmware and OM Image Share only show a photo as geotagged when this is set,
        // even though the lat/long tags alone are fully valid and read correctly by every
        // generic EXIF reader. Confirmed by diffing a sample OI.Share-tagged ORF against
        // an untagged one — GPSStatus was the only unexplained tag-level difference.
        // The `#` writes the raw 'A'/'V' value directly: plain `-GPSStatus=A` fails with
        // "matches more than one PrintConv" and silently writes nothing at all — verified
        // against a real ORF, not just assumed from exiftool's docs.
        add("-GPSStatus#=A")
        add("-overwrite_original")
        add("-m") // ignore minor errors/warnings (exiftool convention)
        add(targetPath)
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.7f", value)
}
