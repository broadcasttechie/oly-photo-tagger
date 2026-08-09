package com.olyphototagger.app.dawarich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DawarichPointDtoTest {

    @Test
    fun `parses negative coordinates`() {
        val dto = DawarichPointDto(latitude = "-33.8688", longitude = "151.2093", timestamp = 1786280032)

        val point = requireNotNull(dto.toTrackPointOrNull())
        assertEquals(-33.8688, point.latitude, 1e-9)
        assertEquals(151.2093, point.longitude, 1e-9)
        assertEquals(Instant.ofEpochSecond(1786280032), point.time)
    }

    @Test
    fun `empty coordinate string is unparsable`() {
        assertNull(DawarichPointDto(latitude = "", longitude = "1.0", timestamp = 0).toTrackPointOrNull())
    }

    @Test
    fun `non-numeric coordinate is unparsable`() {
        assertNull(DawarichPointDto(latitude = "unknown", longitude = "1.0", timestamp = 0).toTrackPointOrNull())
    }
}
