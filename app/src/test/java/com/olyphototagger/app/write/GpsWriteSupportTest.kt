package com.olyphototagger.app.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(GpsWriteSupport.coordinatesMatch(46.4808, 2.2426, 46.4808, 2.2426))
    }

    @Test
    fun `small rounding difference within tolerance matches`() {
        assertTrue(GpsWriteSupport.coordinatesMatch(46.48080001, 2.24260001, 46.4808, 2.2426))
    }

    @Test
    fun `difference outside tolerance does not match`() {
        assertFalse(GpsWriteSupport.coordinatesMatch(46.4809, 2.2426, 46.4808, 2.2426))
    }

    @Test
    fun `difference in longitude alone is caught`() {
        assertFalse(GpsWriteSupport.coordinatesMatch(46.4808, 2.2500, 46.4808, 2.2426))
    }

    @Test
    fun `custom tolerance is honored`() {
        assertFalse(
            GpsWriteSupport.coordinatesMatch(
                46.4808001, 2.2426, 46.4808, 2.2426, toleranceDegrees = 0.00000005
            )
        )
        assertTrue(
            GpsWriteSupport.coordinatesMatch(
                46.4808001, 2.2426, 46.4808, 2.2426, toleranceDegrees = 0.0001
            )
        )
    }

    @Test
    fun `parseArtifactName recognizes the idealized tmp form with no extra extension`() {
        val artifact = GpsWriteSupport.parseArtifactName("P8080743.JPG.tmp")

        assertEquals("P8080743.JPG", artifact?.recoveredName)
        assertEquals(true, artifact?.isTemp)
    }

    @Test
    fun `parseArtifactName recognizes the idealized bak form with no extra extension`() {
        val artifact = GpsWriteSupport.parseArtifactName("P8080743.JPG.bak")

        assertEquals("P8080743.JPG", artifact?.recoveredName)
        assertEquals(false, artifact?.isTemp)
    }

    @Test
    fun `parseArtifactName recognizes the real on-device form with SAF's extra appended extension`() {
        // Confirmed 2026-08-11 against a real SAF-backed folder during a genuine crash
        // simulation: DocumentFile.createFile(mimeType = "image/jpeg", "P8080743.JPG.tmp")
        // actually created "P8080743.JPG.tmp.jpg" on disk, not the literal requested name —
        // the provider appends its own recognized extension since ".tmp" isn't one. A naive
        // "does this file's last extension equal tmp" check misses this file entirely.
        val artifact = GpsWriteSupport.parseArtifactName("P8080743.JPG.tmp.jpg")

        assertEquals("P8080743.JPG", artifact?.recoveredName)
        assertEquals(true, artifact?.isTemp)
    }

    @Test
    fun `parseArtifactName recognizes a bak with SAF's extra appended extension too`() {
        val artifact = GpsWriteSupport.parseArtifactName("P8080744.ORF.bak.orf")

        assertEquals("P8080744.ORF", artifact?.recoveredName)
        assertEquals(false, artifact?.isTemp)
    }

    @Test
    fun `parseArtifactName is case-insensitive on the tmp or bak marker`() {
        assertEquals("P8080743.JPG", GpsWriteSupport.parseArtifactName("P8080743.JPG.TMP")?.recoveredName)
        assertEquals("P8080743.JPG", GpsWriteSupport.parseArtifactName("P8080743.JPG.Bak")?.recoveredName)
    }

    @Test
    fun `parseArtifactName returns null for an ordinary file with no tmp or bak segment`() {
        assertNull(GpsWriteSupport.parseArtifactName("P8080743.JPG"))
        assertNull(GpsWriteSupport.parseArtifactName("P8080743.ORF"))
    }

    @Test
    fun `parseArtifactName uses the first tmp or bak segment, since SAF only ever appends after it`() {
        val artifact = GpsWriteSupport.parseArtifactName("P8080743.JPG.tmp.jpg")

        // Not "P8080743.JPG.tmp" (stopping at a hypothetical later marker) — the first
        // occurrence is always the one this app itself asked for.
        assertEquals("P8080743.JPG", artifact?.recoveredName)
    }
}
