package app.tenshu.timeline.android

import org.tenshu.core.common.app.OpenTargetIntents
import org.tenshu.core.common.app.OpenTargetInbox
import android.content.Intent
import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.tenshu.timeline.capture.TimelineCaptureControl
import app.tenshu.timeline.ui.App
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

// Timeline Android entry point (docs/proposals/timeline-app.md §7 Ph7). ComponentActivity provides
// ViewModelStore / SavedState / Lifecycle owners for free, so the UI shell is just
// TenshuTheme { App(openTargets = this@MainActivity.openTargets.targets) }.
//
// I3b-2: this also *enacts* the live-capture opt-in. It observes the persisted
// TimelineCaptureControl (flipped by the Settings toggle — capture is off by default, threat-model
// A35) and, when enabled, grants the permissions capture needs then starts the capture foreground
// service; when disabled it stops the service. A denied *location* permission resets the opt-in
// honestly.
class MainActivity : ComponentActivity() {

    /**
     * Where a cross-app open goes (`control-cross-app-open.md` TT-2b).
     *
     * ⚠⚠ Injected, not constructed: the SAME inbox is what the transport pushes a warm request
     * into, so a second instance would make that path deliver to something nobody collects.
     */
    private val openTargets: OpenTargetInbox by inject()

    /** ⚠ No-ops for an ordinary launch — the extra is simply absent. */
    private fun readOpenTarget(intent: Intent) {
        OpenTargetIntents.targetFrom(intent) { getKoin().get() }?.let(openTargets::deliver)
    }

