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
import com.choupeanut.fitstepcontroller.data.SharedPreferencesWalkingSessionStore
import com.choupeanut.fitstepcontroller.domain.StepPlanner
import com.choupeanut.fitstepcontroller.domain.WalkingSessionController
import com.choupeanut.fitstepcontroller.domain.WalkingSessionSnapshot
import com.choupeanut.fitstepcontroller.domain.WalkingSessionState
import com.choupeanut.fitstepcontroller.domain.WalkingPlanInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WalkingSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val planner = StepPlanner()
    private var controller: WalkingSessionController? = null
    private var sessionLoaded = false

    private val sessionStore by lazy { SharedPreferencesWalkingSessionStore(this) }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_PAUSE -> runCatching { updateSession { it.pause() } }
                .onFailure { failure -> publishFailure(failure.message ?: "Unable to pause walking session") }
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> runCatching { stopSession() }
                .onFailure { failure -> publishFailure(failure.message ?: "Unable to stop walking session"); stopSelf() }
            null -> restoreSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        publishFailure("Android dataSync foreground service timeout")
        stopSelf(startId)
    }

    private fun startSession(intent: Intent) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing walking session"))

        val speed = intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0)
        val target = intent.getLongExtra(EXTRA_TARGET_STEPS, 1000L)
        val stride = intent.getDoubleExtra(EXTRA_STRIDE_METERS, 0.75)

        try {
            val started = requireController().start(WalkingPlanInput(speed, target, stride))
            sessionLoaded = true
            publishProgress(started)
            launchLoop()
        } catch (failure: Throwable) {
            val message = failure.message ?: "Unable to start walking session"
            publishFailure(message)
            stopSelf()
        }
    }

    private fun restoreSession() {
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, notification("Restoring walking session"))
            val restored = requireController().restore() ?: run {
                stopSelf()
                return
            }
            sessionLoaded = true
            publishProgress(restored)
            if (restored.state == WalkingSessionState.RUNNING) launchLoop()
        } catch (failure: Throwable) {
            publishFailure(failure.message ?: "Unable to restore walking session")
            stopSelf()
        }
    }

    private fun resumeSession() {
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, notification("Resuming walking session"))
            val current = requireController().restore()
            sessionLoaded = current != null
            val resumed = if (current?.state == WalkingSessionState.PAUSED) {
                requireController().resume()
            } else {
                current
            }
            if (resumed != null) {
                publishProgress(resumed)
                if (resumed.state == WalkingSessionState.RUNNING) launchLoop()
            }
        } catch (failure: Throwable) {
            publishFailure(failure.message ?: "Unable to resume walking session")
            stopSelf()
        }
    }

    private fun stopSession() {
        job?.cancel()
        runCatching { updateSession { it.stop() } }
        stopSelf()
    }

    private fun launchLoop() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MILLIS)
                val snapshot = runCatching { requireController().tick() }.getOrElse { failure ->
                    publishFailure(failure.message ?: "Walking session failed")
                    stopSelf()
                    return@launch
                }
                publishProgress(snapshot)
                if (snapshot.state == WalkingSessionState.COMPLETED ||
                    snapshot.state == WalkingSessionState.FAILED ||
                    snapshot.state == WalkingSessionState.STOPPED
                ) {
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    private fun updateSession(operation: (WalkingSessionController) -> WalkingSessionSnapshot) {
        restoreControllerIfNeeded()
        val snapshot = operation(requireController())
        publishProgress(snapshot)
    }

    private fun createController(): WalkingSessionController {
        val writer = HealthConnectStepWriter(
            client = HealthConnectClient.getOrCreate(this),
            appPackageName = packageName,
        )
        return WalkingSessionController(
            planner = planner,
            writer = writer,
            store = sessionStore,
        )
    }

    private fun requireController(): WalkingSessionController {
        return controller ?: createController().also { controller = it }
    }

    private fun publishFailure(message: String) {
        val current = sessionStore.load()
        val snapshot = runCatching {
            if (current != null) {
                restoreControllerIfNeeded()
                requireController().fail(message)
            } else {
                null
            }
        }.getOrNull()
        if (snapshot != null) publishProgress(snapshot) else {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Failed: $message"),
            )
        }
    }

    private fun restoreControllerIfNeeded() {
        if (!sessionLoaded) {
            requireController().restore()
            sessionLoaded = true
        }
    }

    private fun publishProgress(snapshot: WalkingSessionSnapshot) {
        val target = snapshot.plan.targetSteps
        val percent = (snapshot.confirmedSteps * 100 / target).coerceIn(0, 100)
        val state = snapshot.state.name.replace('_', ' ')
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification("$state: ${snapshot.confirmedSteps}/$target steps ($percent%)"),
        )
        sendBroadcast(
            Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_STATE, state)
                putExtra(EXTRA_WRITTEN_STEPS, snapshot.confirmedSteps)
                putExtra(EXTRA_TARGET_STEPS, target)
                putExtra(EXTRA_PERCENT, percent)
                putExtra(EXTRA_PAUSED, snapshot.state == WalkingSessionState.PAUSED)
                putExtra(EXTRA_SESSION_ID, snapshot.sessionId)
                putExtra(EXTRA_ERROR, snapshot.error)
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
        private const val TICK_INTERVAL_MILLIS = 1_000L

        const val ACTION_PROGRESS = "com.choupeanut.fitstepcontroller.WALKING_PROGRESS"
        const val EXTRA_STATE = "state"
        const val EXTRA_WRITTEN_STEPS = "writtenSteps"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_ERROR = "error"

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
