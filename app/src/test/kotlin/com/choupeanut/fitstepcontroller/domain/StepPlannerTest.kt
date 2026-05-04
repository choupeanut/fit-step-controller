package com.choupeanut.fitstepcontroller.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StepPlannerTest {
    private val fixedNow = Instant.parse("2026-05-04T08:00:00Z")
    private val planner = StepPlanner { fixedNow }

    @Test
    fun walkingPlanCalculatesDurationAndDistance() {
        val plan = planner.createWalkingPlan(
            WalkingPlanInput(speedKmh = 6.0, targetSteps = 1000, strideMeters = 0.75)
        )

        assertThat(plan.distanceMeters).isWithin(0.001).of(750.0)
        assertThat(plan.duration.seconds).isEqualTo(450)
        assertThat(plan.stepsPerSecond).isWithin(0.001).of(2.222)
        assertThat(plan.warnings).isEmpty()
    }

    @Test
    fun walkingPlanWarnsWhenSpeedExceedsTrustedWalkingRange() {
        val plan = planner.createWalkingPlan(
            WalkingPlanInput(speedKmh = 12.0, targetSteps = 1000, strideMeters = 0.75)
        )

        assertThat(plan.warnings).isNotEmpty()
    }

    @Test
    fun walkingPlanRejectsInvalidSpeed() {
        assertThrows(IllegalArgumentException::class.java) {
            planner.createWalkingPlan(WalkingPlanInput(speedKmh = 2.9, targetSteps = 1000, strideMeters = 0.75))
        }
    }

    @Test
    fun directIntervalUsesReasonableNonZeroDuration() {
        val interval = planner.directInterval(steps = 500, endingAt = fixedNow)

        assertThat(interval.count).isEqualTo(500)
        assertThat(Duration.between(interval.start, interval.end).seconds).isEqualTo(200)
    }

    @Test
    fun nextWalkingChunkStopsAtTarget() {
        val plan = planner.createWalkingPlan(WalkingPlanInput(speedKmh = 6.0, targetSteps = 10, strideMeters = 0.75))
        val interval = planner.nextWalkingChunk(plan, alreadyWritten = 9, chunkDuration = Duration.ofSeconds(30), now = fixedNow)

        assertThat(interval?.count).isEqualTo(1)
        assertThat(planner.nextWalkingChunk(plan, alreadyWritten = 10, chunkDuration = Duration.ofSeconds(30), now = fixedNow)).isNull()
    }
}
