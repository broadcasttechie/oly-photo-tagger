package com.olyphototagger.app.dawarich

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun `missing altitude maps to null`() {
        val dto = DawarichPointDto(latitude = "1.0", longitude = "1.0", timestamp = 0)
        assertNull(requireNotNull(dto.toTrackPointOrNull()).altitudeMeters)
    }

    @Test
    fun `explicit JSON null altitude maps to null`() {
        val dto = DawarichPointDto(latitude = "1.0", longitude = "1.0", timestamp = 0, altitude = JsonNull)
        assertNull(requireNotNull(dto.toTrackPointOrNull()).altitudeMeters)
    }

    @Test
    fun `altitude as a bare JSON number parses`() {
        // The legacy integer `altitude` column serializes this way.
        val dto = DawarichPointDto(latitude = "1.0", longitude = "1.0", timestamp = 0, altitude = JsonPrimitive(38))
        assertEquals(38.0, requireNotNull(dto.toTrackPointOrNull()).altitudeMeters!!, 1e-9)
    }

    @Test
    fun `altitude as a JSON string parses`() {
        // The newer `altitude_decimal` column serializes as a string (Rails BigDecimal
        // convention) — both shapes must be handled since which column a row uses
        // depends on whether it's been backfilled.
        val dto = DawarichPointDto(latitude = "1.0", longitude = "1.0", timestamp = 0, altitude = JsonPrimitive("38.5"))
        assertEquals(38.5, requireNotNull(dto.toTrackPointOrNull()).altitudeMeters!!, 1e-9)
    }
}
