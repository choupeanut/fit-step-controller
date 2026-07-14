package com.choupeanut.fitstepcontroller.domain

import com.choupeanut.fitstepcontroller.data.InMemoryWalkingSessionStore
import com.choupeanut.fitstepcontroller.data.StepWriteRequest
import com.choupeanut.fitstepcontroller.data.StepWriter
import com.choupeanut.fitstepcontroller.data.VerifiedStepWrite
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WalkingSessionPersistenceTest {
    private val epoch = Instant.parse("2026-05-04T08:00:00Z")

    @Test
    fun firstChunkWaitsForCadenceAndOnlyConfirmedWriteAdvancesStore() = runTest {
        var current = epoch
        val writer = RecordingWriter()
        val store = InMemoryWalkingSessionStore()
        val controller = controller(writer, store) { current }

        controller.start(WalkingPlanInput(6.0, 1_000, 0.75))
        current = epoch.plusSeconds(59)
        assertThat(controller.tick().confirmedSteps).isEqualTo(0)
        assertThat(writer.requests).isEmpty()

        current = epoch.plusSeconds(60)
        val snapshot = controller.tick()

        assertThat(snapshot.confirmedSteps).isEqualTo(writer.requests.single().requestedSteps)
        assertThat(store.load()!!.pendingChunk).isNull()
        assertThat(store.load()!!.nextChunkIndex).isEqualTo(1)
    }

    @Test
    fun retryUsesTheSameClientIdAndConfirmsOnlyAfterExactReadBack() = runTest {
        var current = epoch
        val writer = RecordingWriter(failuresBeforeSuccess = 2)
        val controller = controller(writer, InMemoryWalkingSessionStore()) { current }

        controller.start(WalkingPlanInput(6.0, 1_000, 0.75))
        current = epoch.plusSeconds(60)
        val snapshot = controller.tick()

        assertThat(writer.requests).hasSize(3)
        assertThat(writer.requests.map(StepWriteRequest::clientRecordId).toSet()).hasSize(1)
        assertThat(snapshot.confirmedSteps).isEqualTo(writer.requests.last().requestedSteps)
        assertThat(snapshot.state).isEqualTo(WalkingSessionState.RUNNING)
    }

    @Test
    fun restoreRetriesPendingChunkWithoutBackfillingDowntime() = runTest {
        val store = InMemoryWalkingSessionStore()
        val plan = StepPlanner().createWalkingPlan(WalkingPlanInput(6.0, 1_000, 0.75))
        val interval = StepWriteInterval(epoch, epoch.plusSeconds(60), 100)
        store.save(
            PersistedWalkingSession(
                sessionId = "session-1",
                plan = plan,
                state = WalkingSessionState.RUNNING,
                confirmedSteps = 0,
                pendingChunk = PendingWalkingChunk(0, "session-1:0", interval),
                nextChunkIndex = 0,
                lastConfirmedAt = null,
                lastTickAt = epoch.plusSeconds(60),
                startedAt = epoch,
                error = null,
            )
        )

        var current = epoch.plusSeconds(60 + 60 * 60)
        val writer = RecordingWriter()
        val controller = controller(writer, store) { current }
        val restored = controller.restore()

        assertThat(restored!!.pendingChunk!!.clientRecordId).isEqualTo("session-1:0")
        val snapshot = controller.tick()

        assertThat(writer.requests.single().clientRecordId).isEqualTo("session-1:0")
        assertThat(snapshot.confirmedSteps).isEqualTo(100)
        assertThat(store.load()!!.pendingChunk).isNull()
        current = current.plusSeconds(60)
        controller.tick()
        assertThat(writer.requests).hasSize(2)
        assertThat(writer.requests[1].interval.start).isEqualTo(current.minusSeconds(60))
    }

    @Test
    fun pauseAndResumeDoNotCountPausedTime() = runTest {
        var current = epoch
        val writer = RecordingWriter()
        val controller = controller(writer, InMemoryWalkingSessionStore()) { current }
        controller.start(WalkingPlanInput(6.0, 1_000, 0.75))
        current = epoch.plusSeconds(60)
        controller.tick()
        controller.pause()

        current = epoch.plusSeconds(3_600)
        controller.resume()
        current = epoch.plusSeconds(3_660)
        controller.tick()

        assertThat(writer.requests).hasSize(2)
        assertThat(writer.requests[1].interval.start).isEqualTo(epoch.plusSeconds(3_600))
        assertThat(writer.requests[1].interval.end).isEqualTo(epoch.plusSeconds(3_660))
    }

    @Test
    fun restoreDiscardsPartialCadenceAndDoesNotBackfillDowntime() = runTest {
        var current = epoch
        val store = InMemoryWalkingSessionStore()
        val writer = RecordingWriter()
        val controller = controller(writer, store) { current }
        controller.start(WalkingPlanInput(6.0, 1_000, 0.75))

        current = epoch.plusSeconds(30)
        val beforeRestart = controller.tick()
        val accruedBeforeRestart = beforeRestart.accruedSteps
        assertThat(accruedBeforeRestart).isGreaterThan(0.0)

        current = epoch.plusSeconds(3_600)
        val restoredController = controller(writer, store) { current }
        val restored = restoredController.restore()!!
        assertThat(restored.accruedSteps).isEqualTo(0.0)

        current = current.plusSeconds(30)
        val afterRestart = restoredController.tick()
        assertThat(writer.requests).isEmpty()
        assertThat(afterRestart.accruedSteps).isWithin(0.01).of(6.0 / 3.6 / 0.75 * 30.0)
    }

    @Test
    fun failedRetriesPersistFailedStateAndDoNotAdvanceConfirmedSteps() = runTest {
        var current = epoch
        val store = InMemoryWalkingSessionStore()
        val writer = RecordingWriter(failuresBeforeSuccess = Int.MAX_VALUE)
        val controller = controller(writer, store) { current }
        controller.start(WalkingPlanInput(6.0, 1_000, 0.75))
        current = epoch.plusSeconds(60)

        val snapshot = controller.tick()

        assertThat(writer.requests).hasSize(3)
        assertThat(snapshot.state).isEqualTo(WalkingSessionState.FAILED)
        assertThat(snapshot.confirmedSteps).isEqualTo(0)
        assertThat(store.load()!!.pendingChunk).isNotNull()
        assertThat(store.load()!!.error).contains("temporary")
    }

    @Test
    fun cancellationDuringInFlightWriteLeavesPendingChunkForPauseOrStop() = runTest {
        var current = epoch
        val store = InMemoryWalkingSessionStore()
        val writer = BlockingWriter()
        val controller = controller(writer, store) { current }
        controller.start(WalkingPlanInput(6.0, 1_000, 0.75))
        current = epoch.plusSeconds(60)

        val tickJob = launch { controller.tick() }
        writer.started.await()
        tickJob.cancelAndJoin()

        val paused = controller.pause()
        assertThat(paused.state).isEqualTo(WalkingSessionState.PAUSED)
        assertThat(store.load()!!.pendingChunk).isNotNull()
        assertThat(store.load()!!.confirmedSteps).isEqualTo(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plansLongerThanFiveHoursAreRejected() {
        val controller = controller(RecordingWriter(), InMemoryWalkingSessionStore()) { epoch }
        controller.start(WalkingPlanInput(3.0, 100_000, 1.4))
    }

    private fun controller(
        writer: StepWriter,
        store: InMemoryWalkingSessionStore,
        now: () -> Instant,
    ): WalkingSessionController {
        return WalkingSessionController(
            planner = StepPlanner(),
            writer = writer,
            chunkDuration = Duration.ofSeconds(60),
            now = now,
            store = store,
            retryDelaysMillis = listOf(0L, 0L, 0L),
        )
    }

    private class RecordingWriter(
        private var failuresBeforeSuccess: Int = 0,
    ) : StepWriter {
        val requests = mutableListOf<StepWriteRequest>()

        override suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite {
            requests += request
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                throw IllegalStateException("temporary writer failure")
            }
            return VerifiedStepWrite(
                request = request,
                recordsWritten = 1,
                exactRecordCount = request.requestedSteps,
                aggregateSteps = request.requestedSteps,
                platformRecordId = "platform-${requests.size}",
                wasAlreadyPresent = false,
            )
        }

        override suspend fun readTotal(start: Instant, end: Instant): Long = 0
    }

    private class BlockingWriter : StepWriter {
        val started = CompletableDeferred<Unit>()

        override suspend fun writeAndVerify(request: StepWriteRequest): VerifiedStepWrite {
            started.complete(Unit)
            awaitCancellation()
        }

        override suspend fun readTotal(start: Instant, end: Instant): Long = 0
    }
}
