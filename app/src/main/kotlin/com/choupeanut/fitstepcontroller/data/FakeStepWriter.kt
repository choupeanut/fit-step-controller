package com.choupeanut.fitstepcontroller.data

import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

class FakeStepWriter : StepWriter {
    private val intervals = CopyOnWriteArrayList<StepWriteInterval>()

    override suspend fun write(interval: StepWriteInterval): StepWriteResult {
        intervals += interval
        return StepWriteResult(recordsWritten = 1, requestedSteps = interval.count)
    }

    override suspend fun readTotal(start: Instant, end: Instant): Long {
        return intervals
            .filter { !it.end.isBefore(start) && !it.start.isAfter(end) }
            .sumOf { it.count }
    }

    fun allIntervals(): List<StepWriteInterval> = intervals.toList()
}
