package app.tenshu.fit.ui.screens.record

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.fit.data.RecordingState
import app.tenshu.fit.data.SensorKind
import app.tenshu.fit.data.SensorState
import app.tenshu.fit.data.SensorStatus
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_bpm
import app.tenshu.fit.shared.resources.fit_record_activity_eyebrow
import app.tenshu.fit.shared.resources.fit_record_activity_other
import app.tenshu.fit.shared.resources.fit_record_activity_ride
import app.tenshu.fit.shared.resources.fit_record_activity_run
import app.tenshu.fit.shared.resources.fit_record_activity_strength
import app.tenshu.fit.shared.resources.fit_record_activity_walk
import app.tenshu.fit.shared.resources.fit_record_autostart_cue
import app.tenshu.fit.shared.resources.fit_record_battery_cue
import app.tenshu.fit.shared.resources.fit_record_distance
import app.tenshu.fit.shared.resources.fit_record_elapsed
import app.tenshu.fit.shared.resources.fit_record_finish
import app.tenshu.fit.shared.resources.fit_record_hr
import app.tenshu.fit.shared.resources.fit_record_offline_cue
import app.tenshu.fit.shared.resources.fit_record_pace
import app.tenshu.fit.shared.resources.fit_record_pause
import app.tenshu.fit.shared.resources.fit_record_resume
import app.tenshu.fit.shared.resources.fit_record_sensor_connect
import app.tenshu.fit.shared.resources.fit_record_sensor_gps
import app.tenshu.fit.shared.resources.fit_record_sensor_gps_hint
import app.tenshu.fit.shared.resources.fit_record_sensor_hr
import app.tenshu.fit.shared.resources.fit_record_sensor_hr_hint
import app.tenshu.fit.shared.resources.fit_record_sensor_ready
import app.tenshu.fit.shared.resources.fit_record_sensor_searching
import app.tenshu.fit.shared.resources.fit_record_sensor_steps
import app.tenshu.fit.shared.resources.fit_record_sensor_steps_hint
import app.tenshu.fit.shared.resources.fit_record_sensors_eyebrow
import app.tenshu.fit.shared.resources.fit_record_return_to_workout
import app.tenshu.fit.shared.resources.fit_record_review_workout
import app.tenshu.fit.shared.resources.fit_record_start
import app.tenshu.fit.shared.resources.fit_record_steps
import app.tenshu.fit.shared.resources.fit_record_title
import app.tenshu.fit.ui.components.Eyebrow
import app.tenshu.fit.ui.components.FitCard
import app.tenshu.fit.ui.components.FitIcons
import app.tenshu.fit.ui.components.FitTopBar
import app.tenshu.fit.ui.components.activityName
import app.tenshu.fit.ui.components.distanceValueUnit
import app.tenshu.fit.ui.components.durationClock
import app.tenshu.fit.ui.components.groupThousands
import app.tenshu.fit.ui.components.intOrDash
import app.tenshu.fit.ui.components.paceValueUnit
import app.tenshu.fit.ui.theme.tabular
import app.tenshu.ui.components.LightSystemBarIcons
import app.tenshu.ui.theme.TenshuTheme
import kotlin.time.Clock
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How recently the phone must have killed us for the vendor auto-start cue to be worth showing. */
private const val RECENT_KILL_MS = 30L * 24 * 60 * 60 * 1000

internal val RECORD_ACTIVITIES = listOf(
    ActivityType.Running to Res.string.fit_record_activity_run,
    ActivityType.Walking to Res.string.fit_record_activity_walk,
    ActivityType.Cycling to Res.string.fit_record_activity_ride,
    ActivityType.Strength to Res.string.fit_record_activity_strength,
    ActivityType.Other to Res.string.fit_record_activity_other,
)

