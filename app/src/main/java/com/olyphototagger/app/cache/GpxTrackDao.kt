package com.olyphototagger.app.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GpxTrackDao {

    /** Returns the generated row id, used as the parent key when inserting its points. */
    @Insert
    suspend fun insertFile(file: GpxImportedFileEntity): Long

    @Insert
    suspend fun insertPoints(points: List<GpxTrackPointEntity>)

    @Query("SELECT * FROM gpx_imported_file ORDER BY importedAtEpochMillis DESC")
    fun observeImportedFiles(): Flow<List<GpxImportedFileEntity>>

    /** Cascades to delete the file's points too (`ON DELETE CASCADE` on the FK). */
    @Query("DELETE FROM gpx_imported_file WHERE id = :fileId")
    suspend fun deleteFile(fileId: Long)

    /** Pools points from every imported file within range — no per-file filtering,
     *  matching the decision that separate imports merge rather than one replacing
     *  another. */
    @Query(
        "SELECT * FROM gpx_track_point WHERE epochSeconds BETWEEN :startEpochSeconds AND :endEpochSeconds ORDER BY epochSeconds ASC"
    )
    suspend fun pointsInRange(startEpochSeconds: Long, endEpochSeconds: Long): List<GpxTrackPointEntity>
}
