package com.olyphototagger.app.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WriteLogDao {
    @Insert
    suspend fun insert(entry: WriteLogEntity)

    @Query("SELECT * FROM write_log ORDER BY loggedAtEpochMillis DESC")
    fun observeAll(): Flow<List<WriteLogEntity>>

    @Query("DELETE FROM write_log")
    suspend fun clear()
}
