package app.tenshu.fit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D10 of `docs/proposals/fit-data-sources-restructure.md` — the rules that can be **quietly
 * wrong** are decided once here rather than twice in Compose and SwiftUI.
 *
 * The load-bearing ones: a live import outranks a stored one *only while running* (§4.1), the
 * live channel never speaks for another device, and "last import" is the newest run whatever its
 * status.
 */
class DataSourceProjectionTest {

    private val THIS_DEVICE = "device-A"
    private val OTHER_DEVICE = "device-B"

    private fun hc(
        deviceId: String = THIS_DEVICE,
        label: String = "Pixel 8",
        autoImport: Boolean = true,
        lastRunAt: Long? = 2_000L,
        lastStatus: String? = DataSourceProjection.STATUS_SUCCEEDED,
        lastItemCount: Long? = 165L,
    ) = HealthSourceInput(
        kind = DataSourceProjection.KIND_HEALTH_CONNECT,
        deviceId = deviceId,
        deviceLabel = label,
        autoImport = autoImport,
        lastRunAt = lastRunAt,
        lastStatus = lastStatus,
        lastItemCount = lastItemCount,
    )

    private fun apple(deviceId: String = OTHER_DEVICE, label: String = "iPhone 15") =
        HealthSourceInput(
            kind = DataSourceProjection.KIND_APPLE_HEALTH,
            deviceId = deviceId,
            deviceLabel = label,
            autoImport = true,
            lastRunAt = 1_000L,
            lastStatus = DataSourceProjection.STATUS_SUCCEEDED,
            lastItemCount = 89L,
        )

    private fun run(
        kind: String = DataSourceProjection.KIND_TAKEOUT,
        deviceId: String = THIS_DEVICE,
        startedAt: Long = 1_000L,
        status: String = DataSourceProjection.STATUS_SUCCEEDED,
        window: String = DataSourceProjection.WINDOW_FILE,
        itemCount: Long = 10L,
        workoutCount: Long = 2L,
        byteCount: Long? = null,
        fileName: String? = null,
    ) = ImportRunInput(
        deviceId = deviceId,
        kind = kind,
        startedAt = startedAt,
        finishedAt = startedAt + 50L,
        window = window,
        status = status,
        itemCount = itemCount,
        workoutCount = workoutCount,
        byteCount = byteCount,
        fileName = fileName,
    )

    // ── The row's state ────────────────────────────────────────────────────────────

    @Test
    fun aSettledSourceReadsItsStoredRunNotTheProcess() {
        val rows = DataSourceProjection.dataSourceRows(listOf(hc()), THIS_DEVICE)
        assertEquals(DataSourceState.Imported(165L, 2_000L), rows.single().state)
    }

