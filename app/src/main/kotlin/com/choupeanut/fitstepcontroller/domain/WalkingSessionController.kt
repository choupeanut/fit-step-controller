package com.choupeanut.fitstepcontroller.domain

import com.choupeanut.fitstepcontroller.data.InMemoryWalkingSessionStore
import com.choupeanut.fitstepcontroller.data.StepWriteRequest
import com.choupeanut.fitstepcontroller.data.StepWriter
import com.choupeanut.fitstepcontroller.data.WalkingSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class WalkingSessionState {
    IDLE,
    RUNNING,
    PAUSED,
    FAILED,
    STOPPED,
    COMPLETED,
}

data class PendingWalkingChunk(
    val chunkIndex: Long,
    val clientRecordId: String,
    val interval: StepWriteInterval,
)

data class PersistedWalkingSession(
    val sessionId: String,
    val plan: WalkingPlan,
    val state: WalkingSessionState,
    val confirmedSteps: Long,
    val pendingChunk: PendingWalkingChunk?,
    val nextChunkIndex: Long,
    val lastConfirmedAt: Instant?,
    val lastTickAt: Instant?,
    val startedAt: Instant,
    val error: String?,
    /** Timestamp of the current cadence window; unlike lastTickAt it is not moved every tick. */
    val cadenceStartedAt: Instant? = null,
    /** Fractional steps accumulated since the last confirmed chunk. */
    val accruedSteps: Double = 0.0,
    /** Active (non-paused) elapsed time used for the five-hour session cap. */
    val activeElapsedMillis: Long = 0L,
)

/**
 * Coordinates a walking plan, durable pending chunks and verified Health Connect writes.
 * The controller deliberately creates no chunk until one complete cadence has elapsed.
 */
