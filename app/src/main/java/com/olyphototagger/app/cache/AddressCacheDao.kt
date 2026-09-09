package com.olyphototagger.app.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AddressCacheDao {
    @Query("SELECT * FROM address_cache WHERE bucketKey = :bucketKey")
    suspend fun find(bucketKey: String): AddressCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AddressCacheEntity)

    /** The Settings screen's "Clear Cached Addresses" button — addresses essentially
     *  never change for a given location, so this exists mainly for parity/debugging
     *  rather than any expected real need, unlike the Dawarich GPS cache's clear button. */
    @Query("DELETE FROM address_cache")
    suspend fun clear()
}