/**
 * Record — pre-start (docs/proposals/fit-app.md screen 7): activity picker, sensor status,
 * de-Googled offline cue, big Start. Start arms the mock recorder and pushes the live
 * surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordPreScreen(
    viewModel: RecordViewModel,
    onStart: () -> Unit,
    onReview: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()

    // Just-in-time, per-capability permissions (§9.8): nothing is requested on entering the Record
    // flow. Location + steps + the ongoing-recording notification are asked for when the user taps
    // Start; Bluetooth only when they choose to connect a heart-rate strap. Each OS dialog is
    // preceded by an in-app primer, so no cold batch of dialogs on screen entry.
    val permissionGate = rememberRecorderPermissionGate()
    var priming by remember { mutableStateOf<RecorderCapability?>(null) }
    val hrGranted = permissionGate.isGranted(RecorderCapability.HeartRate)

    // Battery-optimisation exemption: permissions decide whether capture may read a sensor, this
    // decides whether the process survives long enough to keep reading it (see [BackgroundRunGate]).
    val backgroundGate = rememberBackgroundRunGate()
    val backgroundExempt = backgroundGate.isExempt()
    // Has this phone ended one of our processes recently? Only then is the vendor auto-start
    // screen worth pointing at — see the cue below. The window is generous (a month) because the
    // OS keeps at most 16 exit records and a runner may only meet the problem occasionally.
    val oemSettings = rememberOemBackgroundSettings()
    val deathReporter = rememberProcessDeathReporter()
    val killedBefore = remember(deathReporter) {
        deathReporter.lastSystemKillWallMs()?.let {
            Clock.System.now().toEpochMilliseconds() - it < RECENT_KILL_MS
        } == true
    }
    // Every path that begins a session nudges once — a recording that the OS ends at minute 21 is
    // exactly the loss this prompt exists to prevent.
    val startRecording = {
        backgroundGate.requestExemption()
        viewModel.start()
        onStart()
    }

    priming?.let { capability ->
        RecorderPermissionPrimer(
            capability = capability,
            onContinue = {
                priming = null
                permissionGate.request(capability) {
                    // Proceed regardless of the grant — the engine degrades per stream.
                    when (capability) {
                        RecorderCapability.Motion -> startRecording()
                        RecorderCapability.HeartRate -> viewModel.rearmSensors()
                    }
                }
            },
            onDismiss = { priming = null },
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FitTopBar(
                title = stringResource(Res.string.fit_record_title),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        // ⚠⚠ A `Column`, and it is load-bearing — do not collapse it into the scrolling child.
        // Start is a SIBLING of the scroll area (below), pinned to the bottom of the screen. The
        // `Scaffold` content slot is NOT a Column: `ScaffoldLayout` places every body measurable at
        // (0, 0), so two children there OVERLAP rather than stack. §18.15 removed this wrapper and
        // gave the scroll area `fillMaxSize()`, which put Start behind the activity chips at the
        // top — from the user's side it simply vanished. `weight(1f)` on the scroll area is the
        // other half: it is what leaves room for Start instead of consuming the full height.
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Eyebrow(stringResource(Res.string.fit_record_activity_eyebrow), Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RECORD_ACTIVITIES.forEach { (type, labelRes) ->
                        ActivityChip(
                            type = type,
                            labelRes = labelRes,
                            selected = state.selectedActivity == type,
                            onClick = { viewModel.selectActivity(type) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Eyebrow(stringResource(Res.string.fit_record_sensors_eyebrow), Modifier.padding(bottom = 8.dp))
                FitCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                    Column {
                        state.sensors.forEachIndexed { i, sensor ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SensorRow(
                                sensor = sensor,
                                // Offer "Connect" on the HR row only while its Bluetooth grant is
                                // outstanding — the JIT strap opt-in (§9.8). Once granted the row shows
                                // Searching/Ready like the others.
                                showConnect = sensor.kind == SensorKind.HeartRate &&
                                    sensor.state != SensorState.Ready && !hrGranted,
                                onConnect = { priming = RecorderCapability.HeartRate },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FitIcons.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(Res.string.fit_record_offline_cue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The way back for anyone who dismissed the one-time Start-time prompt: without
                // it, the single most damaging setting for a recorder is invisible from inside the
                // app. Hidden entirely once exempt — nothing to act on, so nothing to say.
                if (!backgroundExempt) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { backgroundGate.requestExemption(force = true) },
                    ) {
                        Icon(
                            FitIcons.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(Res.string.fit_record_battery_cue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // Exempt and *still* killed: the AOSP lever was pulled and the vendor's own
                // auto-start list is what is left (§13.5d). Evidence-gated on purpose — this is
                // guesswork about a screen we cannot read, so it is only worth a user's attention
                // once their phone has actually ended a recording.
                if (backgroundExempt && killedBefore && oemSettings.canOpen()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { oemSettings.openBackgroundSettings() },
                    ) {
                        Icon(
                            FitIcons.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(Res.string.fit_record_autostart_cue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Box(Modifier.padding(16.dp)) {
                // A session already in flight redirects the big button (RC-2): back-navigating off the
                // live or review screen must never lead to a Start that clobbers it.
                val active = state.recording is RecordingState.Active
                val reviewing = state.recording is RecordingState.Reviewing
                Button(
                    onClick = {
                        when {
                            reviewing -> onReview()
                            active -> onStart()
                            // Motion (location + steps + notification) is the JIT ask at Start; if
                            // already held we begin immediately, otherwise the primer → OS dialog →
                            // start.
                            permissionGate.isGranted(RecorderCapability.Motion) -> startRecording()
                            else -> priming = RecorderCapability.Motion
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = CircleShape,
                ) {
                    Icon(FitIcons.Play, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = when {
                            reviewing -> stringResource(Res.string.fit_record_review_workout)
                            active -> stringResource(Res.string.fit_record_return_to_workout)
                            else -> stringResource(Res.string.fit_record_start)
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActivityChip(
    type: ActivityType,
    labelRes: StringResource,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                FitIcons.activity(type),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SensorRow(
    sensor: SensorStatus,
    showConnect: Boolean = false,
    onConnect: () -> Unit = {},
) {
    val (nameRes, hintRes, icon) = when (sensor.kind) {
        SensorKind.Gps -> Triple(Res.string.fit_record_sensor_gps, Res.string.fit_record_sensor_gps_hint, FitIcons.Gps)
        SensorKind.HeartRate -> Triple(Res.string.fit_record_sensor_hr, Res.string.fit_record_sensor_hr_hint, FitIcons.Heart)
        SensorKind.StepCounter -> Triple(Res.string.fit_record_sensor_steps, Res.string.fit_record_sensor_steps_hint, FitIcons.Steps)
    }
    val ready = sensor.state == SensorState.Ready
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(nameRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(hintRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showConnect) {
            TextButton(onClick = onConnect, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    text = stringResource(Res.string.fit_record_sensor_connect),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (ready) {
                    Icon(FitIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                }
                Text(
                    text = if (ready) stringResource(Res.string.fit_record_sensor_ready) else stringResource(Res.string.fit_record_sensor_searching),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Record — live (docs/proposals/fit-app.md screen 8). **Always dark** regardless of the app
 * theme (D7): wrapped in a nested [TenshuTheme] with `useDarkTheme = true`. Two numeral
 * treatments; tapping the metric area toggles hero ⇄ grid. Stop parks the session as a
 * Reviewing draft (RC-2) — the shell swaps this screen for the review surface off the state
 * flip; the save-outcome dialogs live there now.
 */
