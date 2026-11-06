package app.tenshu.fit.android

import org.tenshu.core.common.app.OpenTargetIntents
import org.tenshu.core.common.app.OpenTargetInbox
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import app.tenshu.fit.data.ActivityFile
import app.tenshu.fit.data.ActivityFileSource
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthConnectImporter
import app.tenshu.fit.data.HealthImportBootstrap
import app.tenshu.fit.data.JvmArchiveReader
import app.tenshu.fit.data.TakeoutParser
import app.tenshu.fit.android.recorder.RecordingService
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.ui.App
import app.tenshu.fit.ui.screens.record.RecordSurfaceRequests
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.android.inject

// Shares the tag the :fit-shared-data import chain logs under, so `adb logcat -s TenshuFit`
// shows the whole story — permission gate, Health Connect reads, and the schema write — in order.
private const val TAG = "TenshuFit"

// Fit Android entry point (docs/proposals/fit-app.md). ComponentActivity provides
// ViewModelStore / SavedState / Lifecycle owners for free, so the shell is just
// TenshuTheme { App(openTargets = this@MainActivity.openTargets.targets) }.
//
// P5.2: on launch, if Health Connect is available we ensure the read permissions and pull real
// data through the import seam (repository.startImport → HealthConnectImporter → HealthImportWriter).
// The grant uses the platform's Health Connect permission contract; on grant we re-import.
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

    private val healthImporter: HealthConnectImporter by inject()
    private val repository: FitRepository by inject()
    private val permissionRequester: AndroidHealthPermissionRequester by inject()
    private val filePicker: AndroidHealthFilePicker by inject()
    private val takeoutParser: TakeoutParser by inject()
    private val importBootstrap: HealthImportBootstrap by inject()

    // The app-scoped scope (SupervisorJob + Default), not lifecycleScope, for the Takeout import: it
    // writes ~13,500 records over minutes, and tying it to the Activity would cancel it on a rotation
    // or a tab switch. This survives Activity teardown within the process (it does not survive the
    // process being killed — a foreground service would be the next step if that becomes a problem).
    private val appScope: CoroutineScope by inject()

    /**
     * The Google Takeout import (§9.6b) — the only route by which real Google Fit *history* reaches
     * the app, because Google Fit does not feed Health Connect. `OpenDocument` rather than
     * `GetContent`: it gives a stable, re-readable URI and lets the user reach Drive/Downloads, which
     * is where a Takeout export actually lands.
     */
    private val pickHealthFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) Log.i(TAG, "file import: cancelled") else importPickedFile(uri)
        }

    /** Whether a permission prompt has already been shown this session — see [onPermissionResult]. */
    private var alreadyPrompted = false

    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            onPermissionResult(HealthConnectImporter.PERMISSIONS - granted)
        }

    /**
     * The optional deep-history grant (§15). A **separate** contract from
     * [requestHealthPermissions]: it is asked later, on demand, and its refusal must not be
     * mistaken for the required set being missing.
     *
     * On the answer — whichever way it went — refresh the repository's verdict and, if it was
     * granted, immediately run the full-history import the user asked for. Nothing awaits the
     * sheet: the result lands here, out of band.
     */
    private val requestHistoryPermission =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val allowed = HealthConnectImporter.HISTORY_PERMISSION in granted
            Log.i(TAG, "full-history grant: ${if (allowed) "GRANTED" else "declined"}")
            lifecycleScope.launch {
                repository.refreshHistoryAccess()
                // Import either way, and say which window — the onboarding step hands off to this
                // sheet *instead of* starting its own read, so a refusal that imported nothing
                // would leave the user staring at a progress screen that never moves.
                importFromHealthConnect(if (allowed) ImportWindow.FullHistory else ImportWindow.Recent)
            }
        }

    // The recorder's runtime permissions are requested **just-in-time** from the Record screen
    // (§9.8: `rememberRecorderPermissionGate`), not at launch — so location/BLE are only asked for
    // when the user actually goes to record.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ⚠ Guarded on a genuine launch: a rotation re-delivers the same intent.
        if (savedInstanceState == null) intent?.let(::readOpenTarget)
        // Lend the permission launcher to the DI-bound requester, so the import-status banner's
        // "Try again" can actually re-prompt rather than re-running an import that cannot succeed.
        permissionRequester.attach(
            owner = this,
            launch = { promptForHealthPermissions() },
            launchFullHistory = { promptForFullHistory() },
            // Not a prompt: the grant the user needs here belongs to *Google Fit*, and no app can
            // request another's permissions. All we can do is take them to the screen that holds
            // the switch (fit-app.md §15.5).
            openSettings = { openHealthConnectSettings() },
        )
        // Any MIME: a Takeout export is application/zip, but providers label it inconsistently
        // (octet-stream from some file managers), and a filter that misses it hides the file.
        filePicker.attach(this) { pickHealthFile.launch(arrayOf("*/*")) }
        setContent {
            TenshuTheme {
                App()
            }
        }
        ensureHealthConnect()
        maybeOpenRecording(intent)
    }

    /**
     * Refresh from Health Connect whenever the app comes to the foreground.
     *
     * ⚠⚠ Not redundant with the bootstrap's own launch pass, which fires from `startKoin` and is
     * therefore once per *process* — and a Fit process outlives the app being swiped away by days
     * (2026-08-13: up 13 h 26 m, so a ride recorded that morning was never imported and History's
     * newest row was the previous evening's). [HealthImportBootstrap.refresh] debounces, so the
     * cold-start pair and a rotation's re-`onStart` collapse into one pass.
     */
    override fun onStart() {
        super.onStart()
        importBootstrap.refresh(reason = "foreground")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeOpenRecording(intent)
        // ⚠⚠ Control mints a launch ticket whenever the handler must be brought forward —
        // **including when it is already running** (`control-cross-app-open.md` §5e.1) — so the
        // common case is a launch that RESUMES this activity and never reaches `onCreate`.
        readOpenTarget(intent)
    }

    /**
     * The recording notification's tap (RC-1, fit-app.md §13.1): raise the deep-link request; the
     * shared shell routes to the live or review screen once (if) a session is in flight.
     */
    private fun maybeOpenRecording(intent: Intent?) {
        if (intent?.getBooleanExtra(RecordingService.EXTRA_OPEN_RECORDING, false) == true) {
            RecordSurfaceRequests.requestOpen()
        }
    }

    override fun onDestroy() {
        permissionRequester.detach(this)
        filePicker.detach(this)
        super.onDestroy()
    }

    /**
     * Health Connect's prompt is one-shot-ish: after the user dismisses it twice it resolves
     * immediately with nothing granted and no dialog. Re-asking then is a button that does nothing,
     * so once we have already prompted, send the user to the Health Connect settings screen — the
     * only place the grant can still be given — instead of firing a prompt that will not appear.
     */
    private fun promptForHealthPermissions() {
        if (alreadyPrompted) {
            Log.i(TAG, "permissions: already prompted this session → opening Health Connect settings")
            openHealthConnectSettings()
            return
        }
        alreadyPrompted = true
        // REQUESTED, not PERMISSIONS: the optional route grant rides the same sheet, while the
        // verdict below still turns on the required set alone (a declined route must not read as
        // "permissions missing").
        requestHealthPermissions.launch(HealthConnectImporter.REQUESTED_PERMISSIONS)
    }

    /**
     * Asks for the deep-history grant. Unlike [promptForHealthPermissions] this is **not** guarded
     * by `alreadyPrompted`: it is a deliberate, user-initiated action from Profile or onboarding
     * rather than a launch-time gate, so each tap should reach the sheet. Health Connect still
     * applies its own dont-ask-again behaviour, and a silent refusal simply leaves
     * `historyAccess` at `Grantable` — the entry stays offered, which is the honest state.
     */
    private fun promptForFullHistory() {
        runCatching { requestHistoryPermission.launch(setOf(HealthConnectImporter.HISTORY_PERMISSION)) }
            .onFailure { Log.w(TAG, "full-history grant: could not launch the permission sheet", it) }
    }

    private fun openHealthConnectSettings() {
        val opened = runCatching {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        }.isSuccess
        if (!opened) Log.w(TAG, "permissions: could not open the Health Connect settings screen")
    }

    private fun onPermissionResult(missing: Set<String>) {
        if (missing.isNotEmpty()) {
            Log.w(TAG, "permission grant: ${missing.size} still missing: ${missing.map { it.substringAfterLast('.') }.sorted()}")
        }
        // Import either way. On a refusal the importer returns PermissionsRequired, which the
        // repository turns into a banner the user can act on — where bailing out here (as this did)
        // left importStatus at Idle, so a denied grant showed an empty app and no reason why.
        importFromHealthConnect()
    }

    /**
     * Prompt for the read permissions if they are missing. It deliberately does **not** import on the
     * already-granted path: the repository's own first-run import covers that, and firing a second
     * one here raced it — two concurrent imports writing the same rows, with the loser's stale
     * outcome free to overwrite the winner's status.
     */
    private fun ensureHealthConnect() {
        Log.i(TAG, "ensureHealthConnect: sdkStatus=${healthImporter.statusDescription()}")
        if (!healthImporter.isHealthConnectAvailable()) {
            Log.w(TAG, "ensureHealthConnect: Health Connect unavailable → no live import on this device")
            return
        }
        lifecycleScope.launch {
            // Keep the deep-history verdict current from launch, so Profile and onboarding can
            // offer (or correctly hide) the full-history entry without each having to probe.
            repository.refreshHistoryAccess()
            if (healthImporter.hasAllPermissions()) {
                Log.i(TAG, "ensureHealthConnect: permissions in place — the first-run import covers it")
            } else {
                Log.i(TAG, "ensureHealthConnect: requesting Health Connect permissions")
                promptForHealthPermissions()
            }
        }
    }

    private fun importFromHealthConnect(window: ImportWindow = ImportWindow.Recent) {
        lifecycleScope.launch {
            // startImport reaches Health Connect and then the health-service write path; either can
            // throw. Uncaught here it would take the process down (or, in a non-debug build, just
            // disappear) — so catch, log, and leave the app usable.
            val startedAt = System.currentTimeMillis()
            Log.i(TAG, "import: starting (window=$window)")
            runCatching { repository.startImport(CategoryId.entries.toSet(), window) }
                .onSuccess { Log.i(TAG, "import: finished in ${System.currentTimeMillis() - startedAt} ms") }
                .onFailure { Log.e(TAG, "import: FAILED after ${System.currentTimeMillis() - startedAt} ms", it) }
        }
    }

    /**
     * Hands the picked document to the repository as a **streaming** source: for a Takeout `.zip`,
     * the archive is read one member at a time (each opened on [Dispatchers.IO], parsed + written,
     * then dropped), so a multi-gigabyte export is never held in memory whole. The repository parses,
     * persists, and reports progress + outcome through `importStatus` like any other import.
     */
    private fun importPickedFile(uri: Uri) {
        val name = displayName(uri)
        // Read through the application context's resolver, not the Activity's: the import runs on the
        // app scope and may outlive this Activity, and the SAF read grant is held by the process.
        val resolver = applicationContext.contentResolver
        appScope.launch {
            val startedAt = System.currentTimeMillis()
            Log.i(TAG, "file import: starting ($name)")

            val source = if (name.endsWith(".zip", ignoreCase = true)) {
                // openStream is a factory the archive reader calls to walk the zip front-to-back;
                // each `openInputStream` is a fresh, independently-closed stream.
                JvmArchiveReader.source(
                    openStream = { resolver.openInputStream(uri) ?: error("could not open $name") },
                    isRelevant = takeoutParser::isRelevant,
                    archiveName = name,
                    archiveBytes = sizeOf(uri),
                )
            } else {
                // A bare GPX/TCX/FIT the user exported by hand — small, read once.
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri).use { it?.readBytes() ?: error("could not open $name") }
                }
                ActivityFileSource.of(listOf(ActivityFile(name = name, bytes = bytes)))
            }

            runCatching { withContext(Dispatchers.IO) { repository.importFiles(source) } }
                .onSuccess { Log.i(TAG, "file import: finished in ${System.currentTimeMillis() - startedAt} ms") }
                .onFailure { Log.e(TAG, "file import: FAILED", it) }
        }
    }

    /** The document's user-visible name — the extension is how we tell an archive from an activity. */
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val at = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (at >= 0) cursor.getString(at)?.let { return it }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    /**
     * The document's size, for the batch-import record (DS-2 / D6). One column away from
     * [displayName] and never asked for before. Null when the provider does not report it —
     * ⚠ a run with no size beats one with a guessed size.
     */
    private fun sizeOf(uri: Uri): Long? {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val at = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (at >= 0 && !cursor.isNull(at)) return cursor.getLong(at)
            }
        }
        return null
    }
}
