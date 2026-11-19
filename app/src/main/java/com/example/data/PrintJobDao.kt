package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintJobDao {
    @Query("SELECT * FROM print_jobs ORDER BY timestampMs DESC")
    fun observeAllJobs(): Flow<List<PrintJobRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(record: PrintJobRecord)

    @Query("DELETE FROM print_jobs WHERE id = :id")
    suspend fun deleteJobById(id: Long)

    @Query("DELETE FROM print_jobs")
    suspend fun clearAllJobs()
}
