package com.choupeanut.fitstepcontroller.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** An interval occupied by at least one Health Connect step record. */
data class StepRecordWindow(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(end.isAfter(start)) { "step record window must have a positive duration" }
    }
}

/** A gap in which no source has a step record. */
data class AvailableStepWindow(
    val start: Instant,
    val end: Instant,
    val maxSteps: Long,
) {
    init {
        require(end.isAfter(start)) { "available window must have a positive duration" }
        require(maxSteps >= 0) { "maxSteps must not be negative" }
    }

    val duration: Duration
        get() = Duration.between(start, end)
}

/** The result of scanning a time range for gaps in raw StepsRecord intervals. */
data class StepAvailability(
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val occupiedWindows: List<StepRecordWindow>,
    val availableWindows: List<AvailableStepWindow>,
    val stepsPerSecond: Double,
    val scannedAt: Instant,
) {
    init {
        require(rangeEnd.isAfter(rangeStart)) { "availability range must have a positive duration" }
        require(stepsPerSecond > 0.0) { "stepsPerSecond must be positive" }
    }

    val totalAvailableDuration: Duration
        get() = availableWindows.fold(Duration.ZERO) { total, window -> total.plus(window.duration) }

    val maxSteps: Long
        get() = availableWindows.sumOf(AvailableStepWindow::maxSteps)
}

/** One deterministic, oldest-first write generated from an availability scan. */
data class BackfillAllocation(
    val index: Long,
    val interval: StepWriteInterval,
) {
    init {
        require(index >= 0) { "allocation index must not be negative" }
        require(interval.count > 0) { "allocation count must be positive" }
    }
}

/** Pure interval and capacity calculations used by Mode 2 and its tests. */
object StepAvailabilityPlanner {
    const val FAST_SPEED_KMH = 10.0
    const val FAST_STRIDE_METERS = 0.35
    const val MIN_GAP_MILLIS = 1_000L

    val FAST_STEPS_PER_SECOND: Double =
        FAST_SPEED_KMH * 1_000.0 / 3_600.0 / FAST_STRIDE_METERS

    fun analyze(
        rangeStart: Instant,
        rangeEnd: Instant,
        records: Iterable<StepRecordWindow>,
        scannedAt: Instant = rangeEnd,
        stepsPerSecond: Double = FAST_STEPS_PER_SECOND,
    ): StepAvailability {
        require(rangeEnd.isAfter(rangeStart)) { "availability range must have a positive duration" }
        require(stepsPerSecond > 0.0) { "stepsPerSecond must be positive" }

        val occupied = mergeOccupied(rangeStart, rangeEnd, records)
        val gaps = buildList {
            var cursor = rangeStart
            occupied.forEach { window ->
                if (window.start.isAfter(cursor)) add(cursor to window.start)
                if (window.end.isAfter(cursor)) cursor = window.end
            }
            if (cursor.isBefore(rangeEnd)) add(cursor to rangeEnd)
        }.filter { (start, end) ->
            Duration.between(start, end).toMillis() >= MIN_GAP_MILLIS
        }.map { (start, end) ->
            AvailableStepWindow(
                start = start,
                end = end,
                maxSteps = capacity(start, end, stepsPerSecond),
            )
        }

        return StepAvailability(
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            occupiedWindows = occupied,
            availableWindows = gaps,
            stepsPerSecond = stepsPerSecond,
            scannedAt = scannedAt,
        )
    }

    /** Clips source records to the requested range and merges overlapping/touching intervals. */
    fun mergeOccupied(
        rangeStart: Instant,
        rangeEnd: Instant,
        records: Iterable<StepRecordWindow>,
    ): List<StepRecordWindow> {
        require(rangeEnd.isAfter(rangeStart)) { "availability range must have a positive duration" }
        val clipped = records.mapNotNull { record ->
            if (!record.end.isAfter(rangeStart) || !record.start.isBefore(rangeEnd)) return@mapNotNull null
            StepRecordWindow(
                start = maxOf(record.start, rangeStart),
                end = minOf(record.end, rangeEnd),
            )
        }.sortedBy(StepRecordWindow::start)

        if (clipped.isEmpty()) return emptyList()
        return buildList {
            var current = clipped.first()
            clipped.drop(1).forEach { next ->
                if (!next.start.isAfter(current.end)) {
                    current = current.copy(end = maxOf(current.end, next.end))
                } else {
                    add(current)
                    current = next
                }
            }
            add(current)
        }
    }

    fun capacity(start: Instant, end: Instant, stepsPerSecond: Double = FAST_STEPS_PER_SECOND): Long {
        require(end.isAfter(start)) { "capacity interval must have a positive duration" }
        require(stepsPerSecond > 0.0) { "stepsPerSecond must be positive" }
        return floor(Duration.between(start, end).toMillis() / 1_000.0 * stepsPerSecond)
            .toLong()
            .coerceAtLeast(0L)
    }

    /** Allocates [requestedSteps] from the oldest gap first. */
    fun allocate(
        availability: StepAvailability,
        requestedSteps: Long,
        startingIndex: Long = 0L,
    ): List<BackfillAllocation> {
        require(requestedSteps > 0) { "requestedSteps must be positive" }
        require(startingIndex >= 0) { "startingIndex must not be negative" }
        require(requestedSteps <= availability.maxSteps) {
            "requestedSteps $requestedSteps exceeds available capacity ${availability.maxSteps}"
        }

        var remaining = requestedSteps
        var index = startingIndex
        return buildList {
            availability.availableWindows.forEach { window ->
                if (remaining <= 0L) return@forEach
                val count = min(remaining, window.maxSteps)
                if (count <= 0L) return@forEach

                val windowMillis = window.duration.toMillis()
                val requiredMillis = if (count == window.maxSteps) {
                    windowMillis
                } else {
                    ceil(count / availability.stepsPerSecond * 1_000.0)
                        .toLong()
                        .coerceAtLeast(MIN_GAP_MILLIS)
                        .coerceAtMost(windowMillis)
                }
                add(
                    BackfillAllocation(
                        index = index,
                        interval = StepWriteInterval(
                            start = window.start,
                            end = window.start.plusMillis(requiredMillis),
                            count = count,
                        ),
                    )
                )
                index += 1
                remaining -= count
            }
            check(remaining == 0L) { "unable to allocate requested steps; remaining=$remaining" }
        }
    }

    /** Returns today's local midnight-to-now range, or null at the exact midnight boundary. */
    fun todayMidnightRange(now: Instant, zoneId: ZoneId): Pair<Instant, Instant>? {
        val localNow = now.atZone(zoneId)
        val midnight = localNow.toLocalDate().atStartOfDay(zoneId).toInstant()
        return if (now.isAfter(midnight)) midnight to now else null
    }

    /** Returns the query start required to catch records crossing the range boundary. */
    fun queryStart(rangeStart: Instant): Instant = rangeStart.minusSeconds(24 * 60 * 60L)
}