class WalkingSessionController(
    private val planner: StepPlanner,
    private val writer: StepWriter,
    private val chunkDuration: Duration = Duration.ofSeconds(60),
    private val now: () -> Instant = { Instant.now() },
    private val store: WalkingSessionStore = InMemoryWalkingSessionStore(),
    private val retryDelaysMillis: List<Long> = listOf(2_000L, 5_000L, 10_000L),
    private val retryDelay: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    init {
        require(chunkDuration.toMillis() > 0) { "chunkDuration must be positive" }
        require(retryDelaysMillis.size == MAX_WRITE_ATTEMPTS) {
            "retryDelaysMillis must contain exactly $MAX_WRITE_ATTEMPTS entries"
        }
        require(retryDelaysMillis.all { it >= 0 }) { "retry delays must not be negative" }
    }

    private var session: PersistedWalkingSession? = null
    private var lastInterval: StepWriteInterval? = null
    private var pendingWasRestored: Boolean = false

    fun start(input: WalkingPlanInput, sessionId: String = UUID.randomUUID().toString()): WalkingSessionSnapshot {
        val plan = planner.createWalkingPlan(input)
        require(plan.duration <= MAX_SESSION_DURATION) {
            "Walking session duration must not exceed ${MAX_SESSION_DURATION.toHours()} hours"
        }
        val startedAt = now()
        session = PersistedWalkingSession(
            sessionId = sessionId,
            plan = plan,
            state = WalkingSessionState.RUNNING,
            confirmedSteps = 0,
            pendingChunk = null,
            nextChunkIndex = 0,
            lastConfirmedAt = null,
            lastTickAt = startedAt,
            startedAt = startedAt,
            error = null,
            cadenceStartedAt = startedAt,
            accruedSteps = 0.0,
            activeElapsedMillis = 0L,
        )
        lastInterval = null
        persist()
        return snapshot()
    }

    /** Restores the durable session and moves the cadence cursor to now to avoid downtime backfill. */
    fun restore(): WalkingSessionSnapshot? {
        val restored = store.load() ?: return null
        val restoredAt = now()
        session = if (restored.state == WalkingSessionState.RUNNING && restored.pendingChunk == null) {
            // The process may have been recreated while a sub-minute cadence was
            // pending. Discard that partial cadence rather than attaching its old
            // steps to a new interval; this keeps the persisted interval density
            // honest and never turns process downtime into synthetic activity.
            restored.copy(
                lastTickAt = restoredAt,
                cadenceStartedAt = restoredAt,
                accruedSteps = 0.0,
            )
        } else {
            restored
        }
        pendingWasRestored = restored.state == WalkingSessionState.RUNNING && restored.pendingChunk != null
        if (restored.state == WalkingSessionState.RUNNING) persist()
        lastInterval = null
        return snapshot()
    }

    suspend fun tick(): WalkingSessionSnapshot {
        val current = requireSession()
        if (current.state != WalkingSessionState.RUNNING) return snapshot()

        val pending = current.pendingChunk
        if (pending != null) {
            return writePending(pending)
        }

        val tickAt = now()
        val progressed = accrue(current, tickAt)
        val cadenceStart = progressed.cadenceStartedAt ?: progressed.lastTickAt ?: progressed.startedAt
        val elapsedMillis = Duration.between(cadenceStart, tickAt).toMillis()
        if (elapsedMillis < chunkDuration.toMillis()) {
            session = progressed
            persist()
            return snapshot()
        }

        val remaining = progressed.plan.targetSteps - progressed.confirmedSteps
        val count = min(
            remaining,
            max(1L, progressed.accruedSteps.roundToLong()),
        )
        val chunkIndex = current.nextChunkIndex
        val chunk = PendingWalkingChunk(
            chunkIndex = chunkIndex,
            clientRecordId = "${current.sessionId}:$chunkIndex",
            interval = StepWriteInterval(start = cadenceStart, end = tickAt, count = count),
        )
        session = progressed.copy(
            pendingChunk = chunk,
            cadenceStartedAt = tickAt,
            accruedSteps = (progressed.accruedSteps - count).coerceAtLeast(0.0),
        )
        pendingWasRestored = false
        persist()
        return writePending(chunk)
    }

    /**
     * Applies a new speed without retroactively changing the interval already
     * accumulated at the previous speed. The caller can use the returned snapshot
     * to update the live ETA immediately.
     */
    fun updateSpeed(speedKmh: Double): WalkingSessionSnapshot {
        val current = requireSession()
        val retimed = planner.updateSpeed(current.plan, speedKmh)
        val progressed = if (current.state == WalkingSessionState.RUNNING) {
            accrue(current, now()).also {
                session = it
            }
        } else {
            current
        }
        val remainingSteps = (
            progressed.plan.targetSteps - progressed.confirmedSteps - progressed.accruedSteps
        ).coerceAtLeast(0.0)
        val remainingMillis = (remainingSteps / retimed.stepsPerSecond * 1_000.0)
            .roundToLong()
            .coerceAtLeast(0L)
        require(progressed.activeElapsedMillis + remainingMillis <= MAX_SESSION_DURATION.toMillis()) {
            "Speed would exceed the five-hour walking session limit"
        }
        session = progressed.copy(plan = retimed)
        persist()
        return snapshot()
    }

    fun pause(): WalkingSessionSnapshot {
        val current = requireSession()
        if (current.state == WalkingSessionState.RUNNING) {
            val pausedAt = now()
            val progressed = accrue(current, pausedAt)
            session = progressed.copy(
                state = WalkingSessionState.PAUSED,
                // A paused session deliberately discards an incomplete cadence
                // interval; no paused downtime is ever backfilled on resume.
                accruedSteps = if (progressed.pendingChunk == null) 0.0 else progressed.accruedSteps,
                cadenceStartedAt = pausedAt,
                lastTickAt = pausedAt,
                error = null,
            )
            persist()
        }
        return snapshot()
    }

    fun resume(): WalkingSessionSnapshot {
        val current = requireSession()
        if (current.state == WalkingSessionState.PAUSED) {
            // Reset the cursor so time spent paused cannot become a synthetic chunk.
            val resumedAt = now()
            session = current.copy(
                state = WalkingSessionState.RUNNING,
                lastTickAt = resumedAt,
                cadenceStartedAt = resumedAt,
                accruedSteps = 0.0,
                error = null,
            )
            persist()
        }
        return snapshot()
    }

    fun stop(): WalkingSessionSnapshot {
        val current = requireSession()
        session = current.copy(state = WalkingSessionState.STOPPED, error = null)
        persist()
        return snapshot()
    }

    fun fail(error: Throwable): WalkingSessionSnapshot = fail(error.message ?: error::class.simpleName.orEmpty())

    fun fail(error: String): WalkingSessionSnapshot {
        val current = requireSession()
        session = current.copy(state = WalkingSessionState.FAILED, error = error)
        persist()
        return snapshot()
    }

    private suspend fun writePending(pending: PendingWalkingChunk): WalkingSessionSnapshot {
        val current = requireSession()
        var lastError: Throwable? = null
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            try {
                val result = writer.writeAndVerify(
                    StepWriteRequest(interval = pending.interval, clientRecordId = pending.clientRecordId)
                )
                check(result.verified) {
                    "Step writer did not verify exact count for ${pending.clientRecordId}"
                }
                val confirmedAt = now()
                val confirmed = current.confirmedSteps + pending.interval.count
                val nextCursor = if (pendingWasRestored) confirmedAt else pending.interval.end
                session = current.copy(
                    state = if (confirmed >= current.plan.targetSteps) {
                        WalkingSessionState.COMPLETED
                    } else {
                        WalkingSessionState.RUNNING
                    },
                    confirmedSteps = confirmed,
                    pendingChunk = null,
                    nextChunkIndex = pending.chunkIndex + 1,
                    lastConfirmedAt = confirmedAt,
                    // A restored pending chunk may be hours old. The cursor must remain now.
                    lastTickAt = nextCursor,
                    cadenceStartedAt = confirmedAt,
                    error = null,
                )
                lastInterval = pending.interval
                pendingWasRestored = false
                persist()
                return snapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastError = failure
                if (attempt < MAX_WRITE_ATTEMPTS - 1) retryDelay(retryDelaysMillis[attempt])
            }
        }

        session = current.copy(state = WalkingSessionState.FAILED, error = lastError?.message ?: "Step write failed")
        persist()
        lastInterval = pending.interval
        return snapshot()
    }

    private fun requireSession(): PersistedWalkingSession = session ?: error("Session has not started")

    /** Advances fractional steps and active time up to [at] using the current speed. */
    private fun accrue(current: PersistedWalkingSession, at: Instant): PersistedWalkingSession {
        val cursor = current.lastTickAt ?: current.cadenceStartedAt ?: current.startedAt
        val elapsedMillis = Duration.between(cursor, at).toMillis().coerceAtLeast(0L)
        if (elapsedMillis == 0L) return current
        val generated = current.accruedSteps + current.plan.stepsPerSecond * elapsedMillis / 1_000.0
        return current.copy(
            lastTickAt = at,
            accruedSteps = generated,
            activeElapsedMillis = current.activeElapsedMillis + elapsedMillis,
        )
    }

    private fun persist() {
        session?.let(store::save)
    }

    private fun snapshot(): WalkingSessionSnapshot {
        val current = requireSession()
        return WalkingSessionSnapshot(
            plan = current.plan,
            writtenSteps = current.confirmedSteps,
            isComplete = current.state == WalkingSessionState.COMPLETED ||
                current.confirmedSteps >= current.plan.targetSteps,
            lastInterval = lastInterval,
            sessionId = current.sessionId,
            state = current.state,
            confirmedSteps = current.confirmedSteps,
            pendingChunk = current.pendingChunk,
            nextChunkIndex = current.nextChunkIndex,
            lastConfirmedAt = current.lastConfirmedAt,
            error = current.error,
            currentSpeedKmh = current.plan.speedKmh,
            accruedSteps = current.accruedSteps,
            estimatedRemainingMillis = estimateRemainingMillis(current),
            estimatedEndAt = estimateEndAt(current),
        )
    }

    private fun estimateRemainingMillis(current: PersistedWalkingSession): Long {
        val remaining = (
            current.plan.targetSteps - current.confirmedSteps - current.accruedSteps
        ).coerceAtLeast(0.0)
        return (remaining / current.plan.stepsPerSecond * 1_000.0)
            .roundToLong()
            .coerceAtLeast(0L)
    }

    private fun estimateEndAt(current: PersistedWalkingSession): Instant? {
        if (current.state != WalkingSessionState.RUNNING) return null
        return now().plusMillis(estimateRemainingMillis(current))
    }

    companion object {
        val MAX_SESSION_DURATION: Duration = Duration.ofHours(5)
        const val MAX_WRITE_ATTEMPTS = 3
    }
}

data class WalkingSessionSnapshot(
    val plan: WalkingPlan,
    val writtenSteps: Long,
    val isComplete: Boolean,
    val lastInterval: StepWriteInterval?,
    val sessionId: String = "",
    val state: WalkingSessionState = WalkingSessionState.IDLE,
    val confirmedSteps: Long = writtenSteps,
    val pendingChunk: PendingWalkingChunk? = null,
    val nextChunkIndex: Long = 0,
    val lastConfirmedAt: Instant? = null,
    val error: String? = null,
    val currentSpeedKmh: Double = plan.speedKmh,
    val accruedSteps: Double = 0.0,
    val estimatedRemainingMillis: Long = 0L,
    val estimatedEndAt: Instant? = null,
)
