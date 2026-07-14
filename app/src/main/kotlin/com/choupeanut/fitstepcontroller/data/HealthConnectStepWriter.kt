package com.choupeanut.fitstepcontroller.data

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.choupeanut.fitstepcontroller.domain.BackfillAllocation
import com.choupeanut.fitstepcontroller.domain.StepAvailability
import com.choupeanut.fitstepcontroller.domain.StepAvailabilityPlanner
import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class HealthConnectStepWriter(
    private val client: HealthConnectClient,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    appPackageName: String? = null,
    private val clock: () -> Instant = { Instant.now() },
) : StepWriter {
    private val appPackageName: String = requireAppPackageName(appPackageName)

    override suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite {
        // Health Connect serializes interval boundaries as epoch milliseconds. Normalize
        // caller timestamps before both the write and read-back query so Instant.now()
        // nanoseconds cannot create a false interval mismatch.
        val normalizedRequest = request.copy(interval = normalizeInterval(request.interval))
        val interval = normalizedRequest.interval
        require(interval.count > 0) { "count must be positive" }
        require(interval.end.isAfter(interval.start)) { "end must be after start" }

        // A client ID is the idempotency key. Check it before inserting so a retry
        // after an IPC timeout does not create a second record.
        val existing = readExact(normalizedRequest)
        if (existing.isNotEmpty()) {
            return verifiedResult(
                request = normalizedRequest,
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
            metadata = Metadata.manualEntry(normalizedRequest.clientRecordId, normalizedRequest.clientRecordVersion),
        )
        val response = client.insertRecords(listOf(record))
        val inserted = readExact(normalizedRequest)
        return verifiedResult(
            request = normalizedRequest,
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
                dataOriginFilter = setOf(DataOrigin(appPackageName)),
            )
        ).records
    }

    /**
     * Reads raw StepsRecord intervals from every granted source, walking every page.
     * Aggregate reads are intentionally not used because they apply source-priority
     * deduplication and would hide occupied intervals from another source.
     */
    suspend fun readAllSteps(
        start: Instant,
        end: Instant,
        pageSize: Int = MAX_READ_PAGE_SIZE,
    ): List<StepsRecord> {
        require(end.isAfter(start)) { "end must be after start" }
        require(pageSize in 1..MAX_READ_PAGE_SIZE) { "pageSize must be between 1 and $MAX_READ_PAGE_SIZE" }

        val records = mutableListOf<StepsRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    // An empty origin filter means all origins. Disable platform
                    // deduplication so every source can conservatively occupy a gap.
                    dataOriginFilter = emptySet(),
                    ascendingOrder = true,
                    pageSize = pageSize,
                    pageToken = pageToken,
                )
            )
            records += response.records
            val nextToken = response.pageToken
            if (nextToken == pageToken) break
            pageToken = nextToken
        } while (!pageToken.isNullOrEmpty())
        return records
    }

    /** Scans today-noon to now, querying 24 hours before noon for crossing records. */
    suspend fun readStepAvailability(
        rangeStart: Instant,
        rangeEnd: Instant,
        lookback: Duration = Duration.ofHours(24),
        stepsPerSecond: Double = StepAvailabilityPlanner.FAST_STEPS_PER_SECOND,
    ): StepAvailability {
        require(lookback >= Duration.ZERO) { "lookback must not be negative" }
        val records = readAllSteps(rangeStart.minus(lookback), rangeEnd)
        return StepAvailabilityPlanner.analyze(
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            records = records.map { record ->
                com.choupeanut.fitstepcontroller.domain.StepRecordWindow(
                    start = record.startTime,
                    end = record.endTime,
                )
            },
            scannedAt = clock(),
            stepsPerSecond = stepsPerSecond,
        )
    }

    /** Returns null before local noon; otherwise scans the current local day's noon-to-now range. */
    suspend fun readTodayNoonAvailability(
        now: Instant = clock(),
        lookback: Duration = Duration.ofHours(24),
        stepsPerSecond: Double = StepAvailabilityPlanner.FAST_STEPS_PER_SECOND,
    ): StepAvailability? {
        val range = StepAvailabilityPlanner.todayNoonRange(now, zoneId) ?: return null
        return readStepAvailability(
            rangeStart = range.first,
            rangeEnd = range.second,
            lookback = lookback,
            stepsPerSecond = stepsPerSecond,
        )
    }

    /**
     * Re-scans before every oldest-first write. The same [batchId] and allocation index
     * form the clientRecordId, so retrying after an IPC timeout remains idempotent.
     * If the initial capacity is insufficient, no record is written.
     */
    suspend fun backfillAvailableSteps(
        rangeStart: Instant,
        rangeEnd: Instant,
        requestedSteps: Long,
        batchId: String,
        lookback: Duration = Duration.ofHours(24),
        stepsPerSecond: Double = StepAvailabilityPlanner.FAST_STEPS_PER_SECOND,
    ): StepBackfillResult {
        require(requestedSteps > 0) { "requestedSteps must be positive" }
        require(batchId.isNotBlank()) { "batchId must not be blank" }

        val initialAvailability = readStepAvailability(rangeStart, rangeEnd, lookback, stepsPerSecond)
        var availability = initialAvailability
        if (requestedSteps > availability.maxSteps) {
            return StepBackfillResult(
                requestedSteps = requestedSteps,
                writtenSteps = 0,
                allocations = emptyList(),
                initialAvailability = initialAvailability,
                finalAvailability = availability,
                failure = "Requested $requestedSteps steps exceeds available capacity ${availability.maxSteps}",
            )
        }

        val allocations = mutableListOf<BackfillAllocation>()
        var writtenSteps = 0L
        var nextIndex = 0L
        var failure: String? = null
        while (writtenSteps < requestedSteps) {
            availability = readStepAvailability(rangeStart, rangeEnd, lookback, stepsPerSecond)
            val remaining = requestedSteps - writtenSteps
            if (remaining > availability.maxSteps) {
                failure = "Available capacity changed to ${availability.maxSteps}; $remaining steps remain"
                break
            }
            val allocation = StepAvailabilityPlanner.allocate(
                availability = availability,
                requestedSteps = remaining,
                startingIndex = nextIndex,
            ).firstOrNull()
            if (allocation == null) {
                failure = "No available step window remains; $remaining steps remain"
                break
            }
            try {
                val result = writeAndVerify(
                    StepWriteRequest(
                        interval = allocation.interval,
                        clientRecordId = "$batchId:${allocation.index}",
                    )
                )
                check(result.verified) { "Health Connect write verification failed for ${allocation.index}" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error.message ?: "Health Connect write failed for ${allocation.index}"
                break
            }
            allocations += allocation
            writtenSteps += allocation.interval.count
            nextIndex = allocation.index + 1
        }

        val finalAvailability = try {
            readStepAvailability(rangeStart, rangeEnd, lookback, stepsPerSecond)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            availability
        }
        return StepBackfillResult(
            requestedSteps = requestedSteps,
            writtenSteps = writtenSteps,
            allocations = allocations.toList(),
            initialAvailability = initialAvailability,
            finalAvailability = finalAvailability,
            failure = failure,
        )
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
            // Aggregate is source-prioritized diagnostic data. If this separate IPC
            // read is unavailable, the exact record is still a verified write.
            aggregateSteps = readAggregateOrNull {
                readTotal(request.interval.start, request.interval.end)
            },
            platformRecordId = platformRecordId ?: exact.metadata.id,
            wasAlreadyPresent = wasAlreadyPresent,
        )
    }

    companion object {
        const val MAX_READ_PAGE_SIZE = 1_000

        internal fun normalizeInterval(interval: StepWriteInterval): StepWriteInterval {
            return interval.copy(
                start = Instant.ofEpochMilli(interval.start.toEpochMilli()),
                end = Instant.ofEpochMilli(interval.end.toEpochMilli()),
            )
        }

        internal fun requireAppPackageName(appPackageName: String?): String {
            require(!appPackageName.isNullOrBlank()) {
                "appPackageName is required to scope Health Connect records to this app"
            }
            return appPackageName
        }

        internal suspend fun readAggregateOrNull(reader: suspend () -> Long): Long? {
            return try {
                reader()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
    }
}