    /**
     * ⚠⚠ The regression this whole track exists to prevent. `ImportStatus` is process-scoped, so
     * on the next launch it is `Idle` — and the row must still say 165, from the durable row.
     */
    @Test
    fun anIdleProcessStillShowsTheLastImport() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc()), THIS_DEVICE, liveStatus = ImportStatus.Idle,
        )
        assertEquals(DataSourceState.Imported(165L, 2_000L), rows.single().state)
    }

    @Test
    fun aRunningImportOutranksTheStoredFigure() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc()), THIS_DEVICE, liveStatus = ImportStatus.Running(imported = 12),
        )
        assertEquals(DataSourceState.Running(12), rows.single().state)
    }

    /**
     * ⚠⚠ The subtle half of §4.1. A *completed* live status must NOT win — it says the same thing
     * the stored row says, but only until the process dies. Taking it here would re-introduce the
     * vanishing figure through the back door.
     */
    @Test
    fun aCompletedLiveStatusDoesNotOverrideTheStoredRun() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc(lastItemCount = 165L)),
            THIS_DEVICE,
            liveStatus = ImportStatus.Imported(ImportSource.HealthConnect, itemCount = 3),
        )
        assertEquals(
            DataSourceState.Imported(165L, 2_000L),
            rows.single().state,
            "the durable row is the source of truth for a settled outcome",
        )
    }

    @Test
    fun statesNoStoredRunCanExpressComeFromTheLiveChannel() {
        fun stateWith(status: ImportStatus) = DataSourceProjection
            .dataSourceRows(listOf(hc()), THIS_DEVICE, liveStatus = status).single().state

        assertEquals(
            DataSourceState.PermissionNeeded,
            stateWith(ImportStatus.PermissionsMissing(ImportSource.HealthConnect)),
        )
        assertEquals(DataSourceState.Unavailable, stateWith(ImportStatus.SourceUnavailable))
        assertEquals(DataSourceState.Sample, stateWith(ImportStatus.Sample(4)))
    }

    /**
     * ⚠⚠ The live channel describes **this** device. Letting it colour another device's row
     * would render one phone's progress as though it were the iPhone's — a fabricated claim
     * about hardware the user is not holding.
     */
    @Test
    fun theLiveStatusNeverSpeaksForAnotherDevice() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc(), apple()),
            THIS_DEVICE,
            liveStatus = ImportStatus.Running(imported = 7),
        )
        val byKind = rows.associateBy { it.kind }
        assertEquals(DataSourceState.Running(7), byKind[DataSourceKind.HealthConnect]?.state)
        assertEquals(
            DataSourceState.Imported(89L, 1_000L),
            byKind[DataSourceKind.AppleHealth]?.state,
            "the iPhone's row reads the iPhone's stored run, not this phone's progress",
        )
    }

    @Test
    fun aRegisteredSourceThatNeverRanSaysSoRatherThanImportingNothing() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc(lastRunAt = null, lastStatus = null, lastItemCount = null)), THIS_DEVICE,
        )
        assertEquals(
            DataSourceState.NeverRun,
            rows.single().state,
            "'never imported' and 'imported nothing' are different answers",
        )
    }

    @Test
    fun anUnknownStoredStatusStillReportsWhatItImported() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc(lastStatus = "SOMETHING_A_LATER_BUILD_ADDED")), THIS_DEVICE,
        )
        assertEquals(
            DataSourceState.Imported(165L, 2_000L),
            rows.single().state,
            "a newer peer's status must not erase a count that did happen",
        )
    }

    // ── Rows, devices and ordering ─────────────────────────────────────────────────

    /** The whole point of §0: another device's source is visible here at all. */
    @Test
    fun anotherDevicesSourceIsShownAndLabelled() {
        val rows = DataSourceProjection.dataSourceRows(listOf(hc(), apple()), THIS_DEVICE)

        val remote = rows.single { it.kind == DataSourceKind.AppleHealth }
        assertFalse(remote.isThisDevice)
        assertEquals("iPhone 15", remote.deviceLabel)
        assertFalse(remote.canImportNow, "we cannot make an iPhone read HealthKit from here")

        val local = rows.single { it.kind == DataSourceKind.HealthConnect }
        assertTrue(local.isThisDevice)
        assertNull(local.deviceLabel, "naming the device the user is holding is noise")
        assertTrue(local.canImportNow)
    }

    @Test
    fun thisDeviceSortsFirstSoTheActionableRowIsOnTop() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(apple(), hc()), THIS_DEVICE,
        )
        assertTrue(rows.first().isThisDevice)
    }

    @Test
    fun anUnrecognisedKindIsDroppedRatherThanGuessedAt() {
        val rows = DataSourceProjection.dataSourceRows(
            listOf(hc(), hc().copy(kind = "SOME_FUTURE_STORE", deviceId = "device-C")),
            THIS_DEVICE,
        )
        assertEquals(1, rows.size, "a source we cannot name is a source we cannot describe")
    }

    /**
     * ⚠⚠ The row describes what Fit is **configured to do**, not how much it holds. A count of
     * this source's workouts is not cheaply available and, worse, not reproducible: the rollup
     * has no provenance, the bounded `workouts()` read would shrink as imports arrive, and
     * `FitProjection.source(raw)` maps `Manual` as its `else` branch, so no store-side predicate
     * can match what the UI calls Manual. See `TenshuHealthInput`.
     */
    @Test
    fun tenshuHealthLeadsTheListAndCarriesTheDetectionSetting() {
        assertTrue(
            DataSourceProjection.dataSourceRows(listOf(hc()), THIS_DEVICE)
                .none { it.kind == DataSourceKind.TenshuHealth },
            "an unread setting means no row — claiming a configuration we have not read is the " +
                "failure this whole surface avoids",
        )

        val detecting = DataSourceProjection.dataSourceRows(
            listOf(hc()), THIS_DEVICE, tenshu = TenshuHealthInput(autoDetect = true),
        )
        assertEquals(DataSourceKind.TenshuHealth, detecting.first().kind)
        assertEquals(DataSourceState.Recording(autoDetect = true), detecting.first().state)
        assertFalse(detecting.first().canImportNow, "Fit's own recordings are not an import")

        val off = DataSourceProjection.dataSourceRows(
            listOf(hc()), THIS_DEVICE, tenshu = TenshuHealthInput(autoDetect = false),
        )
        assertEquals(DataSourceState.Recording(autoDetect = false), off.first().state)
    }

    // ── Batch import ───────────────────────────────────────────────────────────────

    @Test
    fun batchImportReadsItsRunsBecauseTakeoutHasNoSourceRow() {
        val summary = DataSourceProjection.batchImportSummary(
            listOf(run(startedAt = 1_000L), run(startedAt = 5_000L)),
            canImportFiles = true,
        )
        assertEquals(5_000L, summary?.lastRunAt)
        assertEquals(ImportRunStatus.Succeeded, summary?.lastStatus)
        assertEquals(2, summary?.runCount)
    }

    /** ⚠ A failed import is still the last thing that happened. */
    @Test
    fun lastImportIsTheNewestRunEvenWhenItFailed() {
        val summary = DataSourceProjection.batchImportSummary(
            listOf(
                run(startedAt = 1_000L, status = DataSourceProjection.STATUS_SUCCEEDED),
                run(startedAt = 9_000L, status = DataSourceProjection.STATUS_FAILED),
            ),
            canImportFiles = true,
        )
        assertEquals(9_000L, summary?.lastRunAt)
        assertEquals(
            ImportRunStatus.Failed,
            summary?.lastStatus,
            "showing the earlier success would tell the user their import worked",
        )
    }

    @Test
    fun theBatchSectionIsAbsentWhereNoFileCanBePicked() {
        assertNull(
            DataSourceProjection.batchImportSummary(listOf(run()), canImportFiles = false),
            "a batch-import row on a device that cannot pick a file is a dead affordance",
        )
    }

    @Test
    fun platformRunsNeverLeakIntoTheBatchSummary() {
        val summary = DataSourceProjection.batchImportSummary(
            listOf(run(kind = DataSourceProjection.KIND_HEALTH_CONNECT, startedAt = 9_000L)),
            canImportFiles = true,
        )
        assertEquals(0, summary?.runCount)
        assertNull(summary?.lastRunAt)
    }

    // ── History ────────────────────────────────────────────────────────────────────

    @Test
    fun historyIsNewestFirstAndCarriesTheFileFigures() {
        val history = DataSourceProjection.importHistory(
            listOf(
                run(startedAt = 1_000L),
                run(startedAt = 5_000L, byteCount = 91_000_000L, fileName = "takeout.zip"),
            ),
            kind = null,
            thisDeviceId = THIS_DEVICE,
        )
        assertEquals(listOf(5_000L, 1_000L), history.map { it.startedAt })
        assertEquals(91_000_000L, history.first().byteCount)
        assertEquals("takeout.zip", history.first().fileName)
        assertEquals(ImportRunWindow.File, history.first().window)
    }

    /** Cross-device history is what makes an iPhone's Apple Health readable on Android. */
    @Test
    fun aSourcesHistorySpansEveryDeviceAndLabelsTheRemoteOnes() {
        val history = DataSourceProjection.importHistory(
            listOf(
                run(kind = DataSourceProjection.KIND_APPLE_HEALTH, deviceId = OTHER_DEVICE, startedAt = 7_000L),
                run(kind = DataSourceProjection.KIND_APPLE_HEALTH, deviceId = THIS_DEVICE, startedAt = 3_000L),
            ),
            kind = DataSourceKind.AppleHealth,
            thisDeviceId = THIS_DEVICE,
            deviceLabels = mapOf(OTHER_DEVICE to "iPhone 15"),
        )
        assertEquals(2, history.size)
        assertEquals("iPhone 15", history.first().deviceLabel)
        assertFalse(history.first().isThisDevice)
        assertNull(history.last().deviceLabel)
    }

    @Test
    fun tenshuHealthHasNoImportHistoryAtAll() {
        assertTrue(
            DataSourceProjection.importHistory(
                listOf(run()), DataSourceKind.TenshuHealth, THIS_DEVICE,
            ).isEmpty(),
            "Fit's own recordings are not imports — an empty history is the truth, not a gap",
        )
    }

    /** ⚠ An unreadable status must not render as a success the user then relies on. */
    @Test
    fun anUnknownRunStatusReadsAsFailedNotSucceeded() {
        assertEquals(ImportRunStatus.Failed, DataSourceProjection.statusOf("WHAT_IS_THIS"))
    }
}
