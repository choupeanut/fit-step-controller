package com.choupeanut.fitstepcontroller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import com.choupeanut.fitstepcontroller.data.HealthConnectGateway
import com.choupeanut.fitstepcontroller.data.HealthConnectStepWriter
import com.choupeanut.fitstepcontroller.data.SharedPreferencesWalkingSessionStore
import com.choupeanut.fitstepcontroller.data.StepWriteRequest
import com.choupeanut.fitstepcontroller.domain.StepAvailability
import com.choupeanut.fitstepcontroller.domain.StepPlanner
import com.choupeanut.fitstepcontroller.domain.WalkingPlanInput
import com.choupeanut.fitstepcontroller.domain.WalkingSessionState
import com.choupeanut.fitstepcontroller.service.WalkingSessionService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class WalkingUiState(
    val state: String = "Idle",
    val writtenSteps: Long = 0,
    val targetSteps: Long = 0,
    val percent: Int = 0,
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val error: String? = null,
    val currentSpeedKmh: Double = 0.0,
    val remainingMillis: Long = 0L,
    val estimatedEndAtMillis: Long = -1L,
)

private const val SPEED_UPDATE_THROTTLE_MILLIS = 250L
private val QUICK_SPEEDS_KMH = (3..10).toList()

private val FitStepColorScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF176B67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EEE7),
    onPrimaryContainer = Color(0xFF003733),
    secondary = Color(0xFFE47763),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD2),
    onSecondaryContainer = Color(0xFF3A0904),
    tertiary = Color(0xFF607C72),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFE2F0EC),
    onSurface = Color(0xFF17201E),
    onSurfaceVariant = Color(0xFF3F4A47),
)

