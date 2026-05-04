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
import com.choupeanut.fitstepcontroller.domain.WalkingPlanInput
import com.choupeanut.fitstepcontroller.domain.WalkingSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

class WalkingSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
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

        val writer = HealthConnectStepWriter(HealthConnectClient.getOrCreate(this))
        val controller = WalkingSessionController(
            planner = StepPlanner(),
            writer = writer,
            chunkDuration = Duration.ofSeconds(30),
        )
        controller.start(WalkingPlanInput(speed, target, stride))

        job?.cancel()
        job = scope.launch {
            while (true) {
                val snapshot = controller.tick()
                val percent = (snapshot.writtenSteps * 100 / snapshot.plan.targetSteps).coerceIn(0, 100)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(
                    NOTIFICATION_ID,
                    notification("${snapshot.writtenSteps}/${snapshot.plan.targetSteps} steps ($percent%)")
                )
                if (snapshot.isComplete) {
                    stopSelf()
                    return@launch
                }
                delay(30_000)
            }
        }
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
        private const val ACTION_STOP = "com.choupeanut.fitstepcontroller.STOP_WALKING"
        private const val EXTRA_SPEED_KMH = "speedKmh"
        private const val EXTRA_TARGET_STEPS = "targetSteps"
        private const val EXTRA_STRIDE_METERS = "strideMeters"

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
    }
}
