package com.choupeanut.fitstepcontroller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import com.choupeanut.fitstepcontroller.R
import com.choupeanut.fitstepcontroller.data.HealthConnectStepWriter
import com.choupeanut.fitstepcontroller.domain.StepPlanner
import com.choupeanut.fitstepcontroller.domain.StepWriteInterval
import com.choupeanut.fitstepcontroller.domain.WalkingPlan
import com.choupeanut.fitstepcontroller.domain.WalkingPlanInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToLong

class WalkingSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val planner = StepPlanner()
    private var plan: WalkingPlan? = null
    private var writer: HealthConnectStepWriter? = null
    private var writtenSteps: Long = 0
    private var paused: Boolean = false
    private var lastWriteEnd: Instant = Instant.now()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(intent: Intent) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing walking session"))

        val speed = intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0)
        val target = intent.getLongExtra(EXTRA_TARGET_STEPS, 1000L)
        val stride = intent.getDoubleExtra(EXTRA_STRIDE_METERS, 0.75)

        val currentPlan = planner.createWalkingPlan(WalkingPlanInput(speed, target, stride))
        plan = currentPlan
        writer = HealthConnectStepWriter(HealthConnectClient.getOrCreate(this))
        writtenSteps = 0
        paused = false
        lastWriteEnd = Instant.now()
        publishProgress("Started", currentPlan)

        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(CHUNK_DURATION.toMillis())
                val activePlan = plan ?: return@launch
                if (paused) {
                    publishProgress("Paused", activePlan)
                    continue
                }

                val now = Instant.now()
                val elapsedMillis = Duration.between(lastWriteEnd, now).toMillis()
                if (elapsedMillis <= 0) continue

                val remaining = activePlan.targetSteps - writtenSteps
                val nextSteps = minOf(
                    remaining,
                    max(1, (activePlan.stepsPerSecond * elapsedMillis / 1000.0).roundToLong())
                )
                val interval = StepWriteInterval(
                    start = lastWriteEnd,
                    end = now,
                    count = nextSteps,
                )
                writer?.write(interval)
                writtenSteps += nextSteps
                lastWriteEnd = now
                publishProgress("Running", activePlan)

                if (writtenSteps >= activePlan.targetSteps) {
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    private fun pauseSession() {
        paused = true
        plan?.let { publishProgress("Paused", it) }
    }

    private fun resumeSession() {
        paused = false
        lastWriteEnd = Instant.now()
        plan?.let { publishProgress("Running", it) }
    }

    private fun publishProgress(state: String, activePlan: WalkingPlan) {
        val percent = (writtenSteps * 100 / activePlan.targetSteps).coerceIn(0, 100)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("$state: $writtenSteps/${activePlan.targetSteps} steps ($percent%)")
        )
        sendBroadcast(
            Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_STATE, state)
                putExtra(EXTRA_WRITTEN_STEPS, writtenSteps)
                putExtra(EXTRA_TARGET_STEPS, activePlan.targetSteps)
                putExtra(EXTRA_PERCENT, percent)
                putExtra(EXTRA_PAUSED, paused)
            }
        )
    }

    private fun notification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.health_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "walking-session"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.choupeanut.fitstepcontroller.START_WALKING"
        private const val ACTION_PAUSE = "com.choupeanut.fitstepcontroller.PAUSE_WALKING"
        private const val ACTION_RESUME = "com.choupeanut.fitstepcontroller.RESUME_WALKING"
        private const val ACTION_STOP = "com.choupeanut.fitstepcontroller.STOP_WALKING"
        private const val EXTRA_SPEED_KMH = "speedKmh"
        const val EXTRA_TARGET_STEPS = "targetSteps"
        private const val EXTRA_STRIDE_METERS = "strideMeters"
        private val CHUNK_DURATION: Duration = Duration.ofSeconds(30)

        const val ACTION_PROGRESS = "com.choupeanut.fitstepcontroller.WALKING_PROGRESS"
        const val EXTRA_STATE = "state"
        const val EXTRA_WRITTEN_STEPS = "writtenSteps"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_PAUSED = "paused"

        fun startIntent(context: Context, speedKmh: Double, targetSteps: Long, strideMeters: Double): Intent {
            return Intent(context, WalkingSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SPEED_KMH, speedKmh)
                putExtra(EXTRA_TARGET_STEPS, targetSteps)
                putExtra(EXTRA_STRIDE_METERS, strideMeters)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, WalkingSessionService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun pauseIntent(context: Context): Intent {
            return Intent(context, WalkingSessionService::class.java).apply {
                action = ACTION_PAUSE
            }
        }

        fun resumeIntent(context: Context): Intent {
            return Intent(context, WalkingSessionService::class.java).apply {
                action = ACTION_RESUME
            }
        }
    }
}
