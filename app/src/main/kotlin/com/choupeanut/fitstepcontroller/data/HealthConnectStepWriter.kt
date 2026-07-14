package com.choupeanut.fitstepcontroller.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

class HealthConnectStepWriter(
    private val client: HealthConnectClient,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val appPackageName: String? = null,
) : StepWriter {
    override suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite {
        val interval = request.interval
        require(interval.count > 0) { "count must be positive" }
        require(interval.end.isAfter(interval.start)) { "end must be after start" }

        // A client ID is the idempotency key. Check it before inserting so a retry
        // after an IPC timeout does not create a second record.
        val existing = readExact(request)
        if (existing.isNotEmpty()) {
            return verifiedResult(
                request = request,
                records = existing,
                recordsWritten = 0,
                platformRecordId = existing.singleOrNull()?.metadata?.id,
                wasAlreadyPresent = true,
            )
        }

        val zoneRules = zoneId.rules
        val record = StepsRecord(
            startTime = interval.start,
            startZoneOffset = zoneRules.getOffset(interval.start),
            endTime = interval.end,
            endZoneOffset = zoneRules.getOffset(interval.end),
            count = interval.count,
            metadata = Metadata.manualEntry(request.clientRecordId, request.clientRecordVersion),
        )
        val response = client.insertRecords(listOf(record))
        val inserted = readExact(request)
        return verifiedResult(
            request = request,
            records = inserted,
            recordsWritten = response.recordIdsList.size,
            platformRecordId = response.recordIdsList.singleOrNull(),
            wasAlreadyPresent = false,
        )
    }

    override suspend fun readTotal(start: Instant, end: Instant): Long {
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        return response[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    suspend fun readRaw(start: Instant, end: Instant): List<StepsRecord> {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = appPackageName?.let { setOf(DataOrigin(it)) } ?: emptySet(),
            )
        ).records
    }

    private suspend fun readExact(request: StepWriteRequest): List<StepsRecord> {
        return readRaw(request.interval.start, request.interval.end)
            .filter { it.metadata.clientRecordId == request.clientRecordId }
    }

    private suspend fun verifiedResult(
        request: StepWriteRequest,
        records: List<StepsRecord>,
        recordsWritten: Int,
        platformRecordId: String?,
        wasAlreadyPresent: Boolean,
    ): VerifiedStepWrite {
        require(records.size == 1) {
            "Expected exactly one Health Connect record for clientRecordId=${request.clientRecordId}, found ${records.size}"
        }
        val exact = records.single()
        require(exact.startTime == request.interval.start && exact.endTime == request.interval.end) {
            "Health Connect record interval does not match clientRecordId=${request.clientRecordId}"
        }
        require(exact.count == request.requestedSteps) {
            "Health Connect record count ${exact.count} does not match requested ${request.requestedSteps}"
        }
        return VerifiedStepWrite(
            request = request,
            recordsWritten = recordsWritten,
            exactRecordCount = exact.count,
            aggregateSteps = readTotal(request.interval.start, request.interval.end),
            platformRecordId = platformRecordId ?: exact.metadata.id,
            wasAlreadyPresent = wasAlreadyPresent,
        )
    }
}
