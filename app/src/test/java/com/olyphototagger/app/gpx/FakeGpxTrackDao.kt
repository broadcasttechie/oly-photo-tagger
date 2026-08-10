package com.olyphototagger.app.gpx

import com.olyphototagger.app.cache.GpxImportedFileEntity
import com.olyphototagger.app.cache.GpxTrackDao
import com.olyphototagger.app.cache.GpxTrackPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Plain in-memory fake, same isolation idea as DawarichClientTest's MockEngine — no
 * Room/Android needed, so GpxImporter/GpxTrackSource stay JVM-unit-testable.
 */
class FakeGpxTrackDao : GpxTrackDao {
    private val files = mutableListOf<GpxImportedFileEntity>()
    private val points = mutableListOf<GpxTrackPointEntity>()
    private var nextId = 1L

    override suspend fun insertFile(file: GpxImportedFileEntity): Long {
        val id = nextId++
        files += file.copy(id = id)
        return id
    }

    override suspend fun insertPoints(points: List<GpxTrackPointEntity>) {
        this.points += points
    }

    override fun observeImportedFiles(): Flow<List<GpxImportedFileEntity>> = flowOf(files.toList())

    override suspend fun deleteFile(fileId: Long) {
        files.removeAll { it.id == fileId }
        points.removeAll { it.importedFileId == fileId }
    }

    override suspend fun pointsInRange(startEpochSeconds: Long, endEpochSeconds: Long): List<GpxTrackPointEntity> =
        points.filter { it.epochSeconds in startEpochSeconds..endEpochSeconds }.sortedBy { it.epochSeconds }
}
