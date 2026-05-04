package com.choupeanut.fitstepcontroller.domain

import com.choupeanut.fitstepcontroller.data.FakeStepWriter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WalkingSessionControllerTest {
    @Test
    fun sessionWritesChunksUntilComplete() = runTest {
        val writer = FakeStepWriter()
        val controller = WalkingSessionController(
            planner = StepPlanner(),
            writer = writer,
            chunkDuration = Duration.ofSeconds(30),
            now = { Instant.parse("2026-05-04T08:00:00Z") },
        )

        controller.start(WalkingPlanInput(speedKmh = 6.0, targetSteps = 50, strideMeters = 0.75))
        var snapshot = controller.tick()
        while (!snapshot.isComplete) {
            snapshot = controller.tick()
        }

        assertThat(snapshot.writtenSteps).isEqualTo(50)
        assertThat(writer.allIntervals().sumOf { it.count }).isEqualTo(50)
    }
}
