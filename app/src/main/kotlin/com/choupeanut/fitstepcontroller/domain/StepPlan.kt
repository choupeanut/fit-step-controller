package com.choupeanut.fitstepcontroller.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

data class WalkingPlanInput(
    val speedKmh: Double,
    val targetSteps: Long,
    val strideMeters: Double,
)

data class WalkingPlan(
    val speedKmh: Double,
    val targetSteps: Long,
    val strideMeters: Double,
    val distanceMeters: Double,
    val duration: Duration,
    val stepsPerSecond: Double,
    val warnings: List<String>,
)

data class StepWriteInterval(
    val start: Instant,
    val end: Instant,
    val count: Long,
)

class StepPlanner(
    private val clock: () -> Instant = { Instant.now() },
) {
    fun createWalkingPlan(input: WalkingPlanInput): WalkingPlan {
        require(input.speedKmh in MIN_SPEED_KMH..MAX_SPEED_KMH) {
            "speedKmh must be between $MIN_SPEED_KMH and $MAX_SPEED_KMH"
        }
        require(input.targetSteps > 0) { "targetSteps must be positive" }
        require(input.strideMeters in MIN_STRIDE_METERS..MAX_STRIDE_METERS) {
            "strideMeters must be between $MIN_STRIDE_METERS and $MAX_STRIDE_METERS"
        }

        val distanceMeters = input.targetSteps * input.strideMeters
        val metersPerSecond = input.speedKmh * 1000.0 / 3600.0
        val durationSeconds = max(1.0, distanceMeters / metersPerSecond)
        val stepsPerSecond = input.targetSteps / durationSeconds
        val warnings = buildList {
            if (input.speedKmh > TRUSTED_WALKING_SPEED_KMH) {
                add("Speed is above $TRUSTED_WALKING_SPEED_KMH km/h; some consumers may treat this as non-walking data.")
            }
            if (stepsPerSecond > GOOGLE_FIT_MAX_STEPS_PER_SECOND) {
                add("Step density is above $GOOGLE_FIT_MAX_STEPS_PER_SECOND steps/sec; data may be rejected or ignored.")
            }
        }

        return WalkingPlan(
            speedKmh = input.speedKmh,
            targetSteps = input.targetSteps,
            strideMeters = input.strideMeters,
            distanceMeters = distanceMeters,
            duration = Duration.ofMillis((durationSeconds * 1000).roundToLong()),
            stepsPerSecond = stepsPerSecond,
            warnings = warnings,
        )
    }

    fun directInterval(steps: Long, endingAt: Instant = clock()): StepWriteInterval {
        require(steps > 0) { "steps must be positive" }
        val seconds = ceil(steps / DIRECT_WRITE_STEPS_PER_SECOND).toLong().coerceAtLeast(1)
        val start = endingAt.minusSeconds(seconds)
        return StepWriteInterval(start = start, end = endingAt, count = steps)
    }

    fun nextWalkingChunk(
        plan: WalkingPlan,
        alreadyWritten: Long,
        chunkDuration: Duration,
        now: Instant = clock(),
    ): StepWriteInterval? {
        require(chunkDuration.toMillis() > 0) { "chunkDuration must be positive" }
        if (alreadyWritten >= plan.targetSteps) return null

        val remaining = plan.targetSteps - alreadyWritten
        val chunkSteps = minOf(
            remaining,
            max(1, (plan.stepsPerSecond * chunkDuration.toMillis() / 1000.0).roundToLong())
        )
        val actualDurationMillis = max(1000, (chunkSteps / plan.stepsPerSecond * 1000).roundToLong())
        val end = now
        val start = end.minusMillis(actualDurationMillis)
        return StepWriteInterval(start = start, end = end, count = chunkSteps)
    }

    companion object {
        const val MIN_SPEED_KMH = 3.0
        const val MAX_SPEED_KMH = 12.0
        const val TRUSTED_WALKING_SPEED_KMH = 10.5
        const val MIN_STRIDE_METERS = 0.35
        const val MAX_STRIDE_METERS = 1.4
        const val GOOGLE_FIT_MAX_STEPS_PER_SECOND = 10.0
        const val DIRECT_WRITE_STEPS_PER_SECOND = 2.5
    }
}
