package com.olyphototagger.app.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class ExifTimestampParserTest {

    @Test
    fun `date with no offset or subsec is naive`() {
        val result = ExifTimestampParser.parse("2026:08:09 14:29:04")

        assertTrue(result is CaptureTimestamp.Naive)
        assertEquals(
            LocalDateTime.of(2026, 8, 9, 14, 29, 4),
            (result as CaptureTimestamp.Naive).localDateTime
        )
    }

    @Test
    fun `date with offset is exact`() {
        val result = ExifTimestampParser.parse("2026:08:09 14:29:04", offsetTimeOriginal = "+01:00")

        assertTrue(result is CaptureTimestamp.Exact)
        assertEquals(
            LocalDateTime.of(2026, 8, 9, 14, 29, 4).toInstant(ZoneOffset.of("+01:00")),
            (result as CaptureTimestamp.Exact).instant
        )
    }

    @Test
    fun `negative offset is honored`() {
        val result = ExifTimestampParser.parse("2026:08:09 14:29:04", offsetTimeOriginal = "-05:00")
            as CaptureTimestamp.Exact
        assertEquals(Instant.parse("2026-08-09T19:29:04Z"), result.instant)
    }

    @Test
    fun `single digit subsec means tenths not hundredths`() {
        val naive = ExifTimestampParser.parse(
            "2026:08:09 14:29:04",
            subSecTimeOriginal = "5"
        ) as CaptureTimestamp.Naive
        assertEquals(500_000_000, naive.localDateTime.nano)
    }

    @Test
    fun `two and three digit subsec agree with single digit equivalent`() {
        val fromTwoDigits = ExifTimestampParser.parse(
            "2026:08:09 14:29:04", subSecTimeOriginal = "50"
        ) as CaptureTimestamp.Naive
        val fromThreeDigits = ExifTimestampParser.parse(
            "2026:08:09 14:29:04", subSecTimeOriginal = "500"
        ) as CaptureTimestamp.Naive

        assertEquals(500_000_000, fromTwoDigits.localDateTime.nano)
        assertEquals(500_000_000, fromThreeDigits.localDateTime.nano)
    }

    @Test
    fun `subsec applies before offset conversion`() {
        val result = ExifTimestampParser.parse(
            dateTimeOriginal = "2026:08:09 14:29:04",
            subSecTimeOriginal = "5",
            offsetTimeOriginal = "+00:00"
        ) as CaptureTimestamp.Exact

        assertEquals(Instant.parse("2026-08-09T14:29:04.5Z"), result.instant)
    }

    @Test
    fun `missing date is null`() {
        assertNull(ExifTimestampParser.parse(null))
        assertNull(ExifTimestampParser.parse(""))
        assertNull(ExifTimestampParser.parse("   "))
    }

    @Test
    fun `all-zero placeholder date is null`() {
        assertNull(ExifTimestampParser.parse("0000:00:00 00:00:00"))
    }

    @Test
    fun `garbage date does not throw`() {
        assertNull(ExifTimestampParser.parse("not a date"))
    }

    @Test
    fun `malformed offset falls back to naive rather than throwing`() {
        val result = ExifTimestampParser.parse(
            "2026:08:09 14:29:04",
            offsetTimeOriginal = "garbage"
        )
        assertTrue(result is CaptureTimestamp.Naive)
    }

    @Test
    fun `toInstant ignores assumed offset for exact timestamps`() {
        val exact = ExifTimestampParser.parse(
            "2026:08:09 14:29:04", offsetTimeOriginal = "+01:00"
        )!!
        assertEquals(
            Instant.parse("2026-08-09T13:29:04Z"),
            exact.toInstant(ZoneOffset.of("+09:00"))
        )
    }

    @Test
    fun `toInstant applies assumed offset for naive timestamps`() {
        val naive = ExifTimestampParser.parse("2026:08:09 14:29:04")!!
        assertEquals(
            Instant.parse("2026-08-09T13:29:04Z"),
            naive.toInstant(ZoneOffset.of("+01:00"))
        )
    }
}
