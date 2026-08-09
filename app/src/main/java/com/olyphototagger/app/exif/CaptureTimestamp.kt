package com.olyphototagger.app.exif

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A photo's capture time as read from EXIF.
 *
 * Most cameras — including the E-M5 III — don't write OffsetTimeOriginal (an EXIF 2.31
 * addition), so [DateTimeOriginal] is wall-clock time with no recorded UTC offset. That
 * makes [Naive] the common case, not the edge case: turning it into an absolute [Instant]
 * that can be matched against a GPS track needs an externally supplied offset (the
 * camera's clock offset from UTC at capture time), not an assumption baked into this code.
 */
sealed interface CaptureTimestamp {
    data class Exact(val instant: Instant) : CaptureTimestamp
    data class Naive(val localDateTime: LocalDateTime) : CaptureTimestamp
}

fun CaptureTimestamp.toInstant(assumedOffsetForNaive: ZoneOffset): Instant = when (this) {
    is CaptureTimestamp.Exact -> instant
    is CaptureTimestamp.Naive -> localDateTime.toInstant(assumedOffsetForNaive)
}