@Composable
fun RecordLiveScreen(viewModel: RecordViewModel) {
    val state by viewModel.uiState.collectAsState()
    // ⚠ Dark in either theme and full-bleed under the status bar, so the bars' icons must be light
    // in both — in light mode the window would otherwise draw dark icons on this surface.
    LightSystemBarIcons()

    TenshuTheme(useDarkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val active = state.recording as? RecordingState.Active
            // ⚠⚠ The one Fit screen with no `FitTopBar`, so the one that has to spell its inset
            // out. `LiveTopBar` is not a top app bar — it is the activity readout on a deliberately
            // full-bleed dark surface — and after the §18.15 flip the shell no longer pads the top
            // for anyone. Without this the activity name draws under the status bar.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                LiveTopBar(active?.activityType ?: state.selectedActivity)
                Box(
                    Modifier.weight(1f).fillMaxWidth().clickable { viewModel.toggleLiveLayout() },
                ) {
                    if (active != null) {
                        if (state.liveLayout == LiveLayout.Hero) HeroLayout(active) else GridLayout(active)
                    }
                }
                LiveControls(
                    paused = active?.paused == true,
                    onPauseResume = { if (active?.paused == true) viewModel.resume() else viewModel.pause() },
                    onFinish = viewModel::stop,
                )
            }
        }
    }
}

