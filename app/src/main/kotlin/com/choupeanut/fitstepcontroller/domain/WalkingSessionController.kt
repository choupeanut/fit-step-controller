package com.choupeanut.fitstepcontroller.domain

import com.choupeanut.fitstepcontroller.data.StepWriter
import java.time.Duration
import java.time.Instant

data class WalkingSessionSnapshot(
    val plan: WalkingPlan,
    val writtenSteps: Long,
    val isComplete: Boolean,
    val lastInterval: StepWriteInterval?,
)

class WalkingSessionController(
    private val planner: StepPlanner,
    private val writer: StepWriter,
    private val chunkDuration: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = { Instant.now() },
) {
    private var plan: WalkingPlan? = null
    private var writtenSteps: Long = 0

    fun start(input: WalkingPlanInput): WalkingSessionSnapshot {
        val created = planner.createWalkingPlan(input)
        plan = created
        writtenSteps = 0
        return snapshot(lastInterval = null)
    }

    suspend fun tick(): WalkingSessionSnapshot {
        val current = plan ?: error("Session has not started")
        val interval = planner.nextWalkingChunk(
            plan = current,
            alreadyWritten = writtenSteps,
            chunkDuration = chunkDuration,
            now = now(),
        )
        if (interval != null) {
            writer.write(interval)
            writtenSteps += interval.count
        }
        return snapshot(lastInterval = interval)
    }

    fun stop(): WalkingSessionSnapshot {
        return snapshot(lastInterval = null).also {
            plan = null
            writtenSteps = 0
        }
    }

    private fun snapshot(lastInterval: StepWriteInterval?): WalkingSessionSnapshot {
        val current = plan ?: error("Session has not started")
        return WalkingSessionSnapshot(
            plan = current,
            writtenSteps = writtenSteps,
            isComplete = writtenSteps >= current.targetSteps,
            lastInterval = lastInterval,
        )
    }
}
