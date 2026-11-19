package com.example.data

import kotlinx.coroutines.flow.Flow

class PrintHistoryRepository(private val dao: PrintJobDao) {
    val allJobs: Flow<List<PrintJobRecord>> = dao.observeAllJobs()

    suspend fun recordJob(record: PrintJobRecord) {
        dao.insertJob(record)
    }

    suspend fun deleteJob(id: Long) {
        dao.deleteJobById(id)
    }

    suspend fun clearHistory() {
        dao.clearAllJobs()
    }
}
