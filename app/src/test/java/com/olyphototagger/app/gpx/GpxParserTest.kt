package com.olyphototagger.app.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class GpxParserTest {

    private fun parse(gpx: String) = GpxParser.parse(ByteArrayInputStream(gpx.toByteArray()))

    @Test
    fun `parses a real-shaped GPX 1_0 file with multiple trkseg and GPSLogger extras`() {
        // Matches the actual shape produced by GPSLogger for Android: GPX 1.0 namespace,
        // two <trkseg> under one <trk> (a real gap/restart), extra unknown child
        // elements on some points, millisecond-precision UTC timestamps.
        val gpx = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <gpx version="1.0" creator="GPSLogger 136" xmlns="http://www.topografix.com/GPX/1/0">
              <time>2026-08-09T23:00:41.189Z</time>
              <trk>
                <name>20260810</name>
                <trkseg>
                  <trkpt lat="46.8898949096583" lon="2.2048472305329634">
                    <ele>175.54906656848235</ele>
                    <time>2026-08-09T23:00:41.189Z</time>
                    <geoidheight>49.5</geoidheight>
                    <src>gps</src>
                    <sat>4</sat>
                    <hdop>2.9</hdop>
                    <vdop>1.0</vdop>
                    <pdop>3.1</pdop>
                  </trkpt>
                  <trkpt lat="46.8898949"><ele>158.1</ele></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="46.88999355550389" lon="2.2047649470063333">
                    <ele>183.7580337908914</ele>
                    <time>2026-08-09T23:01:46.781Z</time>
                    <speed>0.0</speed>
                    <course>277.0612</course>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val points = parse(gpx)

        // The second point (missing lon) is dropped; the two valid ones survive, flattened
        // across both <trkseg> into one list.
        assertEquals(2, points.size)
        assertEquals(Instant.parse("2026-08-09T23:00:41.189Z"), points[0].time)
        assertEquals(46.8898949096583, points[0].latitude, 1e-9)
        assertEquals(2.2048472305329634, points[0].longitude, 1e-9)
        assertEquals(175.54906656848235, points[0].altitudeMeters!!, 1e-9)
        assertEquals(Instant.parse("2026-08-09T23:01:46.781Z"), points[1].time)
    }

    @Test
    fun `missing ele is null altitude, not a dropped point`() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0"><time>2026-01-01T00:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val points = parse(gpx)

        assertEquals(1, points.size)
        assertEquals(null, points.single().altitudeMeters)
    }

    @Test
    fun `trkpt missing time is dropped`() {
        val gpx = """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0"><ele>10.0</ele></trkpt>
                <trkpt lat="3.0" lon="4.0"><time>2026-01-01T00:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val points = parse(gpx)

        assertEquals(1, points.size)
        assertEquals(3.0, points.single().latitude, 1e-9)
    }

    @Test
    fun `trkpt with garbage or missing lat lon is dropped`() {
        val gpx = """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><trkseg>
                <trkpt lat="not-a-number" lon="2.0"><time>2026-01-01T00:00:00Z</time></trkpt>
                <trkpt lon="2.0"><time>2026-01-01T00:00:00Z</time></trkpt>
                <trkpt lat="1.0"><time>2026-01-01T00:00:00Z</time></trkpt>
                <trkpt lat="5.0" lon="6.0"><time>2026-01-01T00:00:01Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val points = parse(gpx)

        assertEquals(1, points.size)
        assertEquals(5.0, points.single().latitude, 1e-9)
    }

    @Test
    fun `trkpt with unparsable time is dropped`() {
        val gpx = """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0"><time>not-a-timestamp</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        assertTrue(parse(gpx).isEmpty())
    }

    @Test
    fun `waypoints and routes are ignored, only trkpt matters`() {
        val gpx = """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <wpt lat="9.0" lon="9.0"><time>2026-01-01T00:00:00Z</time><name>Home</name></wpt>
              <rte><rtept lat="8.0" lon="8.0"><time>2026-01-01T00:00:00Z</time></rtept></rte>
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0"><time>2026-01-01T00:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val points = parse(gpx)

        assertEquals(1, points.size)
        assertEquals(1.0, points.single().latitude, 1e-9)
    }

    @Test
    fun `empty gpx yields zero points without crashing`() {
        val gpx = """<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"></gpx>"""

        assertTrue(parse(gpx).isEmpty())
    }

    @Test
    fun `points are returned sorted ascending by time regardless of source order`() {
        val gpx = """
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><trkseg>
                <trkpt lat="2.0" lon="2.0"><time>2026-01-01T00:02:00Z</time></trkpt>
                <trkpt lat="1.0" lon="1.0"><time>2026-01-01T00:01:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val points = parse(gpx)

        assertEquals(
            listOf(Instant.parse("2026-01-01T00:01:00Z"), Instant.parse("2026-01-01T00:02:00Z")),
            points.map { it.time }
        )
    }

    @Test(expected = Exception::class)
    fun `a DOCTYPE declaration is rejected rather than resolved, blocking XXE`() {
        // Untrusted input (share-intent, arbitrary file picker selection) must not be
        // able to smuggle external entity resolution via a DOCTYPE.
        val gpx = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk><trkseg>
                <trkpt lat="1.0" lon="&xxe;"><time>2026-01-01T00:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        parse(gpx)
    }
}
