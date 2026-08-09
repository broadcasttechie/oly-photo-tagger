package com.olyphototagger.app.exif

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parses the raw EXIF DateTimeOriginal / SubSecTimeOriginal / OffsetTimeOriginal tag
 * strings into a [CaptureTimestamp]. Pure string parsing — no ExifInterface or other
 * Android dependency — so the part of EXIF timestamp handling with real logic to get
 * wrong (missing tags, malformed offsets, the "0000:00:00" no-clock placeholder some
 * cameras write) stays unit-testable on the JVM. [ExifTimestampReader] is the thin layer
 * that extracts these raw strings from an actual file.
 */
object ExifTimestampParser {

    private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun parse(
        dateTimeOriginal: String?,
        subSecTimeOriginal: String? = null,
        offsetTimeOriginal: String? = null
    ): CaptureTimestamp? {
        val raw = dateTimeOriginal?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("0000:00:00")) return null

        val base = try {
            LocalDateTime.parse(raw, DATE_TIME_FORMAT)
        } catch (e: DateTimeException) {
            return null
        }.plusNanos(parseSubSecNanos(subSecTimeOriginal))

        val offset = offsetTimeOriginal?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                ZoneOffset.of(it)
            } catch (e: DateTimeException) {
                null
            }
        }

        return if (offset != null) CaptureTimestamp.Exact(base.toInstant(offset))
        else CaptureTimestamp.Naive(base)
    }

    /** EXIF subsec strings are decimal digits after the point, e.g. "5" == "50" == 0.5s. */
    private fun parseSubSecNanos(subSec: String?): Long {
        val digits = subSec?.trim().orEmpty().filter { it.isDigit() }
        if (digits.isEmpty()) return 0L
        return ("0.$digits".toDouble() * 1_000_000_000L).toLong()
    }
}
