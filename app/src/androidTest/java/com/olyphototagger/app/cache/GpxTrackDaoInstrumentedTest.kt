package com.olyphototagger.app.cache

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpxTrackDaoInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: GpxTrackDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.gpxTrackDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun point(epochSeconds: Long, fileId: Long, lat: Double = 1.0) =
        GpxTrackPointEntity(importedFileId = fileId, epochSeconds = epochSeconds, latitude = lat, longitude = 2.0, altitudeMeters = null)

    private fun file(name: String, count: Int, earliest: Long, latest: Long) =
        GpxImportedFileEntity(
            displayName = name,
            importedAtEpochMillis = System.currentTimeMillis(),
            pointCount = count,
            earliestPointEpochSeconds = earliest,
            latestPointEpochSeconds = latest
        )

    @Test
    fun insertsFileAndPoints_andRangeQueryFindsThem(): Unit = runBlocking {
        val fileId = dao.insertFile(file("a.gpx", 2, 100, 200))
        dao.insertPoints(listOf(point(100, fileId), point(200, fileId)))

        val inRange = dao.pointsInRange(50, 250)

        assertEquals(2, inRange.size)
        assertEquals(listOf(100L, 200L), inRange.map { it.epochSeconds })
    }

    @Test
    fun deletingAFile_cascadesToDeleteItsPoints(): Unit = runBlocking {
        val fileId = dao.insertFile(file("a.gpx", 1, 100, 100))
        dao.insertPoints(listOf(point(100, fileId)))

        dao.deleteFile(fileId)

        assertTrue(dao.pointsInRange(0, 1000).isEmpty())
        assertTrue(dao.observeImportedFiles().first().isEmpty())
    }

    @Test
    fun pointsFromMultipleImportedFiles_poolTogetherInRangeQuery(): Unit = runBlocking {
        // Two separate imports (e.g. Saturday's and Sunday's logs) — per the user's
        // decision, both should contribute points to the same range query rather than
        // one replacing the other.
        val fileA = dao.insertFile(file("saturday.gpx", 1, 100, 100))
        val fileB = dao.insertFile(file("sunday.gpx", 1, 150, 150))
        dao.insertPoints(listOf(point(100, fileA, lat = 10.0), point(150, fileB, lat = 20.0)))

        val inRange = dao.pointsInRange(0, 1000)

        assertEquals(2, inRange.size)
        assertEquals(setOf(10.0, 20.0), inRange.map { it.latitude }.toSet())
    }

    @Test
    fun rangeQuery_excludesPointsOutsideTheRequestedWindow(): Unit = runBlocking {
        val fileId = dao.insertFile(file("a.gpx", 3, 100, 300))
        dao.insertPoints(listOf(point(100, fileId), point(200, fileId), point(300, fileId)))

        val inRange = dao.pointsInRange(150, 250)

        assertEquals(listOf(200L), inRange.map { it.epochSeconds })
    }
}