@Composable
private fun LiveTopBar(activityType: ActivityType) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FitIcons.activity(activityType), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(activityName(activityType), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LiveChip(FitIcons.Gps, "GPS")
            LiveChip(FitIcons.Heart, "HR")
        }
    }
}

@Composable
private fun LiveChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeroLayout(active: RecordingState.Active) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BigMetric(value = durationClock(active.elapsedSec), unit = null, label = stringResource(Res.string.fit_record_elapsed), huge = true)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(FitIcons.Heart, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                text = "${intOrDash(active.heartRateBpm)} ${stringResource(Res.string.fit_bpm)}",
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val distance = distanceValueUnit(active.distanceM)
            val pace = paceValueUnit(active.paceSecPerKm)
            BigMetric(distance.value, distance.unit, stringResource(Res.string.fit_record_distance))
            BigMetric(pace.value, pace.unit, stringResource(Res.string.fit_record_pace))
            BigMetric(groupThousands(active.steps), null, stringResource(Res.string.fit_record_steps))
        }
    }
}

@Composable
private fun GridLayout(active: RecordingState.Active) {
    val distance = distanceValueUnit(active.distanceM)
    val pace = paceValueUnit(active.paceSecPerKm)
    // 1dp gaps over the outlineVariant background read as hairline dividers between cells.
    Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                GridCellBox(durationClock(active.elapsedSec), null, stringResource(Res.string.fit_record_elapsed), Modifier.weight(1f))
                GridCellBox(distance.value, distance.unit, stringResource(Res.string.fit_record_distance), Modifier.weight(1f))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                GridCellBox(pace.value, pace.unit, stringResource(Res.string.fit_record_pace), Modifier.weight(1f))
                GridCellBox(intOrDash(active.heartRateBpm), stringResource(Res.string.fit_bpm), stringResource(Res.string.fit_record_hr), Modifier.weight(1f), accent = true)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                GridCellBox(groupThousands(active.steps), null, stringResource(Res.string.fit_record_steps), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GridCellBox(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxHeight()) {
        Box(contentAlignment = Alignment.Center) {
            BigMetric(value, unit, label, accent = accent)
        }
    }
}

@Composable
private fun BigMetric(value: String, unit: String?, label: String, huge: Boolean = false, accent: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.displayLarge.tabular().copy(
                    fontSize = if (huge) 76.sp else 40.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (huge) 12.dp else 4.dp),
                )
            }
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveControls(paused: Boolean, onPauseResume: () -> Unit, onFinish: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
    ) {
        RoundControl(
            icon = if (paused) FitIcons.Play else FitIcons.Pause,
            label = if (paused) stringResource(Res.string.fit_record_resume) else stringResource(Res.string.fit_record_pause),
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            onClick = onPauseResume,
        )
        RoundControl(
            icon = FitIcons.Stop,
            label = stringResource(Res.string.fit_record_finish),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.error,
            onClick = onFinish,
        )
    }
}

@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = onClick, shape = CircleShape, color = container, modifier = Modifier.size(76.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(30.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
