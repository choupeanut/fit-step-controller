package com.choupeanut.fitstepcontroller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WalkingSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val planner = StepPlanner()
    private var controller: WalkingSessionController? = null
    private var sessionLoaded = false
    /** Serializes every controller operation, including an in-flight provider write. */
    private val sessionMutex = Mutex()
    /** Preserves the order of lifecycle commands received by onStartCommand. */
    private var commandTail: Job? = null

    private val sessionStore by lazy { SharedPreferencesWalkingSessionStore(this) }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent, startId)
            ACTION_PAUSE -> pauseSession(startId)
            ACTION_RESUME -> resumeSession(startId)
            ACTION_STOP -> stopSession(startId)
            null -> restoreSession(startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        enqueueCommand {
            try {
                failAndPublish("Android dataSync foreground service timeout")
            } catch (_: CancellationException) {
                return@enqueueCommand
            } finally {
                stopSelf(startId)
            }
        }
    }

    private fun startSession(intent: Intent, startId: Int) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing walking session"))

        val speed = intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0)
        val target = intent.getLongExtra(EXTRA_TARGET_STEPS, 1000L)
        val stride = intent.getDoubleExtra(EXTRA_STRIDE_METERS, 0.75)

        enqueueCommand {
            try {
                stopLoopAndJoin()
                val started = sessionMutex.withLock {
                    requireController().start(WalkingPlanInput(speed, target, stride)).also {
                        sessionLoaded = true
                    }
                }
                publishProgress(started)
                launchLoop(startId)
            } catch (cancelled: CancellationException) {
                return@enqueueCommand
            } catch (failure: Throwable) {
                failAndPublish(failure.message ?: "Unable to start walking session")
                stopSelf(startId)
            }
        }
    }

    private fun restoreSession(startId: Int) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Restoring walking session"))
        enqueueCommand {
            try {
                val restored = sessionMutex.withLock {
                    requireController().restore().also { sessionLoaded = true }
                }
                if (restored == null) {
                    stopSelf(startId)
                    return@enqueueCommand
                }
                publishProgress(restored)
                if (restored.state == WalkingSessionState.RUNNING) {
                    launchLoop(startId)
                } else {
                    // Paused and terminal sessions must not keep a dataSync FGS alive.
                    stopSelf(startId)
                }
            } catch (cancelled: CancellationException) {
                return@enqueueCommand
            } catch (failure: Throwable) {
                failAndPublish(failure.message ?: "Unable to restore walking session")
                stopSelf(startId)
            }
        }
    }

    private fun resumeSession(startId: Int) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Resuming walking session"))
        enqueueCommand {
            try {
                stopLoopAndJoin()
                val resumed = sessionMutex.withLock {
                    val current = requireController().restore()
                    sessionLoaded = current != null
                    if (current?.state == WalkingSessionState.PAUSED) {
                        requireController().resume()
                    } else {
                        current
                    }
                }
                if (resumed == null) {
                    stopSelf(startId)
                    return@enqueueCommand
                }
                publishProgress(resumed)
                if (resumed.state == WalkingSessionState.RUNNING) {
                    launchLoop(startId)
                } else {
                    stopSelf(startId)
                }
            } catch (cancelled: CancellationException) {
                return@enqueueCommand
            } catch (failure: Throwable) {
                failAndPublish(failure.message ?: "Unable to resume walking session")
                stopSelf(startId)
            }
        }
    }

    private fun stopSession(startId: Int) {
        enqueueCommand {
            try {
                stopLoopAndJoin()
                val stopped = sessionMutex.withLock {
                    val restored = requireController().restore()
                    sessionLoaded = restored != null
                    if (restored == null) null else requireController().stop()
                }
                if (stopped != null) publishProgress(stopped)
            } catch (cancelled: CancellationException) {
                return@enqueueCommand
            } catch (failure: Throwable) {
                failAndPublish(failure.message ?: "Unable to stop walking session")
            } finally {
                stopSelf(startId)
            }
        }
    }

    private fun pauseSession(startId: Int) {
        // Cancel first, then join under the same mutex used by tick(). This prevents
        // an in-flight write from persisting RUNNING after PAUSED was saved.
        job?.cancel()
        enqueueCommand {
            try {
                stopLoopAndJoin()
                val paused = sessionMutex.withLock {
                    restoreControllerIfNeeded()
                    requireController().pause()
                }
                publishProgress(paused)
            } catch (cancelled: CancellationException) {
                return@enqueueCommand
            } catch (failure: Throwable) {
                failAndPublish(failure.message ?: "Unable to pause walking session")
            } finally {
                // A paused session is durable and can be resumed by a new foreground start.
                stopSelf(startId)
            }
        }
    }

    private fun launchLoop(startId: Int) {
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MILLIS)
                val snapshot = try {
                    sessionMutex.withLock {
                        requireController().tick().also(::publishProgress)
                    }
                } catch (cancelled: CancellationException) {
                    return@launch
                } catch (failure: Throwable) {
                    failAndPublish(failure.message ?: "Walking session failed")
                    stopSelf(startId)
                    return@launch
                }
                if (snapshot.state == WalkingSessionState.COMPLETED ||
                    snapshot.state == WalkingSessionState.FAILED ||
                    snapshot.state == WalkingSessionState.STOPPED
                ) {
                    stopSelf(startId)
                    return@launch
                }
            }
        }
    }

    private fun enqueueCommand(block: suspend () -> Unit) {
        val previous = commandTail
        commandTail = scope.launch {
            previous?.join()
            block()
        }
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

    private suspend fun failAndPublish(message: String) {
        val snapshot = sessionMutex.withLock {
            val current = sessionStore.load()
            if (current != null) {
                restoreControllerIfNeeded()
                requireController().fail(message)
            } else {
                null
            }
        }
        if (snapshot != null) publishProgress(snapshot) else {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Failed: $message"),
            )
        }
    }

    private suspend fun stopLoopAndJoin() {
        job?.cancelAndJoin()
        job = null
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.health_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
