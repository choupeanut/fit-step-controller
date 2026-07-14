package com.choupeanut.fitstepcontroller.data

import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class FakeStepWriter : StepWriter {
    private data class WrittenRecord(
        val interval: StepWriteInterval,
        val clientRecordId: String,
        val platformRecordId: String,
    )

    private val records = CopyOnWriteArrayList<WrittenRecord>()

    override suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite {
        val existing = records.filter { it.clientRecordId == request.clientRecordId }
        if (existing.isNotEmpty()) {
            require(existing.size == 1) { "duplicate clientRecordId=${request.clientRecordId}" }
            val record = existing.single()
            require(record.interval == request.interval) {
                "clientRecordId=${request.clientRecordId} was reused with a different interval"
            }
            return verified(request, record, recordsWritten = 0, wasAlreadyPresent = true)
        }

        val record = WrittenRecord(
            interval = request.interval,
            clientRecordId = request.clientRecordId,
            platformRecordId = "fake-${records.size}",
        )
        records += record
        return verified(request, record, recordsWritten = 1, wasAlreadyPresent = false)
    }

    private fun verified(
        request: StepWriteRequest,
        record: WrittenRecord,
        recordsWritten: Int,
        wasAlreadyPresent: Boolean,
    ): VerifiedStepWrite {
        val exact = records.filter { it.clientRecordId == request.clientRecordId }
        return VerifiedStepWrite(
            request = request,
            recordsWritten = recordsWritten,
            exactRecordCount = exact.single().interval.count,
            aggregateSteps = records
                .filter {
                    !it.interval.end.isBefore(request.interval.start) &&
                        !it.interval.start.isAfter(request.interval.end)
                }
                .sumOf { it.interval.count },
            platformRecordId = record.platformRecordId,
            wasAlreadyPresent = wasAlreadyPresent,
        )
    }

    override suspend fun readTotal(start: Instant, end: Instant): Long {
        return records
            .filter { !it.interval.end.isBefore(start) && !it.interval.start.isAfter(end) }
            .sumOf { it.interval.count }
    }

    fun allIntervals(): List<StepWriteInterval> = records.map { it.interval }
}