    /**
     * ⚠⚠ Control mints a launch ticket whenever the handler must be brought forward — **including
     * when it is already running** (§5e.1) — so the common case is a launch that RESUMES this
     * activity and never reaches `onCreate`. Reading only there would make a warm open silent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenTarget(intent)
    }

    private val control: TimelineCaptureControl by inject()
    private val launcher: TimelineCaptureServiceLauncher by inject()
    private val importControl: AndroidTimelineImportControl by inject()

    /**
     * B2 — the Takeout picker. `OpenDocument` rather than `GetContent`, for the reason Fit found:
     * it gives a stable, re-readable URI and lets the user reach Drive/Downloads, which is where an
     * export actually lands.
     *
     * ⚠ The MIME list is broad because Android's document providers disagree about what a zip is
     * (`application/zip`, `application/x-zip-compressed`, and Drive's own `application/octet-stream`
     * for anything it did not recognise). A narrow filter greys out the very file the user came for.
     */
    private val pickTakeout =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importControl.onPicked(uri)
        }

    /**
     * ⚠⚠ Decides from the **live permission state**, not from the result map.
     *
     * The map only carries keys that were actually requested, so once location is granted and only
     * physical activity is outstanding, `grants[ACCESS_FINE_LOCATION]` is `null` — and the previous
     * `== true` test would have read that absence as a refusal and switched the user's capture opt-in
     * off for a permission they had already given.
     */
    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                maybeAskForBackgroundLocation()
                startIfEnabled()
            } else {
                control.setEnabled(false) // location refused → reflect it back in the toggle
            }
        }

    /**
     * ⚠ Its **own** launcher and its own request, because Android refuses to show the
     * background-location dialog in the same call as foreground location — it must be asked after
     * `ACCESS_FINE_LOCATION` is already held, or it is silently auto-denied.
     */
    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onDestroy() {
        importControl.detach(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ⚠ Guarded on a genuine launch: a rotation re-delivers the same intent.
        if (savedInstanceState == null) intent?.let(::readOpenTarget)
        // ⚠ Keyed by owner: a dying Activity must not clear a newer one's launcher and leave the
        // Import row silently doing nothing (Fit's `AndroidHealthFilePicker` lesson).
        importControl.attach(this) {
            pickTakeout.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/json",
                    "application/octet-stream",
                ),
            )
        }

        setContent {
            TenshuTheme {
                // ⚠⚠ `openTargets` is not optional here, it is the whole receiving half (TT-2b).
                // Both shells read the launch extra and pushed it into the inbox, and then neither
                // handed the inbox to `App` — so every cross-app open into Timeline delivered
                // perfectly and navigated nowhere, which looks exactly like a launch that worked.
                App(openTargets = this@MainActivity.openTargets.targets)
            }
        }

        // React to the opt-in: enable → ensure permissions + start capture; disable → stop it.
        lifecycleScope.launch {
            control.enabled.collect { enabled ->
                if (enabled) {
                    ensurePermissionsThenStart()
                    maybeAskForBatteryExemption()
                } else {
                    launcher.stop()
                }
            }
        }
    }

    /**
     * Nudge the user, **once**, to exempt the app from battery optimisation the first time capture is
     * on and we are not yet exempt. On aggressive OEMs this is what actually keeps background capture
     * alive (and what lets the boot receiver / watchdog restart the service from the background); no
     * app-side code substitutes for it. Guarded by a pref so it is a one-time ask, not a nag on every
     * launch — a user who declines is respected until they toggle capture again on a fresh install.
     */
    private fun maybeAskForBatteryExemption() {
        if (BatteryOptimization.isExempt(this)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_BATTERY, false)) return
        prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        runCatching { startActivity(BatteryOptimization.requestExemptionIntent(this)) }
    }

    private fun ensurePermissionsThenStart() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            maybeAskForBackgroundLocation()
            startIfEnabled()
            return
        }
        showRationaleThenRequest(missing)
    }

    /**
     * Ask, **once**, for "Allow all the time" — which is what lets capture come back after an OEM kill.
     *
     * ⚠⚠ This is not a nice-to-have. Measured on Android 16: a foreground service started while the
     * app is in the background is **denied the location type** unless this is held —
     * `Foreground service started from background can not have location/camera/microphone access` —
     * so the service self-stops and capture stays dead until the user next opens the app. The
     * background *start* itself is already allowed by the battery-optimisation exemption; it is the
     * location type that is refused.
     *
     * ⚠ Asked separately and only once fine location is held: Android auto-denies a background
     * request bundled with the foreground one. ⚠ Guarded by a pref like the battery ask — a refusal
     * must not become a prompt on every launch, and capture still works while the app is open.
     */
    private fun maybeAskForBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_BACKGROUND, false)) return
        prefs.edit().putBoolean(KEY_ASKED_BACKGROUND, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_background_title)
            .setMessage(R.string.capture_background_message)
            .setPositiveButton(R.string.capture_permission_continue) { _, _ ->
                runCatching { requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            }
            .setNegativeButton(R.string.capture_permission_not_now, null)
            .show()
    }

    private fun startIfEnabled() {
        if (control.enabled.value) launcher.start()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The permissions capture needs that we do not have yet.
     *
     * ⚠ **Two different kinds of permission, and the difference is load-bearing.** Fine location is
     * *required* — without it capture records nothing, so a refusal resets the opt-in. Physical
     * activity is *optional* — without it mode classification degrades to the speed-only bands, which
     * still works. So a refusal there must change nothing except which bands run, and it is asked once
     * rather than on every launch.
     *
     * ⚠⚠ **`ACCESS_BACKGROUND_LOCATION` is deliberately not in this list, but it *is* required** — it
     * is asked for separately by [maybeAskForBackgroundLocation], because Android auto-denies a
     * background request bundled with the foreground one. It is not in *this* list because this list
     * gates the opt-in: refusing it must not reset "Capture my timeline", since capture works fine
     * while the app is open.
     *
     * ⚠⚠ **Never drop it from the manifest as "not needed".** It looks unneeded if the states are
     * only *FGS alive* (fine location suffices) and *process dead* (no permission helps). The state it
     * serves is the one after an OEM kill — process alive, backgrounded, FGS restarting — where the
     * service is refused the **location type** without it (manifest comment;
     * `timeline-movement-capture.md` §30).
     */
    private fun missingPermissions(): List<String> = buildList {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // ⚠⚠ This was declared in the manifest and never requested by anything, so on every device
        // it sat at `granted=false` with no `USER_SET` flag — never denied, simply never asked. The
        // cost was silent: `AndroidStepCounter.isAvailable()` returns true (the sensor object exists),
        // `start()` succeeds, and the listener then never fires, so `stepsSinceLast()` answers `null`
        // forever and `LegMode` runs its no-pedometer fallback. Measured effect: a bike ride at
        // 12–13 km/h records as `run`, because `cycle` needs ≥ 4.0 m/s ≈ 14.4 km/h from speed alone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.ACTIVITY_RECOGNITION) &&
            !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ASKED_ACTIVITY, false)
        ) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    /**
     * Explain *before* the system dialogs, naming only what is actually about to be asked for.
     *
     * The system prompts say what the permission is; only we can say what it buys, and for physical
     * activity the honest answer is concrete enough to be worth a sentence ("tell a bike ride from a
     * run"). ⚠ The message is assembled from the outstanding set rather than being one fixed
     * paragraph, so a user who already granted location is not told we need it again.
     */
    private fun showRationaleThenRequest(missing: List<String>) {
        val reasons = buildList {
            if (missing.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(getString(R.string.capture_permission_reason_location))
            }
            if (missing.contains(Manifest.permission.ACTIVITY_RECOGNITION)) {
                add(getString(R.string.capture_permission_reason_activity))
            }
        }
        // Nothing worth a sentence (e.g. only the notification permission is outstanding) — the
        // system prompt speaks for itself, so do not interpose a dialog that says nothing.
        if (reasons.isEmpty()) {
            request(missing)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_permission_title)
            .setMessage(reasons.joinToString("\n\n"))
            .setPositiveButton(R.string.capture_permission_continue) { _, _ -> request(missing) }
            .setNegativeButton(R.string.capture_permission_not_now) { _, _ -> declineRationale() }
            .setOnCancelListener { declineRationale() }
            .show()
    }

    private fun request(missing: List<String>) {
        // Mark the optional ask as spent whether or not it is granted — the point of the guard is to
        // ask once, and "denied" and "dismissed" both mean we have had our turn.
        if (missing.contains(Manifest.permission.ACTIVITY_RECOGNITION)) markActivityAsked()
        requestPermissions.launch(missing.toTypedArray())
    }

    /**
     * The user dismissed the explanation. Location is required, so backing out of it resets the
     * opt-in — the same honesty as a refusal at the system prompt. Physical activity is optional, so
     * backing out of *that* alone leaves capture running with the speed-only bands.
     */
    private fun declineRationale() {
        markActivityAsked()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) startIfEnabled()
        else control.setEnabled(false)
    }

    private fun markActivityAsked() =
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ASKED_ACTIVITY, true).apply()

    private companion object {
        const val PREFS = "tenshu_timeline_capture_ui"
        const val KEY_ASKED_BATTERY = "asked_battery_exemption"
        const val KEY_ASKED_ACTIVITY = "asked_activity_recognition"
        const val KEY_ASKED_BACKGROUND = "asked_background_location"
    }
}
