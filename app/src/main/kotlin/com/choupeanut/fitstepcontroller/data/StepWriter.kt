package com.choupeanut.fitstepcontroller.data

import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import java.time.Instant

interface StepWriter {
    suspend fun write(interval: StepWriteInterval): StepWriteResult
    suspend fun readTotal(start: Instant, end: Instant): Long
}

data class StepWriteResult(
    val recordsWritten: Int,
    val requestedSteps: Long,
)
