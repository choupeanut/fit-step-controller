package com.choupeanut.fitstepcontroller.domain

import com.choupeanut.fitstepcontroller.data.FakeStepWriter
import com.choupeanut.fitstepcontroller.data.StepWriteRequest
import com.choupeanut.fitstepcontroller.data.StepWriter
import com.choupeanut.fitstepcontroller.data.VerifiedStepWrite
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WalkingSessionControllerTest {
    @Test
    fun sessionWritesChunksUntilComplete() = runTest {
        var current = Instant.parse("2026-05-04T08:00:00Z")
        val writer = FakeStepWriter()
        val controller = WalkingSessionController(
            planner = StepPlanner(),
            writer = writer,
            chunkDuration = Duration.ofSeconds(30),
            now = { current },
            retryDelaysMillis = listOf(0L, 0L, 0L),
        )

        controller.start(WalkingPlanInput(speedKmh = 6.0, targetSteps = 50, strideMeters = 0.75))
        current = current.plusSeconds(30)
        var snapshot = controller.tick()
        while (!snapshot.isComplete) {
            current = current.plusSeconds(30)
            snapshot = controller.tick()
        }

        assertThat(snapshot.writtenSteps).isEqualTo(50)
        assertThat(writer.allIntervals().sumOf { it.count }).isEqualTo(50)
    }

    @Test
    fun failedWriteDoesNotAdvanceWrittenStepsBeforeRetry() = runTest {
        var current = Instant.parse("2026-05-04T08:00:00Z")
        val writer = FailingOnceStepWriter()
        val controller = WalkingSessionController(
            planner = StepPlanner(),
            writer = writer,
            chunkDuration = Duration.ofSeconds(30),
            now = { current },
            retryDelaysMillis = listOf(0L, 0L, 0L),
        )
        controller.start(WalkingPlanInput(speedKmh = 6.0, targetSteps = 100, strideMeters = 0.75))
        current = current.plusSeconds(30)

        val snapshot = controller.tick()

        assertThat(writer.requests).hasSize(2)
        assertThat(writer.requests[1].interval.count).isEqualTo(writer.requests[0].interval.count)
        assertThat(snapshot.writtenSteps).isEqualTo(writer.requests[1].interval.count)
    }

    private class FailingOnceStepWriter : StepWriter {
        val requests = mutableListOf<StepWriteRequest>()

        override suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite {
            requests += request
            if (requests.size == 1) throw IllegalStateException("temporary write failure")
            return VerifiedStepWrite(
                request = request,
                recordsWritten = 1,
                exactRecordCount = request.requestedSteps,
                aggregateSteps = request.requestedSteps,
                platformRecordId = "fake-${requests.size}",
                wasAlreadyPresent = false,
            )
        }

        override suspend fun readTotal(start: Instant, end: Instant): Long = 0L
    }
}