private enum class AppSection(val label: String) {
    WALKING("持續步行"),
    BACKFILL("空檔補步"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FitStepApp(activity = this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitStepApp(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    val healthGateway = remember { HealthConnectGateway(activity) }
    val planner = remember { StepPlanner() }
    val walkingStore = remember { SharedPreferencesWalkingSessionStore(activity) }

    var hasHealthPermission by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("請先設定 Health Connect 權限") }
    var speed by remember { mutableStateOf(5.0) }
    var stride by remember { mutableStateOf("0.75") }
    var walkTarget by remember { mutableStateOf("1000") }
    var directSteps by remember { mutableStateOf("500") }
    var directStatus by remember { mutableStateOf("尚未執行") }
    var walkingUiState by remember { mutableStateOf(WalkingUiState()) }
    var isSpeedDragging by remember { mutableStateOf(false) }
    var speedDispatchJob by remember { mutableStateOf<Job?>(null) }
    var lastSpeedDispatchAt by remember { mutableStateOf(0L) }
    var availability by remember { mutableStateOf<StepAvailability?>(null) }
    var availabilityStatus by remember { mutableStateOf("尚未掃描") }
    var availabilityScanning by remember { mutableStateOf(false) }
    var backfillSteps by remember { mutableStateOf("1000") }
    var useLast12Hours by rememberSaveable { mutableStateOf(true) }
    var selectedSection by remember { mutableStateOf(AppSection.WALKING) }

    val healthPermissionLauncher = rememberLauncherForActivityResult(healthGateway.permissionContract()) { granted ->
        hasHealthPermission = granted.containsAll(healthGateway.permissions)
        status = if (hasHealthPermission) "Health Connect 權限已啟用" else "需要 Health Connect 權限"
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    suspend fun scanAvailability() {
        if (!hasHealthPermission || availabilityScanning) return
        availabilityScanning = true
        runCatching {
            HealthConnectStepWriter(
                client = healthGateway.client(),
                appPackageName = activity.packageName,
            ).readBackfillAvailability(useLast12Hours = useLast12Hours)
        }.onSuccess { result ->
            availability = result
            availabilityStatus = if (result == null) {
                "今日可掃描時段從本地 00:00 開始"
            } else {
                "已找到 ${result.availableWindows.size} 個可用空檔"
            }
        }.onFailure { failure ->
            availabilityStatus = failure.message ?: "無法讀取 Health Connect 步數"
            status = availabilityStatus
        }
        availabilityScanning = false
    }

    LaunchedEffect(Unit) {
        if (healthGateway.status() == HealthConnectClient.SDK_AVAILABLE) {
            hasHealthPermission = healthGateway.hasPermissions()
        }
        walkingStore.load()?.let { persisted ->
            val remainingMillis = (
                (persisted.plan.targetSteps - persisted.confirmedSteps).coerceAtLeast(0L) /
                    persisted.plan.stepsPerSecond * 1_000.0
                ).toLong().coerceAtLeast(0L)
            walkingUiState = WalkingUiState(
                state = persisted.state.name,
                writtenSteps = persisted.confirmedSteps,
                targetSteps = persisted.plan.targetSteps,
                percent = (persisted.confirmedSteps * 100 / persisted.plan.targetSteps).toInt().coerceIn(0, 100),
                isActive = persisted.state == WalkingSessionState.RUNNING ||
                    persisted.state == WalkingSessionState.PAUSED,
                isPaused = persisted.state == WalkingSessionState.PAUSED,
                error = persisted.error,
                currentSpeedKmh = persisted.plan.speedKmh,
                remainingMillis = remainingMillis,
                estimatedEndAtMillis = if (persisted.state == WalkingSessionState.RUNNING) {
                    System.currentTimeMillis() + remainingMillis
                } else {
                    -1L
                },
            )
            speed = persisted.plan.speedKmh
        }
    }

    LaunchedEffect(hasHealthPermission, useLast12Hours) {
        if (hasHealthPermission) scanAvailability()
    }

    DisposableEffect(hasHealthPermission, useLast12Hours) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasHealthPermission) {
                scope.launch { scanAvailability() }
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WalkingSessionService.ACTION_PROGRESS) return
                val target = intent.getLongExtra(WalkingSessionService.EXTRA_TARGET_STEPS, 0L)
                val written = intent.getLongExtra(WalkingSessionService.EXTRA_WRITTEN_STEPS, 0L)
                val progressState = intent.getStringExtra(WalkingSessionService.EXTRA_STATE) ?: "Running"
                val percent = intent.getIntExtra(WalkingSessionService.EXTRA_PERCENT, 0)
                val isPaused = intent.getBooleanExtra(WalkingSessionService.EXTRA_PAUSED, false)
                val error = intent.getStringExtra(WalkingSessionService.EXTRA_ERROR)
                val currentSpeed = intent.getDoubleExtra(
                    WalkingSessionService.EXTRA_CURRENT_SPEED_KMH,
                    speed,
                )
                val remainingMillis = intent.getLongExtra(WalkingSessionService.EXTRA_REMAINING_MILLIS, 0L)
                val estimatedEndAt = intent.getLongExtra(WalkingSessionService.EXTRA_ESTIMATED_END_AT, -1L)
                walkingUiState = WalkingUiState(
                    state = progressState,
                    writtenSteps = written,
                    targetSteps = target,
                    percent = percent,
                    isActive = progressState.equals("RUNNING", ignoreCase = true) ||
                        progressState.equals("PAUSED", ignoreCase = true) ||
                        progressState.equals("STARTING", ignoreCase = true),
                    isPaused = isPaused,
                    error = error,
                    currentSpeedKmh = currentSpeed,
                    remainingMillis = remainingMillis,
                    estimatedEndAtMillis = estimatedEndAt,
                )
                // A service progress broadcast can arrive while the user is still
                // dragging. Do not overwrite the gesture's local value mid-drag.
                if (currentSpeed > 0.0 && !isSpeedDragging) speed = currentSpeed
                if (error != null) status = error
            }
        }
        val filter = IntentFilter(WalkingSessionService.ACTION_PROGRESS)
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            activity.unregisterReceiver(receiver)
        }
    }

    MaterialTheme(colorScheme = FitStepColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Pets,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("步數控制器")
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppSection.values().forEach { section ->
                            NavigationBarItem(
                                selected = selectedSection == section,
                                onClick = { selectedSection = section },
                                icon = {
                                    Icon(
                                        imageVector = if (section == AppSection.WALKING) {
                                            Icons.AutoMirrored.Filled.DirectionsWalk
                                        } else {
                                            Icons.Default.Sync
                                        },
                                        contentDescription = section.label,
                                    )
                                },
                                label = { Text(section.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                ) {
                    HealthStatusBanner(
                        hasHealthPermission = hasHealthPermission,
                        status = status,
                        onHealthConnect = {
                            when (healthGateway.status()) {
                                HealthConnectClient.SDK_AVAILABLE -> healthPermissionLauncher.launch(healthGateway.permissions)
                                else -> activity.startActivity(healthGateway.installIntent())
                            }
                        },
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        when (selectedSection) {
                            AppSection.WALKING -> ModeWalkingCard(
                                speed = speed,
                                onSpeedChange = { value ->
                                    isSpeedDragging = true
                                    speed = value
                                    if (walkingUiState.isActive && !walkingUiState.isPaused) {
                                        val now = SystemClock.elapsedRealtime()
                                        val elapsed = now - lastSpeedDispatchAt
                                        val dispatch = {
                                            speedDispatchJob?.cancel()
                                            lastSpeedDispatchAt = SystemClock.elapsedRealtime()
                                            activity.startService(
                                                WalkingSessionService.updateSpeedIntent(activity, value)
                                            )
                                        }
                                        if (elapsed >= SPEED_UPDATE_THROTTLE_MILLIS) {
                                            dispatch()
                                        } else {
                                            speedDispatchJob?.cancel()
                                            speedDispatchJob = scope.launch {
                                                delay(SPEED_UPDATE_THROTTLE_MILLIS - elapsed)
                                                dispatch()
                                            }
                                        }
                                    }
                                },
                                onSpeedChangeFinished = { finalSpeed ->
                                    if (walkingUiState.isActive && !walkingUiState.isPaused) {
                                        speedDispatchJob?.cancel()
                                        lastSpeedDispatchAt = SystemClock.elapsedRealtime()
                                        activity.startService(
                                            WalkingSessionService.updateSpeedIntent(activity, finalSpeed)
                                        )
                                    }
                                    isSpeedDragging = false
                                },
                                target = walkTarget,
                                onTargetChange = { walkTarget = it },
                                stride = stride,
                                onStrideChange = { stride = it },
                                planner = planner,
                                enabled = hasHealthPermission,
                                walkingUiState = walkingUiState,
                                onStart = {
                                    val target = walkTarget.toLongOrNull() ?: 0
                                    val strideMeters = stride.toDoubleOrNull() ?: 0.75
                                    val plan = runCatching {
                                        planner.createWalkingPlan(WalkingPlanInput(speed, target, strideMeters))
                                    }.getOrElse {
                                        status = it.message ?: "步行計畫無效"
                                        return@ModeWalkingCard
                                    }
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    val intent = WalkingSessionService.startIntent(activity, plan.speedKmh, plan.targetSteps, plan.strideMeters)
                                    ContextCompat.startForegroundService(activity, intent)
                                    walkingUiState = WalkingUiState(
                                        state = "Starting",
                                        writtenSteps = 0,
                                        targetSteps = plan.targetSteps,
                                        percent = 0,
                                        isActive = true,
                                        isPaused = false,
                                        currentSpeedKmh = plan.speedKmh,
                                        remainingMillis = plan.duration.toMillis(),
                                        estimatedEndAtMillis = System.currentTimeMillis() + plan.duration.toMillis(),
                                    )
                                    status = "Mode 1 已開始：目標 ${plan.targetSteps} 步"
                                },
                                onPause = {
                                    activity.startService(WalkingSessionService.pauseIntent(activity))
                                    walkingUiState = walkingUiState.copy(state = "Paused", isPaused = true)
                                    status = "Mode 1 已暫停"
                                },
                                onResume = {
                                    activity.startService(WalkingSessionService.resumeIntent(activity))
                                    walkingUiState = walkingUiState.copy(state = "Running", isPaused = false)
                                    status = "Mode 1 已繼續"
                                },
                                onStop = {
                                    activity.startService(WalkingSessionService.stopIntent(activity))
                                    walkingUiState = WalkingUiState(state = "Stopped")
                                    status = "Mode 1 已停止"
                                },
                            )

                            AppSection.BACKFILL -> {
                                ModeBackfillCard(
                                    enabled = hasHealthPermission,
                                    steps = backfillSteps,
                                    onStepsChange = { backfillSteps = it.filter(Char::isDigit) },
                                    useLast12Hours = useLast12Hours,
                                    onUseLast12HoursChange = { checked ->
                                        useLast12Hours = checked
                                        availability = null
                                        availabilityStatus = "補步範圍已變更，正在重新掃描"
                                    },
                                    availability = availability,
                                    status = availabilityStatus,
                                    scanning = availabilityScanning,
                                    onRefresh = { scope.launch { scanAvailability() } },
                                    onWrite = {
                                        val requested = backfillSteps.toLongOrNull() ?: 0L
                                        val current = availability
                                        if (requested <= 0L) {
                                            availabilityStatus = "請輸入大於 0 的步數"
                                            status = availabilityStatus
                                            return@ModeBackfillCard
                                        }
                                        if (current == null) {
                                            availabilityStatus = if (useLast12Hours) {
                                                "尚未取得最近 12 小時空檔，請先重新掃描"
                                            } else {
                                                "尚未取得今日空檔，請先重新掃描"
                                            }
                                            status = availabilityStatus
                                            return@ModeBackfillCard
                                        }
                                        if (requested > current.maxSteps) {
                                            availabilityStatus = "要求 $requested 步，超過目前上限 ${current.maxSteps} 步"
                                            status = availabilityStatus
                                            return@ModeBackfillCard
                                        }
                                        scope.launch {
                                            availabilityScanning = true
                                            runCatching {
                                                val writer = HealthConnectStepWriter(
                                                    client = healthGateway.client(),
                                                    appPackageName = activity.packageName,
                                                )
                                                // Re-scan immediately before planning writes so a new
                                                // record from another source cannot be overwritten.
                                                val fresh = writer.readBackfillAvailability(
                                                    useLast12Hours = useLast12Hours,
                                                ) ?: error(
                                                    if (useLast12Hours) {
                                                        "最近 12 小時尚無可掃描時段"
                                                    } else {
                                                        "今日可掃描時段從本地 00:00 開始"
                                                    },
                                                )
                                                availability = fresh
                                                require(requested <= fresh.maxSteps) {
                                                    "要求 $requested 步，超過重新掃描後的上限 ${fresh.maxSteps} 步"
                                                }
                                                writer.backfillAvailableSteps(
                                                    rangeStart = fresh.rangeStart,
                                                    rangeEnd = fresh.rangeEnd,
                                                    requestedSteps = requested,
                                                    batchId = "mode2:${UUID.randomUUID()}",
                                                )
                                            }.onSuccess { result ->
                                                availability = result.finalAvailability
                                                val message = if (result.completed) {
                                                    "Mode 2 已在 ${result.allocations.size} 個空檔寫入 ${result.writtenSteps} 步"
                                                } else {
                                                    "Mode 2 已寫入 ${result.writtenSteps}/${result.requestedSteps} 步；${result.failure ?: "尚未完成"}"
                                                }
                                                availabilityStatus = message
                                                status = message
                                            }.onFailure { failure ->
                                                availabilityStatus = failure.message ?: "Mode 2 補步失敗"
                                                status = availabilityStatus
                                            }
                                            availabilityScanning = false
                                        }
                                    },
                                )
                                var showAdvancedDirect by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { showAdvancedDirect = !showAdvancedDirect },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = if (showAdvancedDirect) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(if (showAdvancedDirect) "收合進階寫入" else "進階：直接寫入步數")
                                }
                                if (showAdvancedDirect) {
                                    ModeDirectCard(
                                        steps = directSteps,
                                        onStepsChange = { directSteps = it.filter(Char::isDigit) },
                                        enabled = hasHealthPermission,
                                        directStatus = directStatus,
                                        onWrite = {
                                            val steps = directSteps.toLongOrNull() ?: 0
                                            if (steps <= 0) {
                                                directStatus = "請輸入大於 0 的步數"
                                                status = directStatus
                                                return@ModeDirectCard
                                            }
                                            directStatus = "正在將 $steps 步寫入 Health Connect…"
                                            status = directStatus
                                            scope.launch {
                                                runCatching {
                                                    val writer = HealthConnectStepWriter(
                                                        client = healthGateway.client(),
                                                        appPackageName = activity.packageName,
                                                    )
                                                    val interval = planner.directInterval(steps, Instant.now())
                                                    val result = writer.writeAndVerify(
                                                        StepWriteRequest(
                                                            interval = interval,
                                                            clientRecordId = "direct:${UUID.randomUUID()}",
                                                        )
                                                    )
                                                    check(result.verified) { "Health Connect 紀錄驗證失敗" }
                                                    "已要求 $steps 步；App 讀到 ${result.exactRecordCount} 筆紀錄，區間彙總 ${result.aggregateSteps ?: "無資料"} 步"
                                                }.onFailure {
                                                    val message = it.message ?: "直接寫入失敗"
                                                    directStatus = message
                                                    status = message
                                                }.onSuccess { message ->
                                                    directStatus = message
                                                    status = message
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthStatusBanner(
    hasHealthPermission: Boolean,
    status: String,
    onHealthConnect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (hasHealthPermission) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        },
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (hasHealthPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (hasHealthPermission) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasHealthPermission) "Health Connect 已連線" else "需要 Health Connect 權限",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (!hasHealthPermission) {
                OutlinedButton(onClick = onHealthConnect) {
                    Text("設定")
                }
            }
        }
    }
}

@Composable
private fun ModeWalkingCard(
    speed: Double,
    onSpeedChange: (Double) -> Unit,
    onSpeedChangeFinished: (Double) -> Unit,
    target: String,
    onTargetChange: (String) -> Unit,
    stride: String,
    onStrideChange: (String) -> Unit,
    planner: StepPlanner,
    enabled: Boolean,
    walkingUiState: WalkingUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    var sliderValue by remember { mutableStateOf(speed.toFloat()) }
    var isSliderDragging by remember { mutableStateOf(false) }

    LaunchedEffect(speed, isSliderDragging) {
        if (!isSliderDragging) sliderValue = speed.toFloat()
    }

    val preview = runCatching {
        planner.createWalkingPlan(
            WalkingPlanInput(
                speedKmh = speed,
                targetSteps = target.toLongOrNull() ?: 0,
                strideMeters = stride.toDoubleOrNull() ?: 0.75,
            )
        )
    }.getOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("持續步行", style = MaterialTheme.typography.headlineSmall)
                    Text("依指定速度逐步寫入步數", style = MaterialTheme.typography.bodyMedium)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text("目前速率", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${"%.1f".format(speed)} km/h",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            if (preview != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WalkingStat(
                        label = "目標步數",
                        value = "${preview.targetSteps}",
                        modifier = Modifier.weight(1f),
                    )
                    WalkingStat(
                        label = "預估時間",
                        value = formatDuration(preview.duration.toMillis()),
                        modifier = Modifier.weight(1f),
                    )
                    WalkingStat(
                        label = "距離",
                        value = "${preview.distanceMeters.toLong()} m",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text("調整步速", style = MaterialTheme.typography.titleMedium)
            val speedControlsEnabled = enabled && !walkingUiState.isPaused
            val sliderRange = 3f..12f
            SpeedSlider(
                value = sliderValue,
                valueRange = sliderRange,
                enabled = speedControlsEnabled,
                onValueChange = {
                    isSliderDragging = true
                    sliderValue = it
                    onSpeedChange(it.toDouble())
                },
                onValueChangeFinished = {
                    isSliderDragging = false
                    onSpeedChangeFinished(sliderValue.toDouble())
                },
            )
            Text("快速選擇（km/h）", style = MaterialTheme.typography.labelLarge)
            QUICK_SPEEDS_KMH.chunked(4).forEach { rowSpeeds ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowSpeeds.forEach { quickSpeed ->
                        val isSelected = kotlin.math.abs(speed - quickSpeed) < 0.05
                        val onQuickSpeed = {
                            onSpeedChange(quickSpeed.toDouble())
                            onSpeedChangeFinished(quickSpeed.toDouble())
                        }
                        if (isSelected) {
                            Button(
                                onClick = onQuickSpeed,
                                enabled = speedControlsEnabled,
                                modifier = Modifier.weight(1f),
                            ) { Text("$quickSpeed") }
                        } else {
                            OutlinedButton(
                                onClick = onQuickSpeed,
                                enabled = speedControlsEnabled,
                                modifier = Modifier.weight(1f),
                            ) { Text("$quickSpeed") }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = onTargetChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("目標步數") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !walkingUiState.isActive,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = stride,
                    onValueChange = onStrideChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("步幅（公尺）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !walkingUiState.isActive,
                    singleLine = true,
                )
            }

            if (preview != null) {
                Text(
                    "預計完成：${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + preview.duration.toMillis()))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                preview.warnings.forEach { warning ->
                    Text(
                        localizedPlanWarning(warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (walkingUiState.isActive || walkingUiState.state != "Idle") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { (walkingUiState.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${localizedWalkingState(walkingUiState.state)}：${walkingUiState.writtenSteps}/${walkingUiState.targetSteps} 步（${walkingUiState.percent}%）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (walkingUiState.currentSpeedKmh > 0.0) {
                            Text(
                                "目前 ${"%.1f".format(walkingUiState.currentSpeedKmh)} km/h · 剩餘 ${formatEta(walkingUiState.remainingMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val endAt = walkingUiState.estimatedEndAtMillis
                            if (endAt > 0L && !walkingUiState.isPaused) {
                                Text(
                                    "預計結束：${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(endAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = enabled && !walkingUiState.isActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("開始")
                }
                if (walkingUiState.isActive && !walkingUiState.isPaused) {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("暫停") }
                }
                if (walkingUiState.isActive && walkingUiState.isPaused) {
                    OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f)) { Text("繼續") }
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = walkingUiState.isActive,
                    modifier = Modifier.weight(1f),
                ) { Text("停止") }
            }
        }
    }
}

@Composable
private fun SpeedSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val density = LocalDensity.current
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val inactiveColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }

    fun valueAtPosition(x: Float, width: Int): Float {
        val horizontalPadding = with(density) { 12.dp.toPx() }
        val trackWidth = (width / 1f - horizontalPadding * 2f).coerceAtLeast(1f)
        val fraction = ((x - horizontalPadding) / trackWidth).coerceIn(0f, 1f)
        return valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = "調整步速"
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, 0)
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    val next = target.coerceIn(valueRange.start, valueRange.endInclusive)
                    latestOnValueChange(next)
                    latestOnValueChangeFinished()
                    true
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tappedValue = valueAtPosition(down.position.x, size.width)
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        change.consume()
                        latestOnValueChange(valueAtPosition(change.position.x, size.width))
                    }
                    if (drag != null) {
                        horizontalDrag(drag.id) { change ->
                            change.consume()
                            latestOnValueChange(valueAtPosition(change.position.x, size.width))
                        }
                    } else {
                        // A release before horizontal touch-slop is a track tap.
                        latestOnValueChange(tappedValue)
                    }
                    latestOnValueChangeFinished()
                }
            },
    ) {
        val horizontalPadding = 12.dp.toPx()
        val trackStart = horizontalPadding
        val trackEnd = size.width - horizontalPadding
        val centerY = size.height / 2f
        val trackHeight = 4.dp.toPx()
        val thumbRadius = 10.dp.toPx()
        val fraction = ((value - valueRange.start) /
            (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val thumbX = trackStart + (trackEnd - trackStart) * fraction
        drawRoundRect(
            color = inactiveColor,
            topLeft = androidx.compose.ui.geometry.Offset(trackStart, centerY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(trackEnd - trackStart, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
        )
        drawRoundRect(
            color = activeColor,
            topLeft = androidx.compose.ui.geometry.Offset(trackStart, centerY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(thumbX - trackStart, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
        )
        drawCircle(color = activeColor, radius = thumbRadius, center = androidx.compose.ui.geometry.Offset(thumbX, centerY))
    }
}

@Composable
private fun WalkingStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun localizedWalkingState(state: String): String = when (state.uppercase()) {
    "STARTING" -> "準備中"
    "RUNNING" -> "執行中"
    "PAUSED" -> "已暫停"
    "STOPPED" -> "已停止"
    "COMPLETED" -> "已完成"
    "ERROR" -> "發生錯誤"
    "IDLE" -> "尚未開始"
    else -> state
}

private fun localizedPlanWarning(warning: String): String = when {
    warning.startsWith("Speed is above") -> {
        val limit = warning.substringAfter("above ").substringBefore(" km/h")
        "速度高於 $limit km/h；部分應用程式可能將其視為非步行資料。"
    }
    warning.startsWith("Step density is above") -> {
        val limit = warning.substringAfter("above ").substringBefore(" steps/sec")
        "步數密度高於 $limit 步／秒；資料可能被拒絕或忽略。"
    }
    else -> warning
}

private fun formatEta(millis: Long): String {
    val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%dh %02dm".format(hours, minutes)
    } else {
        "%dm %02ds".format(minutes, seconds)
    }
}

@Composable
private fun ModeBackfillCard(
    enabled: Boolean,
    steps: String,
    onStepsChange: (String) -> Unit,
    useLast12Hours: Boolean,
    onUseLast12HoursChange: (Boolean) -> Unit,
    availability: StepAvailability?,
    status: String,
    scanning: Boolean,
    onRefresh: () -> Unit,
    onWrite: () -> Unit,
) {
    val requested = steps.toLongOrNull() ?: 0L
    val canWrite = availability?.let { window ->
        enabled && !scanning && requested in 1..window.maxSteps
    } ?: false

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("空檔補步", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (useLast12Hours) {
                        "只填入最近 12 小時內沒有步行紀錄的時間段"
                    } else {
                        "只填入今天 00:00 後沒有步行紀錄的時間段"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = useLast12Hours,
                    onCheckedChange = { checked -> onUseLast12HoursChange(checked) },
                    enabled = enabled && !scanning,
                )
                Text("最多只從目前時間往前 12 小時補送步數")
            }
            if (availability == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("尚未取得可用空檔", style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onRefresh, enabled = enabled && !scanning) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                if (scanning) {
                                    "掃描中…"
                                } else if (useLast12Hours) {
                                    "掃描最近 12 小時空檔"
                                } else {
                                    "掃描今日空檔"
                                },
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("目前最多可補步數", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${availability.maxSteps} 步",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text("以最快 10 km/h 計算，僅供上限參考", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WalkingStat(
                        label = "可用時間",
                        value = formatDuration(availability.totalAvailableDuration.toMillis()),
                        modifier = Modifier.weight(1f),
                    )
                    WalkingStat(
                        label = "空檔數量",
                        value = "${availability.availableWindows.size} 段",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "範圍：${formatLocalDateTime(availability.rangeStart)} – ${formatLocalDateTime(availability.rangeEnd)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("最近掃描：${formatLocalDateTime(availability.scannedAt)}", style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = steps,
                        onValueChange = onStepsChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("要補入的步數") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = enabled && !scanning,
                        isError = requested !in 1..availability.maxSteps,
                        singleLine = true,
                        supportingText = {
                            Text("上限 ${availability.maxSteps} 步")
                        },
                    )
                    OutlinedButton(onClick = onRefresh, enabled = enabled && !scanning) {
                        Text(if (scanning) "掃描中…" else "重新掃描")
                    }
                }
                Button(
                    onClick = onWrite,
                    enabled = canWrite,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("依時間順序填入空檔")
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "%dh %02dm".format(hours, minutes) else "%dm".format(minutes)
}

private fun formatLocalDateTime(instant: Instant): String {
    return instant.atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ')
}

@Composable
private fun ModeDirectCard(
    steps: String,
    onStepsChange: (String) -> Unit,
    enabled: Boolean,
    directStatus: String,
    onWrite: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("直接寫入步數", style = MaterialTheme.typography.titleMedium)
            Text(
                "僅供測試用：建立一筆短時間 Health Connect 步數紀錄。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = steps,
                onValueChange = onStepsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("步數") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = enabled,
                singleLine = true,
            )
            Button(onClick = onWrite, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(3.dp))
                Text("立即寫入")
            }
            Text(directStatus, style = MaterialTheme.typography.bodySmall)
            Text(
                "Google Fit 是否顯示，取決於裝置上 Google Fit 與 Health Connect 的同步設定。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
