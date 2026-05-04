package com.choupeanut.fitstepcontroller.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import java.time.Instant
import java.time.ZoneId

class HealthConnectStepWriter(
    private val client: HealthConnectClient,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val appPackageName: String? = null,
) : StepWriter {
    override suspend fun write(interval: StepWriteInterval): StepWriteResult {
        require(interval.count > 0) { "count must be positive" }
        require(interval.end.isAfter(interval.start)) { "end must be after start" }

        val zoneRules = zoneId.rules
        val record = StepsRecord(
            startTime = interval.start,
            startZoneOffset = zoneRules.getOffset(interval.start),
            endTime = interval.end,
            endZoneOffset = zoneRules.getOffset(interval.end),
            count = interval.count,
            metadata = Metadata.manualEntry(),
        )
        val response = client.insertRecords(listOf(record))
        return StepWriteResult(
            recordsWritten = response.recordIdsList.size,
            requestedSteps = interval.count,
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
}
