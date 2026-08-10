package com.olyphototagger.app.gpx

import com.olyphototagger.app.cache.GpxImportedFileEntity
import com.olyphototagger.app.cache.GpxTrackPointEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GpxTrackSourceTest {

    private fun point(epochSeconds: Long, fileId: Long, lat: Double) =
        GpxTrackPointEntity(importedFileId = fileId, epochSeconds = epochSeconds, latitude = lat, longitude = 0.0, altitudeMeters = null)

    private fun file(displayName: String, pointCount: Int, earliest: Long, latest: Long) =
        GpxImportedFileEntity(
            displayName = displayName,
            importedAtEpochMillis = 0,
            pointCount = pointCount,
            earliestPointEpochSeconds = earliest,
            latestPointEpochSeconds = latest
        )

    @Test
    fun `fetches points within range, mapped to TrackPoint`() = runTest {
        val dao = FakeGpxTrackDao()
        val fileId = dao.insertFile(file("a.gpx", 1, 100, 200))
        dao.insertPoints(listOf(point(100, fileId, 1.0), point(200, fileId, 2.0)))
        val source = GpxTrackSource(dao)

        val points = source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))

        assertEquals(2, points.size)
        assertEquals(listOf(1.0, 2.0), points.map { it.latitude })
    }

    @Test
    fun `pools points from multiple imported files within range`() = runTest {
        val dao = FakeGpxTrackDao()
        val fileA = dao.insertFile(file("saturday.gpx", 1, 100, 100))
        val fileB = dao.insertFile(file("sunday.gpx", 1, 150, 150))
        dao.insertPoints(listOf(point(100, fileA, 10.0), point(150, fileB, 20.0)))
        val source = GpxTrackSource(dao)

        val points = source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(1000))

        assertEquals(setOf(10.0, 20.0), points.map { it.latitude }.toSet())
    }

    @Test
    fun `excludes points outside the requested range`() = runTest {
        val dao = FakeGpxTrackDao()
        val fileId = dao.insertFile(file("a.gpx", 2, 100, 300))
        dao.insertPoints(listOf(point(100, fileId, 1.0), point(300, fileId, 2.0)))
        val source = GpxTrackSource(dao)

        val points = source.fetchTrackPoints(Instant.ofEpochSecond(0), Instant.ofEpochSecond(150))

        assertEquals(1, points.size)
        assertEquals(1.0, points.single().latitude, 1e-9)
    }
}
