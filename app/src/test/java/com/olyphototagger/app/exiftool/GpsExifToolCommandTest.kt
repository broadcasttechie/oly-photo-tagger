package com.olyphototagger.app.exiftool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsExifToolCommandTest {

    @Test
    fun `builds full argv for a northern eastern hemisphere coordinate`() {
        val args = GpsExifToolCommand.build("/tmp/photo.orf", latitude = 46.4808, longitude = -5.2426)

        assertEquals(
            listOf(
                "-GPSLatitude=46.4808000",
                "-GPSLatitudeRef=N",
                "-GPSLongitude=5.2426000",
                "-GPSLongitudeRef=W",
                "-GPSStatus#=A",
                "-overwrite_original",
                "-m",
                "/tmp/photo.orf"
            ),
            args
        )
    }

    @Test
    fun `always marks GPS status as active`() {
        assertTrue(GpsExifToolCommand.build("/tmp/x.orf", 1.0, 1.0).contains("-GPSStatus#=A"))
        assertTrue(
            GpsExifToolCommand.build("/tmp/x.orf", 1.0, 1.0, altitudeMeters = 10.0)
                .contains("-GPSStatus#=A")
        )
    }

    @Test
    fun `negative latitude and positive longitude map to S and E`() {
        val args = GpsExifToolCommand.build("/tmp/x.orf", latitude = -33.8688, longitude = 151.2093)

        assertTrue(args.contains("-GPSLatitudeRef=S"))
        assertTrue(args.contains("-GPSLongitudeRef=E"))
        assertTrue(args.contains("-GPSLatitude=33.8688000"))
        assertTrue(args.contains("-GPSLongitude=151.2093000"))
    }

    @Test
    fun `altitude is included only when provided, with correct ref for below sea level`() {
        val withoutAltitude = GpsExifToolCommand.build("/tmp/x.orf", 1.0, 1.0)
        assertFalse(withoutAltitude.any { it.startsWith("-GPSAltitude") })

        val aboveSeaLevel = GpsExifToolCommand.build("/tmp/x.orf", 1.0, 1.0, altitudeMeters = 120.5)
        assertTrue(aboveSeaLevel.contains("-GPSAltitude=120.5000000"))
        assertTrue(aboveSeaLevel.contains("-GPSAltitudeRef=0"))

        val belowSeaLevel = GpsExifToolCommand.build("/tmp/x.orf", 1.0, 1.0, altitudeMeters = -50.0)
        assertTrue(belowSeaLevel.contains("-GPSAltitude=50.0000000"))
        assertTrue(belowSeaLevel.contains("-GPSAltitudeRef=1"))
    }

    @Test
    fun `target path is always the last argument`() {
        val args = GpsExifToolCommand.build("/some/path with spaces/img.orf", 1.0, 1.0, 5.0)
        assertEquals("/some/path with spaces/img.orf", args.last())
    }

    @Test
    fun `decimal formatting uses a period regardless of default locale`() {
        val originalDefault = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            val args = GpsExifToolCommand.build("/tmp/x.orf", 46.4808, -5.2426)
            assertTrue(args.any { it == "-GPSLatitude=46.4808000" })
            assertFalse(args.any { it.contains(",") })
        } finally {
            java.util.Locale.setDefault(originalDefault)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects out-of-range latitude`() {
        GpsExifToolCommand.build("/tmp/x.orf", latitude = 91.0, longitude = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects out-of-range longitude`() {
        GpsExifToolCommand.build("/tmp/x.orf", latitude = 0.0, longitude = 181.0)
    }
}
