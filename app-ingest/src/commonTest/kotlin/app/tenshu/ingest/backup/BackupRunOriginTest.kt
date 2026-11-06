package app.tenshu.ingest.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The pure decisions behind `drive-synced-folders-phase2.md` §4.1a: which runs may hold the Android
 * foreground service, what *Back up now* may offer to ignore, and the WorkManager job's policy.
 */
class BackupRunOriginTest {

    private val storage = StorageStatusModel(hasStorage = true, usedBytes = 0, limitBytes = 100)
    private val everyState = listOf(
        BackupState.Disabled,
        BackupState.PausedByUser,
        BackupState.UpToDate,
        BackupState.BackingUp(1, 3, "a.jpg"),
        BackupState.Paused(BackupPauseReason.METERED_NETWORK),
        BackupState.JustFinished(uploaded = 3, failed = 0),
    )

    // ── the foreground service ────────────────────────────────────────────────────────────────

    @Test
    fun noAutomaticStateEverStartsTheForegroundService() {
        // ⚠⚠ Play: "daily auto backup of all new photos" is not a dataSync use.
        everyState.forEach { assertFalse(startsForegroundService(it), "$it started the service") }
    }

    @Test
    fun onlyAUserRunInFlightStartsTheForegroundService() {
        assertTrue(startsForegroundService(BackupState.BackingUp(0, 3, "a.jpg", userInitiated = true)))
    }

    @Test
    fun aRunningServiceReadsAnAutomaticDrainAsIdle_soItStopsInsteadOfCarryingIt() {
        assertEquals(BackupState.UpToDate, foregroundServiceState(BackupState.BackingUp(1, 3, "b.jpg")))
        val userRun = BackupState.BackingUp(1, 3, "b.jpg", userInitiated = true)
        assertEquals(userRun, foregroundServiceState(userRun))
        // The user run's own summary still shows.
        val finished = BackupState.JustFinished(uploaded = 3, failed = 0)
        assertEquals(finished, foregroundServiceState(finished))
    }

    @Test
    fun aggregatingSources_keepsTheUserStamp() {
        val aggregate = aggregateState(
            listOf(
                BackupState.BackingUp(0, 2, "a.jpg", userInitiated = true),
                BackupState.BackingUp(0, 1, "doc.pdf", userInitiated = false),
            ),
        )
        assertTrue(startsForegroundService(aggregate))
    }

    // ── the one-time override ─────────────────────────────────────────────────────────────────

    @Test
    fun ignoring_liftsExactlyWhatItNames() {
        val both = BackupSettings(enabled = true, wifiOnly = true, chargingOnly = true)
        assertEquals(both.copy(wifiOnly = false), both.ignoring(setOf(BackupConstraint.WIFI_ONLY)))
        assertEquals(both.copy(chargingOnly = false), both.ignoring(setOf(BackupConstraint.CHARGING_ONLY)))
        assertEquals(both, both.ignoring(emptySet()))
        // ⚠ Never switches a constraint ON.
        val none = BackupSettings(enabled = true, wifiOnly = false, chargingOnly = false)
        assertEquals(none, none.ignoring(setOf(BackupConstraint.WIFI_ONLY, BackupConstraint.CHARGING_ONLY)))
    }

    @Test
    fun ignoring_neverLiftsStopOrTheMasterSwitch() {
        val stopped = BackupSettings(enabled = true, wifiOnly = true, chargingOnly = true, pausedByUser = true)
        val all = setOf(BackupConstraint.WIFI_ONLY, BackupConstraint.CHARGING_ONLY)
        assertEquals(
            BackupGate.PausedByUser,
            evaluateBackupGate(stopped.ignoring(all), storage, BackupNetworkStatus.METERED, charging = false),
        )
    }

    @Test
    fun blockers_onMobileDataWithWifiOnly() {
        val settings = BackupSettings(enabled = true, wifiOnly = true, chargingOnly = false)
        assertEquals(
            setOf(BackupConstraint.WIFI_ONLY),
            overridableBlockers(settings, storage, BackupNetworkStatus.METERED, charging = true),
        )
    }

