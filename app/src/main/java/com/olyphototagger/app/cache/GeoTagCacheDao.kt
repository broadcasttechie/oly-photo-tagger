package com.olyphototagger.app.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeoTagCacheDao {
    @Query("SELECT * FROM geotag_cache WHERE fileKey = :key")
    suspend fun get(key: String): GeoTagCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GeoTagCacheEntity)

    @Query("DELETE FROM geotag_cache")
    suspend fun clear()
}
