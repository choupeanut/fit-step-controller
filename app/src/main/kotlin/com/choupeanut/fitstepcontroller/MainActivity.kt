package com.choupeanut.fitstepcontroller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import com.choupeanut.fitstepcontroller.auth.GoogleSignInResult
import com.choupeanut.fitstepcontroller.auth.GoogleSignInManager
import com.choupeanut.fitstepcontroller.data.HealthConnectGateway
import com.choupeanut.fitstepcontroller.data.HealthConnectStepWriter
import com.choupeanut.fitstepcontroller.data.StepWriteCursorStore
import com.choupeanut.fitstepcontroller.domain.StepPlanner
import com.choupeanut.fitstepcontroller.domain.StepWindowAllocator
import com.choupeanut.fitstepcontroller.domain.WalkingPlanInput
import com.choupeanut.fitstepcontroller.service.WalkingSessionService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.launch
import java.time.Instant

data class WalkingUiState(
    val state: String = "Idle",
    val writtenSteps: Long = 0,
    val targetSteps: Long = 0,
    val percent: Int = 0,
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
)

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
    val signInManager = remember { GoogleSignInManager(activity) }
    val healthGateway = remember { HealthConnectGateway(activity) }
    val planner = remember { StepPlanner() }
    val cursorStore = remember { StepWriteCursorStore(activity) }

    var account by remember { mutableStateOf<GoogleSignInAccount?>(null) }
    var hasHealthPermission by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var speed by remember { mutableStateOf(5.0) }
    var stride by remember { mutableStateOf("0.75") }
    var walkTarget by remember { mutableStateOf("1000") }
    var directSteps by remember { mutableStateOf("500") }
    var directStatus by remember { mutableStateOf("Idle") }
    var walkingUiState by remember { mutableStateOf(WalkingUiState()) }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (val signInResult = signInManager.parseResult(result.data)) {
            is GoogleSignInResult.Success -> {
                account = signInResult.account
                status = "Signed in as ${signInResult.account.email}"
            }
            is GoogleSignInResult.Failure -> {
                account = null
                status = signInResult.displayMessage()
            }
        }
    }
    val healthPermissionLauncher = rememberLauncherForActivityResult(healthGateway.permissionContract()) { granted ->
        hasHealthPermission = granted.containsAll(healthGateway.permissions)
        status = if (hasHealthPermission) "Health Connect permissions granted" else "Health Connect permissions are required"
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        account = signInManager.lastSignedInAccount(activity)
        if (healthGateway.status() == HealthConnectClient.SDK_AVAILABLE) {
            hasHealthPermission = healthGateway.hasPermissions()
        }
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
                walkingUiState = WalkingUiState(
                    state = progressState,
                    writtenSteps = written,
                    targetSteps = target,
                    percent = percent,
                    isActive = progressState != "Stopped" && target > 0 && written < target,
                    isPaused = isPaused,
                )
            }
        }
        val filter = IntentFilter(WalkingSessionService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }
        onDispose {
            activity.unregisterReceiver(receiver)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = { TopAppBar(title = { Text("Fit Step Controller") }) }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StatusCard(
                        accountEmail = account?.email,
                        hasHealthPermission = hasHealthPermission,
                        status = status,
                        onSignIn = { signInLauncher.launch(signInManager.signInIntent()) },
                        onHealthConnect = {
                            when (healthGateway.status()) {
                                HealthConnectClient.SDK_AVAILABLE -> healthPermissionLauncher.launch(healthGateway.permissions)
                                else -> activity.startActivity(healthGateway.installIntent())
                            }
                        },
                    )

                    ModeWalkingCard(
                        speed = speed,
                        onSpeedChange = { speed = it },
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
                                status = it.message ?: "Invalid walking plan"
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
                            )
                            status = "Walking mode started for ${plan.targetSteps} steps"
                        },
                        onPause = {
                            activity.startService(WalkingSessionService.pauseIntent(activity))
                            walkingUiState = walkingUiState.copy(state = "Paused", isPaused = true)
                            status = "Walking mode paused"
                        },
                        onResume = {
                            activity.startService(WalkingSessionService.resumeIntent(activity))
                            walkingUiState = walkingUiState.copy(state = "Running", isPaused = false)
                            status = "Walking mode resumed"
                        },
                        onStop = {
                            activity.startService(WalkingSessionService.stopIntent(activity))
                            walkingUiState = WalkingUiState(state = "Stopped")
                            status = "Walking mode stopped"
                        },
                    )

                    ModeDirectCard(
                        steps = directSteps,
                        onStepsChange = { directSteps = it },
                        enabled = hasHealthPermission,
                        directStatus = directStatus,
                        onWrite = {
                            val steps = directSteps.toLongOrNull() ?: 0
                            if (steps <= 0) {
                                directStatus = "Enter a positive step count"
                                status = directStatus
                                return@ModeDirectCard
                            }
                            directStatus = "Writing $steps steps to Health Connect..."
                            status = directStatus
                            scope.launch {
                                runCatching {
                                    val writer = HealthConnectStepWriter(
                                        client = healthGateway.client(),
                                        appPackageName = activity.packageName,
                                    )
                                    val allocator = StepWindowAllocator(
                                        planner = planner,
                                        loadCursor = { cursorStore.loadDirectCursor() },
                                        saveCursor = { cursorStore.saveDirectCursor(it) },
                                    )
                                    val interval = allocator.allocatePastWindow(steps, Instant.now())
                                    writer.write(interval)
                                    val rawTotal = writer.readRaw(interval.start, interval.end).sumOf { it.count }
                                    val aggregateTotal = writer.readTotal(interval.start, interval.end)
                                    "Requested $steps steps; app raw records read $rawTotal; Health Connect aggregate reads $aggregateTotal in that non-overlapping interval"
                                }.onFailure {
                                    val message = it.message ?: "Direct write failed"
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

@Composable
private fun StatusCard(
    accountEmail: String?,
    hasHealthPermission: Boolean,
    status: String,
    onSignIn: () -> Unit,
    onHealthConnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Account and data access", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (accountEmail != null) Icons.Default.CheckCircle else Icons.Default.Login,
                    contentDescription = null,
                )
                Text(accountEmail ?: "Not signed in")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (hasHealthPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                )
                Text(if (hasHealthPermission) "Health Connect ready" else "Health Connect permission needed")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSignIn) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Text("Google")
                }
                OutlinedButton(onClick = onHealthConnect) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Text("Health")
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeWalkingCard(
    speed: Double,
    onSpeedChange: (Double) -> Unit,
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
    val preview = runCatching {
        planner.createWalkingPlan(
            WalkingPlanInput(
                speedKmh = speed,
                targetSteps = target.toLongOrNull() ?: 0,
                strideMeters = stride.toDoubleOrNull() ?: 0.75,
            )
        )
    }.getOrNull()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mode 1: paced walking", style = MaterialTheme.typography.titleMedium)
            Text("${"%.1f".format(speed)} km/h")
            Slider(value = speed.toFloat(), onValueChange = { onSpeedChange(it.toDouble()) }, valueRange = 3f..12f)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = onTargetChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Target steps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = stride,
                    onValueChange = onStrideChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("Stride m") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            if (preview != null) {
                Text("Distance ${preview.distanceMeters.toLong()} m, duration ${preview.duration.toMinutes()} min")
                preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (walkingUiState.isActive || walkingUiState.state != "Idle") {
                LinearProgressIndicator(
                    progress = { (walkingUiState.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${walkingUiState.state}: ${walkingUiState.writtenSteps}/${walkingUiState.targetSteps} steps (${walkingUiState.percent}%)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStart, enabled = enabled && !walkingUiState.isActive) {
                    Icon(Icons.Default.DirectionsWalk, contentDescription = null)
                    Text("Start")
                }
                if (walkingUiState.isActive && !walkingUiState.isPaused) {
                    OutlinedButton(onClick = onPause) {
                        Text("Pause")
                    }
                }
                if (walkingUiState.isActive && walkingUiState.isPaused) {
                    OutlinedButton(onClick = onResume) {
                        Text("Resume")
                    }
                }
                OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun ModeDirectCard(
    steps: String,
    onStepsChange: (String) -> Unit,
    enabled: Boolean,
    directStatus: String,
    onWrite: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mode 2: direct step entry", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = steps,
                onValueChange = onStepsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Steps") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(onClick = onWrite, enabled = enabled) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Text("Write now")
            }
            Text(directStatus, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Google Fit display requires Google Fit to sync with Health Connect on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
