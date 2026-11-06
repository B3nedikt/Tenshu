package org.tenshu.ingest.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.IngestService
import org.tenshu.core.ingest.RegisterSource
import org.tenshu.core.ingest.SetSourceEnabled
import org.tenshu.core.ingest.UpdateSourceStatus
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.ingest.test.createIngestProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the `:ingest-service` registry (Synced Folders Phase 2a-i).
 * In-process via `executeAsync` + direct DB / relation reads — the same altitude
 * as `ScheduleServiceTest` / `ProxyNotificationServiceUnitTest`. Cross-process
 * wire coverage (serialize→parse over a real transport) is a follow-up system
 * test, mirroring how `:notification-service` gets it from
 * `NotificationSyncSystemTest`.
 */
class IngestServiceTest {

    private val APP = "app.tenshu.drive"
    private val GALLERY = "drive.gallery"
    private val DEVICE = "device-A"

    private suspend fun TestProxy.register(
        sourceId: String = GALLERY,
        deviceId: String = DEVICE,
        displayName: String = "Photos & Videos",
        watchedPaths: String = "",
    ): Data? = executionContext.executeAsync(
        operationId = RegisterSource.ID,
        caller = null,
        params = mapOf(
            RegisterSource.Params.SOURCE_ID to sourceId.toTString(executionContext.context),
            RegisterSource.Params.APP_ID to APP.toTString(executionContext.context),
            RegisterSource.Params.DEVICE_ID to deviceId.toTString(executionContext.context),
            RegisterSource.Params.DISPLAY_NAME to displayName.toTString(executionContext.context),
            RegisterSource.Params.SOURCE_KIND to IngestSource.KIND_GALLERY.toTString(executionContext.context),
            RegisterSource.Params.WATCHED_PATHS to watchedPaths.toTString(executionContext.context),
        )
    )

    private suspend fun TestProxy.setEnabled(
        enabled: Boolean,
        sourceId: String = GALLERY,
        deviceId: String = DEVICE,
    ): Data? = executionContext.executeAsync(
        operationId = SetSourceEnabled.ID,
        caller = null,
        params = mapOf(
            SetSourceEnabled.Params.SOURCE_ID to sourceId.toTString(executionContext.context),
            SetSourceEnabled.Params.DEVICE_ID to deviceId.toTString(executionContext.context),
            SetSourceEnabled.Params.ENABLED to enabled.toTBoolean(executionContext.context),
        )
    )

    private suspend fun TestProxy.updateStatus(
        status: String,
        pendingCount: Long = 0,
        sourceId: String = GALLERY,
        deviceId: String = DEVICE,
    ): Data? = executionContext.executeAsync(
        operationId = UpdateSourceStatus.ID,
        caller = null,
        params = mapOf(
            UpdateSourceStatus.Params.SOURCE_ID to sourceId.toTString(executionContext.context),
            UpdateSourceStatus.Params.DEVICE_ID to deviceId.toTString(executionContext.context),
            UpdateSourceStatus.Params.STATUS to status.toTString(executionContext.context),
            UpdateSourceStatus.Params.PENDING_COUNT to pendingCount.toTInteger(executionContext.context),
            UpdateSourceStatus.Params.LAST_RUN_AT to 0L.toTInteger(executionContext.context),
            UpdateSourceStatus.Params.LAST_ERROR to "".toTString(executionContext.context),
        )
    )

