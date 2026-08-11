package com.olyphototagger.app.pipeline

import com.olyphototagger.app.dcim.CameraFile
import com.olyphototagger.app.dcim.PhotoPair
import com.olyphototagger.app.geotag.GeoMatch
import com.olyphototagger.app.write.GpsExifWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class OutOfSpaceGuardTest {

    private fun jpeg(name: String) = CameraFile(
        uriString = "content://fake/$name.JPG",
        displayName = "$name.JPG",
        folderName = "100OLYMP",
        sizeBytes = 12_000_000,
        lastModified = Instant.parse("2026-08-11T12:00:00Z")
    )

    private fun raw(name: String) = CameraFile(
        uriString = "content://fake/$name.ORF",
        displayName = "$name.ORF",
        folderName = "100OLYMP",
        sizeBytes = 19_000_000,
        lastModified = Instant.parse("2026-08-11T12:00:00Z")
    )

    private fun pair(name: String, hasRaw: Boolean = true) = PhotoPair(
        folderName = "100OLYMP",
        baseName = name,
        jpeg = jpeg(name),
        raw = if (hasRaw) raw(name) else null
    )

    private fun match(name: String, hasRaw: Boolean = true) = ProposedMatch(
        pair(name, hasRaw),
        Instant.parse("2026-08-11T12:00:00Z"),
        GeoMatch.Matched(46.89, 2.20, 150.0, Duration.ofSeconds(2), Duration.ofSeconds(2))
    )

    private val written = GpsExifWriteResult.Written(null, 46.89, 2.20, 150.0, Instant.now())

    @Test
    fun `a real ENOSPC failure is recognized`() {
        val result = PairWriteResult(
            pair = pair("P8110012"),
            jpegResult = GpsExifWriteResult.Failed("I/O error: write failed: ENOSPC (No space left on device)"),
            rawResult = GpsExifWriteResult.Failed("I/O error: write failed: ENOSPC (No space left on device)")
        )
        assertTrue(OutOfSpaceGuard.indicatesOutOfSpace(result))
    }

    @Test
    fun `an ENOSPC failure on only one of the pair's two files is still recognized`() {
        val result = PairWriteResult(
            pair = pair("P8110012"),
            jpegResult = written,
            rawResult = GpsExifWriteResult.Failed("I/O error: write failed: ENOSPC (No space left on device)")
        )
        assertTrue(OutOfSpaceGuard.indicatesOutOfSpace(result))
    }

    @Test
    fun `a successful result does not indicate out of space`() {
        val result = PairWriteResult(pair = pair("P8110012"), jpegResult = written, rawResult = written)
        assertFalse(OutOfSpaceGuard.indicatesOutOfSpace(result))
    }

    @Test
    fun `an unrelated failure reason does not indicate out of space`() {
        val result = PairWriteResult(
            pair = pair("P8110012"),
            jpegResult = GpsExifWriteResult.Failed("Verification mismatch after write"),
            rawResult = null
        )
        assertFalse(OutOfSpaceGuard.indicatesOutOfSpace(result))
    }

    @Test
    fun `skipped builds a Failed result for every file the pair actually has`() {
        val result = OutOfSpaceGuard.skipped(match("P8110099", hasRaw = true))

        assertTrue(result.jpegResult is GpsExifWriteResult.Failed)
        assertTrue(result.rawResult is GpsExifWriteResult.Failed)
        assertTrue((result.jpegResult as GpsExifWriteResult.Failed).reason.contains("Skipped"))
    }

    @Test
    fun `skipped leaves rawResult null for a JPEG-only pair`() {
        val result = OutOfSpaceGuard.skipped(match("P8110099", hasRaw = false))

        assertTrue(result.jpegResult is GpsExifWriteResult.Failed)
        assertEquals(null, result.rawResult)
    }
}
