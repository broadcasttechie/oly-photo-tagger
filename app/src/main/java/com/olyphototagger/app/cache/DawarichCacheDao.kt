package com.olyphototagger.app.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DawarichCacheDao {

    /** Null means no single previously-fetched range fully covers
     *  `[startEpochSeconds, endEpochSeconds]` — the caller needs a real fetch. */
    @Query(
        "SELECT * FROM dawarich_fetched_range WHERE startEpochSeconds <= :startEpochSeconds " +
            "AND endEpochSeconds >= :endEpochSeconds LIMIT 1"
    )
    suspend fun findCoveringRange(startEpochSeconds: Long, endEpochSeconds: Long): DawarichFetchedRangeEntity?

    @Query(
        "SELECT * FROM dawarich_track_point WHERE epochSeconds BETWEEN :startEpochSeconds AND :endEpochSeconds " +
            "ORDER BY epochSeconds ASC"
    )
    suspend fun pointsInRange(startEpochSeconds: Long, endEpochSeconds: Long): List<DawarichTrackPointEntity>

    @Insert
    suspend fun insertPoints(points: List<DawarichTrackPointEntity>)

    @Insert
    suspend fun insertFetchedRange(range: DawarichFetchedRangeEntity)

    @Query("DELETE FROM dawarich_track_point")
    suspend fun clearPoints()

    @Query("DELETE FROM dawarich_fetched_range")
    suspend fun clearFetchedRanges()

    /** The Settings screen's "Clear cached GPS data" button — both tables together, since
     *  a fetched-range row with no matching points (or vice versa) would be meaningless. */
    @Transaction
    suspend fun clear() {
        clearFetchedRanges()
        clearPoints()
    }
}