    private fun TestProxy.row(sourceId: String = GALLERY, deviceId: String = DEVICE): IngestSource? {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = IngestSource.ID,
            keyValue = IngestSource.keyOf(sourceId, deviceId)
        )
        return executionContext.database.getData(dataId) as? IngestSource
    }

    private fun TestProxy.relationSources(): List<IngestSource> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = IngestService.Relations.SOURCES
            ),
            isEntity = false,
            elementSchemaId = IngestSource.ID
        )
        return executionContext.getArrayData(ref)?.toList()?.filterIsInstance<IngestSource>() ?: emptyList()
    }

    @Test
    fun register_createsRow_withDefaults_andAddsToRelation() = runTest {
        val p = createIngestProxy(this)

        p.register(displayName = "Photos & Videos")

        val row = p.row()
        assertTrue(row != null, "register must persist a row")
        assertEquals(GALLERY, row.sourceId.get())
        assertEquals(APP, row.appId.get())
        assertEquals(DEVICE, row.deviceId.get())
        assertEquals("Photos & Videos", row.displayName.get())
        assertEquals(IngestSource.KIND_GALLERY, row.sourceKind.get())
        assertTrue(row.enabled.get(), "a new source defaults enabled")
        assertEquals(IngestSource.STATUS_IDLE, row.lastStatus?.get(), "a new source defaults to IDLE")

        val relation = p.relationSources()
        assertEquals(1, relation.size, "the maintainer adds the source to the relation")
        assertEquals(IngestSource.keyOf(GALLERY, DEVICE), relation.single().key.get())
    }

    @Test
    fun setSourceEnabled_flips_preservingDescriptor() = runTest {
        val p = createIngestProxy(this)
        p.register(displayName = "Photos & Videos")

        p.setEnabled(false)

        val row = p.row()
        assertTrue(row != null)
        assertFalse(row.enabled.get(), "kill switch flips enabled")
        assertEquals("Photos & Videos", row.displayName.get(), "descriptor preserved")
        assertEquals(1, p.relationSources().size, "still a single relation entry")
    }

    @Test
    fun reRegister_preservesKillSwitch_butRefreshesDescriptor() = runTest {
        val p = createIngestProxy(this)
        p.register(displayName = "Photos")
        p.setEnabled(false)

        // App re-registers on its next launch with an updated label.
        p.register(displayName = "Photos & Videos")

        val row = p.row()
        assertTrue(row != null)
        assertFalse(row.enabled.get(), "re-register must NOT clobber a central kill-switch toggle")
        assertEquals("Photos & Videos", row.displayName.get(), "re-register refreshes the descriptor")
        assertEquals(1, p.relationSources().size, "upsert stays a single row")
    }

    @Test
    fun updateSourceStatus_writesStatus_preservingEnabledAndDescriptor() = runTest {
        val p = createIngestProxy(this)
        p.register(displayName = "Photos")
        p.setEnabled(false)

        p.updateStatus(status = IngestSource.STATUS_UP_TO_DATE, pendingCount = 3)

        val row = p.row()
        assertTrue(row != null)
        assertEquals(IngestSource.STATUS_UP_TO_DATE, row.lastStatus?.get())
        assertEquals(3L, row.pendingCount?.get())
        assertFalse(row.enabled.get(), "status write preserves the kill switch")
        assertEquals("Photos", row.displayName.get(), "status write preserves the descriptor")
    }

    @Test
    fun statusAndEnable_onUnknownSource_areNoOps() = runTest {
        val p = createIngestProxy(this)

        p.updateStatus(status = IngestSource.STATUS_UP_TO_DATE)
        p.setEnabled(true)

        assertNull(p.row(), "no descriptor → no row created by status/enable")
        assertTrue(p.relationSources().isEmpty())
    }

    // ---- The device-local run signal (drive-upload-progress.md §14.1) ----

    @Test
    fun aReportedRun_isTrackedUntilItReportsAnythingElse() = runTest {
        val runs = IngestRunRegistry()
        val p = createIngestProxy(this, runs = runs)
        p.register()

        assertFalse(runs.anyRunning, "nothing is running before a status is reported")

        p.updateStatus(status = IngestSource.STATUS_RUNNING, pendingCount = 400)
        assertEquals(setOf(IngestSource.keyOf(GALLERY, DEVICE)), runs.runningSources.value)

        // Any non-RUNNING status ends it — including a pause, which must hand the story back
        // rather than silence Control's notification for as long as the user is on cellular.
        p.updateStatus(status = IngestSource.STATUS_PAUSED_POLICY)
        assertFalse(runs.anyRunning, "a paused drain is not a run in flight")

        p.updateStatus(status = IngestSource.STATUS_RUNNING, pendingCount = 12)
        p.updateStatus(status = IngestSource.STATUS_UP_TO_DATE)
        assertFalse(runs.anyRunning, "a finished drain releases the signal")
    }

    @Test
    fun runsAreTrackedPerSource_soOneFinishingDoesNotClearAnother() = runTest {
        val runs = IngestRunRegistry()
        val p = createIngestProxy(this, runs = runs)
        p.register(sourceId = GALLERY)
        p.register(sourceId = "drive.documentTree")

        p.updateStatus(sourceId = GALLERY, status = IngestSource.STATUS_RUNNING)
        p.updateStatus(sourceId = "drive.documentTree", status = IngestSource.STATUS_RUNNING)
        p.updateStatus(sourceId = GALLERY, status = IngestSource.STATUS_UP_TO_DATE)

        assertEquals(
            setOf(IngestSource.keyOf("drive.documentTree", DEVICE)),
            runs.runningSources.value,
            "the gallery finishing must not clear the document-tree run still in flight",
        )
    }

    @Test
    fun statusOnAnUnknownSource_doesNotRegisterARun() = runTest {
        // The op is a no-op without a descriptor (above), and the signal must agree with the write:
        // claiming a run nothing recorded would silence the notification for good.
        val runs = IngestRunRegistry()
        val p = createIngestProxy(this, runs = runs)

        p.updateStatus(status = IngestSource.STATUS_RUNNING)

        assertFalse(runs.anyRunning, "no descriptor → no write → no run")
    }

    @Test
    fun sourcesAreDeviceScoped_sameSourceId_onTwoDevices_areDistinctRows() = runTest {
        val p = createIngestProxy(this)

        p.register(deviceId = "device-A")
        p.register(deviceId = "device-B")

        val relation = p.relationSources()
        assertEquals(2, relation.size, "same sourceId on two devices → two distinct keyed rows")
        assertEquals(
            setOf(IngestSource.keyOf(GALLERY, "device-A"), IngestSource.keyOf(GALLERY, "device-B")),
            relation.map { it.key.get() }.toSet()
        )
    }
}
