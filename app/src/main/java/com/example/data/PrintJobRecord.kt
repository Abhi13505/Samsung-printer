package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PrintJobStatus(val label: String) {
    COMPLETED("Printed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

@Entity(tableName = "print_jobs")
data class PrintJobRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileName: String,
    val documentType: String,
    val pageCount: Int,
    val copies: Int,
    val paperSize: String,
    val qualityLabel: String,
    val bytesTransferred: Long,
    val durationMs: Long,
    val status: String,
    val errorMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)
