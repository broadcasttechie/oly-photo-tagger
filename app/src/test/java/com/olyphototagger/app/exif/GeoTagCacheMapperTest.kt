package com.olyphototagger.app.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class GeoTagCacheMapperTest {

    private val checkedAt = Instant.parse("2026-09-09T12:00:00Z")

    @Test
    fun `Exact capture timestamp round-trips through the entity`() {
        val status = PhotoExifStatus(
            hasGeoTag = false,
            captureTimestamp = CaptureTimestamp.Exact(Instant.parse("2026-06-07T14:23:01Z"))
        )

        val entity = GeoTagCacheMapper.from("100OLYMP/P8080743.JPG|123|456", status, checkedAt)

        assertEquals(false, entity.hasGeoTag)
        assertEquals(checkedAt.toEpochMilli(), entity.checkedAtEpochMillis)
        assertEquals(Instant.parse("2026-06-07T14:23:01Z").toEpochMilli(), entity.captureTimestampExactEpochMillis)
        assertNull(entity.captureTimestampNaiveLocal)
        assertEquals(status.captureTimestamp, entity.toCaptureTimestamp())
    }

    @Test
    fun `Naive capture timestamp round-trips through the entity without an assumed offset`() {
        val localDateTime = LocalDateTime.parse("2026-06-07T14:23:01.5")
        val status = PhotoExifStatus(hasGeoTag = true, captureTimestamp = CaptureTimestamp.Naive(localDateTime))

        val entity = GeoTagCacheMapper.from("key", status, checkedAt)

        assertEquals(true, entity.hasGeoTag)
        assertNull(entity.captureTimestampExactEpochMillis)
        assertEquals(localDateTime.toString(), entity.captureTimestampNaiveLocal)
        assertEquals(CaptureTimestamp.Naive(localDateTime), entity.toCaptureTimestamp())
    }

    @Test
    fun `Absent capture timestamp maps to both columns null and back to null`() {
        val status = PhotoExifStatus(hasGeoTag = false, captureTimestamp = null)

        val entity = GeoTagCacheMapper.from("key", status, checkedAt)

        assertNull(entity.captureTimestampExactEpochMillis)
        assertNull(entity.captureTimestampNaiveLocal)
        assertNull(entity.toCaptureTimestamp())
    }
}
