package com.olyphototagger.app.gpx

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class GpxImporterTest {

    private val validGpx = """
        <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
          <trk><trkseg>
            <trkpt lat="1.0" lon="2.0"><time>2026-01-01T00:00:00Z</time></trkpt>
            <trkpt lat="3.0" lon="4.0"><time>2026-01-01T00:10:00Z</time></trkpt>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun `imports a valid GPX file and persists its points`() = runTest {
        val dao = FakeGpxTrackDao()
        val importer = GpxImporter(dao)

        val summary = importer.import(stream(validGpx), "saturday.gpx")

        assertEquals("saturday.gpx", summary.displayName)
        assertEquals(2, summary.pointCount)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), summary.earliest)
        assertEquals(Instant.parse("2026-01-01T00:10:00Z"), summary.latest)

        val stored = dao.pointsInRange(0, Instant.parse("2026-01-02T00:00:00Z").epochSecond)
        assertEquals(2, stored.size)
    }

    @Test
    fun `a GPX with no usable points throws GpxImportException`() = runTest {
        val dao = FakeGpxTrackDao()
        val importer = GpxImporter(dao)
        val emptyGpx = """<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"></gpx>"""

        try {
            importer.import(stream(emptyGpx), "empty.gpx")
            fail("Expected GpxImportException")
        } catch (e: GpxImportException) {
            assertTrue(e.message!!.contains("empty.gpx"))
        }
    }

    @Test
    fun `malformed XML throws GpxImportException wrapping the parse failure`() = runTest {
        val dao = FakeGpxTrackDao()
        val importer = GpxImporter(dao)

        try {
            importer.import(stream("not xml at all <<<"), "broken.gpx")
            fail("Expected GpxImportException")
        } catch (e: GpxImportException) {
            assertTrue(e.message!!.contains("broken.gpx"))
        }
    }

    @Test
    fun `two separate imports both persist without interfering with each other`() = runTest {
        val dao = FakeGpxTrackDao()
        val importer = GpxImporter(dao)
        val secondGpx = """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><trkseg>
                <trkpt lat="9.0" lon="9.0"><time>2026-01-02T00:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val first = importer.import(stream(validGpx), "saturday.gpx")
        val second = importer.import(stream(secondGpx), "sunday.gpx")

        assertEquals(2, first.pointCount)
        assertEquals(1, second.pointCount)

        val allStored = dao.pointsInRange(0, Instant.parse("2026-01-03T00:00:00Z").epochSecond)
        assertEquals(3, allStored.size)
    }
}
