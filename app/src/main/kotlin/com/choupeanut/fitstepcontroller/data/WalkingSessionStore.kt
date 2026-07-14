package com.choupeanut.fitstepcontroller.data

import android.annotation.SuppressLint
import android.content.Context
import com.choupeanut.fitstepcontroller.domain.PendingWalkingChunk
import com.choupeanut.fitstepcontroller.domain.PersistedWalkingSession
import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import com.choupeanut.fitstepcontroller.domain.WalkingPlan
import com.choupeanut.fitstepcontroller.domain.WalkingSessionState
import java.time.Duration
import java.time.Instant

/** Durable boundary for a walking session. Implementations must replace the snapshot atomically. */
interface WalkingSessionStore {
    fun load(): PersistedWalkingSession?
    fun save(session: PersistedWalkingSession)
    fun clear()
}

/** Small deterministic store used by domain and service tests. */
class InMemoryWalkingSessionStore : WalkingSessionStore {
    private var session: PersistedWalkingSession? = null

    override fun load(): PersistedWalkingSession? = session

    override fun save(session: PersistedWalkingSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

/** SharedPreferences-backed store; the domain remains independent of Android persistence APIs. */
class SharedPreferencesWalkingSessionStore(
    context: Context,
    name: String = "walking-session",
) : WalkingSessionStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun load(): PersistedWalkingSession? {
        if (!preferences.getBoolean(KEY_EXISTS, false)) return null
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return null
        val state = runCatching {
            WalkingSessionState.valueOf(preferences.getString(KEY_STATE, null) ?: return null)
        }.getOrNull() ?: return null
        val targetSteps = preferences.getLong(KEY_TARGET_STEPS, 0L)
        val speed = preferences.getFloat(KEY_SPEED_KMH, 0f).toDouble()
        val stride = preferences.getFloat(KEY_STRIDE_METERS, 0f).toDouble()
        val distance = preferences.getFloat(KEY_DISTANCE_METERS, 0f).toDouble()
        val duration = Duration.ofMillis(preferences.getLong(KEY_DURATION_MILLIS, 0L))
        val stepsPerSecond = preferences.getFloat(KEY_STEPS_PER_SECOND, 0f).toDouble()
        val warnings = preferences.getString(KEY_WARNINGS, "")
            .orEmpty()
            .split(WARNINGS_SEPARATOR)
            .filter(String::isNotEmpty)
        val plan = WalkingPlan(
            speedKmh = speed,
            targetSteps = targetSteps,
            strideMeters = stride,
            distanceMeters = distance,
            duration = duration,
            stepsPerSecond = stepsPerSecond,
            warnings = warnings,
        )
        val pending = if (preferences.getBoolean(KEY_HAS_PENDING, false)) {
            PendingWalkingChunk(
                chunkIndex = preferences.getLong(KEY_PENDING_INDEX, 0L),
                clientRecordId = preferences.getString(KEY_PENDING_ID, "") ?: "",
                interval = StepWriteInterval(
                    start = Instant.parse(preferences.getString(KEY_PENDING_START, null)),
                    end = Instant.parse(preferences.getString(KEY_PENDING_END, null)),
                    count = preferences.getLong(KEY_PENDING_COUNT, 0L),
                ),
            )
        } else {
            null
        }
        return PersistedWalkingSession(
            sessionId = sessionId,
            plan = plan,
            state = state,
            confirmedSteps = preferences.getLong(KEY_CONFIRMED_STEPS, 0L),
            pendingChunk = pending,
            nextChunkIndex = preferences.getLong(KEY_NEXT_CHUNK_INDEX, 0L),
            lastConfirmedAt = preferences.getString(KEY_LAST_CONFIRMED_AT, null)?.let(Instant::parse),
            lastTickAt = preferences.getString(KEY_LAST_TICK_AT, null)?.let(Instant::parse),
            startedAt = Instant.parse(preferences.getString(KEY_STARTED_AT, null)),
            error = preferences.getString(KEY_ERROR, null),
            cadenceStartedAt = preferences.getString(KEY_CADENCE_STARTED_AT, null)?.let(Instant::parse),
            accruedSteps = preferences.getLong(KEY_ACCRUED_STEPS, 0L).let(Double::fromBits),
            activeElapsedMillis = preferences.getLong(KEY_ACTIVE_ELAPSED_MILLIS, 0L),
        )
    }

    override fun save(session: PersistedWalkingSession) {
        val editor = preferences.edit()
            .clear()
            .putBoolean(KEY_EXISTS, true)
            .putString(KEY_SESSION_ID, session.sessionId)
            .putString(KEY_STATE, session.state.name)
            .putLong(KEY_CONFIRMED_STEPS, session.confirmedSteps)
            .putLong(KEY_NEXT_CHUNK_INDEX, session.nextChunkIndex)
            .putString(KEY_LAST_CONFIRMED_AT, session.lastConfirmedAt?.toString())
            .putString(KEY_LAST_TICK_AT, session.lastTickAt?.toString())
            .putString(KEY_STARTED_AT, session.startedAt.toString())
            .putString(KEY_ERROR, session.error)
            .putString(KEY_CADENCE_STARTED_AT, session.cadenceStartedAt?.toString())
            .putLong(KEY_ACCRUED_STEPS, session.accruedSteps.toBits())
            .putLong(KEY_ACTIVE_ELAPSED_MILLIS, session.activeElapsedMillis)
            .putLong(KEY_TARGET_STEPS, session.plan.targetSteps)
            .putFloat(KEY_SPEED_KMH, session.plan.speedKmh.toFloat())
            .putFloat(KEY_STRIDE_METERS, session.plan.strideMeters.toFloat())
            .putFloat(KEY_DISTANCE_METERS, session.plan.distanceMeters.toFloat())
            .putLong(KEY_DURATION_MILLIS, session.plan.duration.toMillis())
            .putFloat(KEY_STEPS_PER_SECOND, session.plan.stepsPerSecond.toFloat())
            .putString(KEY_WARNINGS, session.plan.warnings.joinToString(WARNINGS_SEPARATOR))
            .putBoolean(KEY_HAS_PENDING, session.pendingChunk != null)
        session.pendingChunk?.let { pending ->
            editor
                .putLong(KEY_PENDING_INDEX, pending.chunkIndex)
                .putString(KEY_PENDING_ID, pending.clientRecordId)
                .putString(KEY_PENDING_START, pending.interval.start.toString())
                .putString(KEY_PENDING_END, pending.interval.end.toString())
                .putLong(KEY_PENDING_COUNT, pending.interval.count)
        }
        check(editor.commit()) { "Unable to persist walking session" }
    }

    @SuppressLint("ApplySharedPref")
    override fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_EXISTS = "exists"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_STATE = "state"
        const val KEY_CONFIRMED_STEPS = "confirmedSteps"
        const val KEY_NEXT_CHUNK_INDEX = "nextChunkIndex"
        const val KEY_LAST_CONFIRMED_AT = "lastConfirmedAt"
        const val KEY_LAST_TICK_AT = "lastTickAt"
        const val KEY_STARTED_AT = "startedAt"
        const val KEY_ERROR = "error"
        const val KEY_CADENCE_STARTED_AT = "cadenceStartedAt"
        const val KEY_ACCRUED_STEPS = "accruedSteps"
        const val KEY_ACTIVE_ELAPSED_MILLIS = "activeElapsedMillis"
        const val KEY_TARGET_STEPS = "targetSteps"
        const val KEY_SPEED_KMH = "speedKmh"
        const val KEY_STRIDE_METERS = "strideMeters"
        const val KEY_DISTANCE_METERS = "distanceMeters"
        const val KEY_DURATION_MILLIS = "durationMillis"
        const val KEY_STEPS_PER_SECOND = "stepsPerSecond"
        const val KEY_WARNINGS = "warnings"
        const val KEY_HAS_PENDING = "hasPending"
        const val KEY_PENDING_INDEX = "pendingIndex"
        const val KEY_PENDING_ID = "pendingId"
        const val KEY_PENDING_START = "pendingStart"
        const val KEY_PENDING_END = "pendingEnd"
        const val KEY_PENDING_COUNT = "pendingCount"
        const val WARNINGS_SEPARATOR = "\u001f"
    }
}
