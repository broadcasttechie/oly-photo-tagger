package com.olyphototagger.app.gpx

import com.olyphototagger.app.geotag.TrackPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses GPX 1.0/1.1 track points into [TrackPoint]s. DOM-based
 * ([javax.xml.parsers]/[org.w3c.dom]) rather than a pull parser, deliberately: both are
 * part of the standard library on the JVM and Android alike, so this needs zero new
 * dependency — not even a test-only one — and stays fully unit-testable off-device. GPX
 * files at the scale this app deals with (a day's log, a couple thousand points at most)
 * are trivial to hold as a full DOM tree.
 *
 * Deliberately tolerant, matching [com.olyphototagger.app.dawarich.DawarichPointDto]'s
 * "one bad point shouldn't fail the batch" policy: a `<trkpt>` missing `<time>` or with
 * unparsable `lat`/`lon` is skipped, not fatal. Matches elements by tag name only (no
 * namespace handling) — every real GPX generator checked emits an unprefixed default
 * namespace, so this covers GPX 1.0 and 1.1 identically without needing to know which.
 * `<wpt>`/`<rte>` are ignored entirely — only `<trkpt>` (wherever it sits under any
 * `<trk>`/`<trkseg>`, flattened) matters for this app's matching. Every other child
 * element real-world loggers commonly add (`<geoidheight>`, `<src>`, `<sat>`, `<hdop>`,
 * `<vdop>`, `<pdop>`, `<speed>`, `<course>`, ...) is silently ignored — this only ever
 * reads `<ele>` and `<time>`.
 */
object GpxParser {

    fun parse(input: InputStream): List<TrackPoint> {
        val document = secureDocumentBuilderFactory().newDocumentBuilder().parse(input)

        val points = mutableListOf<TrackPoint>()
        val trkpts = document.getElementsByTagName("trkpt")
        for (i in 0 until trkpts.length) {
            val element = trkpts.item(i) as? Element ?: continue
            toTrackPointOrNull(element)?.let { points += it }
        }
        return points.sortedBy { it.time }
    }

    private fun toTrackPointOrNull(trkpt: Element): TrackPoint? {
        val lat = trkpt.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = trkpt.getAttribute("lon").toDoubleOrNull() ?: return null

        var time: Instant? = null
        var elevation: Double? = null
        var child = trkpt.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                when (child.nodeName) {
                    "time" -> time = child.textContent?.trim()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    "ele" -> elevation = child.textContent?.trim()?.toDoubleOrNull()
                }
            }
            child = child.nextSibling
        }

        val resolvedTime = time ?: return null
        return TrackPoint(time = resolvedTime, latitude = lat, longitude = lon, altitudeMeters = elevation)
    }

    /**
     * A GPX file can come from a share-intent (chunk 8) or an arbitrary file-picker
     * selection — untrusted input. Disabling DOCTYPE outright is the standard,
     * OWASP-recommended defense against XXE: it structurally rules out the whole attack
     * class (custom entity definitions require a DOCTYPE), rather than trying to
     * selectively disable individual entity-resolution features. No real GPX file needs
     * a DOCTYPE, so this costs nothing functionally.
     */
    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
}
