package com.choupeanut.fitstepcontroller.data

import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import java.time.Instant

interface StepWriter {
    /**
     * Inserts a manual step record and verifies that the exact app-origin record can be
     * read back by its client record ID. A caller must reuse the same request ID when
     * retrying an operation.
     */
    suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite

    /**
     * Legacy interval-only entry point. It remains source-compatible with the service
     * and UI while they migrate to [writeAndVerify]. The deterministic ID makes a retry
     * of the same interval idempotent.
     */
    suspend fun write(interval: StepWriteInterval): StepWriteResult {
        val result = writeAndVerify(
            StepWriteRequest(
                interval = interval,
                clientRecordId = legacyClientRecordId(interval),
            )
        )
        return StepWriteResult(
            recordsWritten = result.recordsWritten,
            requestedSteps = result.requestedSteps,
        )
    }

    suspend fun readTotal(start: Instant, end: Instant): Long

    companion object {
        private fun legacyClientRecordId(interval: StepWriteInterval): String {
            return "legacy:${interval.start}:${interval.end}:${interval.count}"
        }
    }
}

data class StepWriteRequest(
    val interval: StepWriteInterval,
    val clientRecordId: String,
    val clientRecordVersion: Long = 0L,
) {
    init {
        require(clientRecordId.isNotBlank()) { "clientRecordId must not be blank" }
        require(clientRecordVersion >= 0) { "clientRecordVersion must not be negative" }
    }

    val requestedSteps: Long
        get() = interval.count
}

data class VerifiedStepWrite(
    val request: StepWriteRequest,
    val recordsWritten: Int,
    val exactRecordCount: Long?,
    /** Aggregate is diagnostic only; exact app-origin read-back is authoritative. */
    val aggregateSteps: Long?,
    val platformRecordId: String?,
    val wasAlreadyPresent: Boolean,
) {
    val clientRecordId: String
        get() = request.clientRecordId

    val requestedSteps: Long
        get() = request.requestedSteps

    val verified: Boolean
        get() = exactRecordCount == requestedSteps
}

data class StepWriteResult(
    val recordsWritten: Int,
    val requestedSteps: Long,
)
