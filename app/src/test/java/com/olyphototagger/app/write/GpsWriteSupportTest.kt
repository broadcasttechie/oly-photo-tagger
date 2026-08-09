package com.olyphototagger.app.write

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsWriteSupportTest {

    @Test
    fun `jpeg png and webp are supported for writing`() {
        assertTrue(GpsWriteSupport.isSupportedForWriting("image/jpeg"))
        assertTrue(GpsWriteSupport.isSupportedForWriting("image/png"))
        assertTrue(GpsWriteSupport.isSupportedForWriting("image/webp"))
    }

    @Test
    fun `ORF and other raw formats are not supported for writing`() {
        assertFalse(GpsWriteSupport.isSupportedForWriting("image/x-olympus-orf"))
        assertFalse(GpsWriteSupport.isSupportedForWriting("image/x-adobe-dng"))
        assertFalse(GpsWriteSupport.isSupportedForWriting("image/x-canon-cr2"))
    }

    @Test
    fun `unknown or missing mime type is not supported`() {
        assertFalse(GpsWriteSupport.isSupportedForWriting(null))
        assertFalse(GpsWriteSupport.isSupportedForWriting("application/octet-stream"))
        assertFalse(GpsWriteSupport.isSupportedForWriting(""))
    }

    @Test
    fun `exact coordinate match`() {
        assertTrue(GpsWriteSupport.coordinatesMatch(53.4808, -2.2426, 53.4808, -2.2426))
    }

    @Test
    fun `small rounding difference within tolerance matches`() {
        assertTrue(GpsWriteSupport.coordinatesMatch(53.48080001, -2.24260001, 53.4808, -2.2426))
    }

    @Test
    fun `difference outside tolerance does not match`() {
        assertFalse(GpsWriteSupport.coordinatesMatch(53.4809, -2.2426, 53.4808, -2.2426))
    }

    @Test
    fun `difference in longitude alone is caught`() {
        assertFalse(GpsWriteSupport.coordinatesMatch(53.4808, -2.2500, 53.4808, -2.2426))
    }

    @Test
    fun `custom tolerance is honored`() {
        assertFalse(
            GpsWriteSupport.coordinatesMatch(
                53.4808001, -2.2426, 53.4808, -2.2426, toleranceDegrees = 0.00000005
            )
        )
        assertTrue(
            GpsWriteSupport.coordinatesMatch(
                53.4808001, -2.2426, 53.4808, -2.2426, toleranceDegrees = 0.0001
            )
        )
    }
}