    @Test
    fun blockers_unpluggedWithChargingOnly() {
        val settings = BackupSettings(enabled = true, wifiOnly = false, chargingOnly = true)
        assertEquals(
            setOf(BackupConstraint.CHARGING_ONLY),
            overridableBlockers(settings, storage, BackupNetworkStatus.UNMETERED, charging = false),
        )
    }

    @Test
    fun blockers_bothAtOnce_soOneConfirmationCoversTheRun() {
        val settings = BackupSettings(enabled = true, wifiOnly = true, chargingOnly = true)
        assertEquals(
            setOf(BackupConstraint.WIFI_ONLY, BackupConstraint.CHARGING_ONLY),
            overridableBlockers(settings, storage, BackupNetworkStatus.METERED, charging = false),
        )
    }

    @Test
    fun blockers_noneWhenNothingIsInTheWay() {
        val settings = BackupSettings(enabled = true, wifiOnly = true, chargingOnly = true)
        assertEquals(emptySet(), overridableBlockers(settings, storage, BackupNetworkStatus.UNMETERED, charging = true))
    }

    @Test
    fun blockers_noneWhenSomethingTheUserCannotWaiveIsInTheWay() {
        val settings = BackupSettings(enabled = true, wifiOnly = true, chargingOnly = true)
        // No connection: "use mobile data this once?" would offer a choice that changes nothing.
        assertEquals(emptySet(), overridableBlockers(settings, storage, BackupNetworkStatus.NONE, charging = false))
        // No storage bound.
        assertEquals(
            emptySet(),
            overridableBlockers(settings, storage.copy(hasStorage = false), BackupNetworkStatus.METERED, charging = false),
        )
        // Full.
        assertEquals(
            emptySet(),
            overridableBlockers(settings, storage.copy(usedBytes = 100), BackupNetworkStatus.METERED, charging = false),
        )
        // Stopped, or off.
        assertEquals(
            emptySet(),
            overridableBlockers(settings.copy(pausedByUser = true), storage, BackupNetworkStatus.METERED, charging = false),
        )
        assertEquals(
            emptySet(),
            overridableBlockers(settings.copy(enabled = false), storage, BackupNetworkStatus.METERED, charging = false),
        )
    }

    // ── the WorkManager job ───────────────────────────────────────────────────────────────────

    @Test
    fun jobConstraints_followTheUsersSwitches() {
        assertEquals(
            BackupJobConstraints(unmeteredNetwork = true, charging = false, batteryNotLow = true, storageNotLow = true),
            backupJobConstraints(wifiOnly = true, chargingOnly = false),
        )
        assertEquals(
            BackupJobConstraints(unmeteredNetwork = false, charging = true, batteryNotLow = true, storageNotLow = true),
            backupJobConstraints(wifiOnly = false, chargingOnly = true),
        )
    }

    @Test
    fun theJobBudgetYieldsBeforeTheOsTenMinuteLimit() {
        // ⚠ At the limit the OS cancels mid-file; the budget is what keeps the stop at a boundary.
        assertTrue(BACKUP_JOB_BUDGET < 10.minutes)
        assertTrue(BACKUP_JOB_BUDGET >= 5.minutes, "a budget this short wastes most of each job")
    }

    @Test
    fun onlyAStoredFalseStopsTheJobs_unknownRetriesAndCancelsNothing() {
        // ⚠⚠ The defect: a cold start with Control slow or dead read "could not read" as "off" and
        // cancelled the periodic, content and continuation jobs together.
        assertEquals(BackupJobStart.RUN, backupJobStart(true))
        assertEquals(BackupJobStart.STOP_ALL, backupJobStart(false))
        assertEquals(BackupJobStart.RETRY, backupJobStart(null))
    }

    @Test
    fun aYieldedRunContinues_aNotReadyOneRetries_aCompleteOneWaitsForTheNextTrigger() {
        assertEquals(BackupJobFollowUp.CONTINUE, backupJobFollowUp(BackgroundDrainResult.YIELDED))
        assertEquals(BackupJobFollowUp.RETRY, backupJobFollowUp(BackgroundDrainResult.NOT_READY))
        assertEquals(BackupJobFollowUp.DONE, backupJobFollowUp(BackgroundDrainResult.COMPLETE))
    }
}
