package com.olyphototagger.app.cache

import com.olyphototagger.app.geotag.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GpxTrackPointMappingTest {

    @Test
    fun `round-trips through entity, whole-second precision`() {
        val point = TrackPoint(
            time = Instant.ofEpochSecond(1786280032),
            latitude = 46.890059,
            longitude = 2.204761,
            altitudeMeters = 158.4
        )

        val entity = point.toEntity(importedFileId = 7L)
        assertEquals(7L, entity.importedFileId)
        assertEquals(1786280032L, entity.epochSeconds)

        val roundTripped = entity.toTrackPoint()
        assertEquals(point.time, roundTripped.time)
        assertEquals(point.latitude, roundTripped.latitude, 1e-9)
        assertEquals(point.longitude, roundTripped.longitude, 1e-9)
        assertEquals(point.altitudeMeters, roundTripped.altitudeMeters)
    }

    @Test
    fun `null altitude round-trips as null, not zero`() {
        val point = TrackPoint(time = Instant.EPOCH, latitude = 1.0, longitude = 1.0, altitudeMeters = null)

        val roundTripped = point.toEntity(importedFileId = 1L).toTrackPoint()

        assertNull(roundTripped.altitudeMeters)
    }
}
