package com.choupeanut.fitstepcontroller.domain

import java.time.Instant

class StepWindowAllocator(
    private val planner: StepPlanner,
    private val loadCursor: () -> Instant?,
    private val saveCursor: (Instant) -> Unit,
) {
    fun allocatePastWindow(steps: Long, now: Instant): StepWriteInterval {
        val cursor = loadCursor()?.takeIf { !it.isAfter(now) } ?: now
        val interval = planner.directInterval(steps = steps, endingAt = cursor)
        saveCursor(interval.start)
        return interval
    }

    fun reset(now: Instant) {
        saveCursor(now)
    }
}
