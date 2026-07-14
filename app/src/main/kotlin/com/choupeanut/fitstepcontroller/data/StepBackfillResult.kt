package com.choupeanut.fitstepcontroller.data

import com.choupeanut.fitstepcontroller.domain.BackfillAllocation
import com.choupeanut.fitstepcontroller.domain.StepAvailability

/** Outcome of a Mode 2 gap backfill. A non-null failure may still include partial writes. */
data class StepBackfillResult(
    val requestedSteps: Long,
    val writtenSteps: Long,
    val allocations: List<BackfillAllocation>,
    val initialAvailability: StepAvailability,
    val finalAvailability: StepAvailability,
    val failure: String? = null,
) {
    val remainingSteps: Long
        get() = (requestedSteps - writtenSteps).coerceAtLeast(0L)

    val completed: Boolean
        get() = failure == null && remainingSteps == 0L
}
