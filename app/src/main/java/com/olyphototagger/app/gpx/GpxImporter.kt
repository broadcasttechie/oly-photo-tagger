package com.olyphototagger.app.gpx

import com.olyphototagger.app.cache.GpxImportedFileEntity
import com.olyphototagger.app.cache.GpxTrackDao
import com.olyphototagger.app.cache.toEntity
import java.io.InputStream
import java.time.Instant

class GpxImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class GpxImportSummary(
    val displayName: String,
    val pointCount: Int,
    val earliest: Instant,
    val latest: Instant
)

/**
 * Parses a GPX input stream and persists its points via [GpxTrackDao], as a new
 * [GpxImportedFileEntity] row. Never touches or replaces any previously-imported file —
 * multiple imports coexist and pool together at match time (see [GpxTrackDao]'s doc),
 * per the decision that separate trips' logs should merge rather than one import wiping
 * out the last.
 */
class GpxImporter(private val dao: GpxTrackDao) {

    suspend fun import(input: InputStream, displayName: String): GpxImportSummary {
        val points = try {
            GpxParser.parse(input)
        } catch (e: Exception) {
            throw GpxImportException("Could not parse $displayName: ${e.message}", e)
        }
        if (points.isEmpty()) {
            throw GpxImportException("$displayName has no usable track points")
        }

        // GpxParser guarantees ascending order.
        val earliest = points.first().time
        val latest = points.last().time

        val fileId = dao.insertFile(
            GpxImportedFileEntity(
                displayName = displayName,
                importedAtEpochMillis = Instant.now().toEpochMilli(),
                pointCount = points.size,
                earliestPointEpochSeconds = earliest.epochSecond,
                latestPointEpochSeconds = latest.epochSecond
            )
        )
        dao.insertPoints(points.map { it.toEntity(fileId) })

        return GpxImportSummary(displayName, points.size, earliest, latest)
    }
}
