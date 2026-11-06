package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.RecordImportRun
import org.tenshu.core.health.RegisterHealthSource
import org.tenshu.core.health.SetSourceAutoImport
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DS-1 of `docs/proposals/fit-data-sources-restructure.md` — the Data sources registry.
 *
 * The two behaviours worth pinning are the ones a "simplification" would quietly break:
 * a re-register must **preserve** the user's `autoImport` (it runs on every launch), and
 * recording a run must fold its figures onto the owning source so the list stays one read.
 */
class HealthDataSourceRegistryTest {

    private val DEVICE = "device-A"
    private val OTHER_DEVICE = "device-B"

    private suspend fun TestProxy.register(
        kind: String = HealthSource.KIND_HEALTH_CONNECT,
        deviceId: String = DEVICE,
        deviceLabel: String = "Pixel 8",
    ): Data? = executionContext.executeAsync(
        operationId = RegisterHealthSource.ID,
        caller = null,
        params = mapOf(
            RegisterHealthSource.Params.KIND to kind.toTString(executionContext.context),
            RegisterHealthSource.Params.DEVICE_ID to deviceId.toTString(executionContext.context),
            RegisterHealthSource.Params.DEVICE_LABEL to deviceLabel.toTString(executionContext.context),
        )
    )

    private suspend fun TestProxy.setAutoImport(
        autoImport: Boolean,
        kind: String = HealthSource.KIND_HEALTH_CONNECT,
        deviceId: String = DEVICE,
    ): Data? = executionContext.executeAsync(
        operationId = SetSourceAutoImport.ID,
        caller = null,
        params = mapOf(
            SetSourceAutoImport.Params.KIND to kind.toTString(executionContext.context),
            SetSourceAutoImport.Params.DEVICE_ID to deviceId.toTString(executionContext.context),
            SetSourceAutoImport.Params.AUTO_IMPORT to autoImport.toTBoolean(executionContext.context),
        )
    )

    private suspend fun TestProxy.recordRun(
        kind: String = HealthSource.KIND_HEALTH_CONNECT,
        deviceId: String = DEVICE,
        startedAt: Long = 1_000L,
        finishedAt: Long = 2_000L,
        window: String = HealthImportRun.WINDOW_RECENT,
        status: String = HealthImportRun.STATUS_SUCCEEDED,
        itemCount: Long = 165L,
        workoutCount: Long = 12L,
        byteCount: Long? = null,
        fileName: String? = null,
    ): Data? = executionContext.executeAsync(
        operationId = RecordImportRun.ID,
        caller = null,
        params = mapOf(
            RecordImportRun.Params.RUN to HealthImportRun.create(
                context = executionContext.context,
                deviceId = deviceId,
                kind = kind,
                startedAt = startedAt,
                finishedAt = finishedAt,
                window = window,
                status = status,
                itemCount = itemCount,
                workoutCount = workoutCount,
                byteCount = byteCount,
                fileName = fileName,
            )
        )
    )

    private fun TestProxy.source(
        kind: String = HealthSource.KIND_HEALTH_CONNECT,
        deviceId: String = DEVICE,
    ): HealthSource? {
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = HealthSource.ID,
            keyValue = HealthSource.keyOf(kind, deviceId)
        )
        return executionContext.database.getData(dataId) as? HealthSource
    }

    private fun TestProxy.sources(): List<HealthSource> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = HealthService.Relations.SOURCES
            ),
            isEntity = false,
            elementSchemaId = HealthSource.ID
        )
        return executionContext.getArrayData(ref)?.toList()?.filterIsInstance<HealthSource>()
            ?: emptyList()
    }

    private fun TestProxy.runs(): List<HealthImportRun> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = executionContext.context,
                type = TList.ID,
                relationId = HealthService.Relations.IMPORT_RUNS
            ),
            isEntity = false,
            elementSchemaId = HealthImportRun.ID
        )
        return executionContext.getArrayData(ref)?.toList()?.filterIsInstance<HealthImportRun>()
            ?: emptyList()
    }

    @Test
    fun registerCreatesAnEnabledRowAndAddsItToTheRelation() = runTest {
        val p = createHealthProxy(this)

        p.register()

        val row = p.source()
        assertTrue(row != null, "register must persist a row")
        assertEquals(HealthSource.KIND_HEALTH_CONNECT, row.kind.get())
        assertEquals(DEVICE, row.deviceId.get())
        assertEquals("Pixel 8", row.deviceLabel.get())
        assertTrue(row.autoImport.get(), "a newly registered source imports automatically")
        assertNull(row.lastRunAt?.get(), "a source that has never run has no last run")

        assertEquals(1, p.sources().size, "the maintainer adds the source to the relation")
    }

    /**
     * ⚠⚠ The regression this exists for. `registerHealthSource` runs on **every launch** where
     * the importer reports itself available; if it re-defaulted `autoImport`, a source the user
     * switched off would silently switch itself back on — and because the flag is synced, it
     * would do so from the other side of the realm.
     */
    @Test
    fun reRegisteringPreservesAutoImportAndRefreshesTheLabel() = runTest {
        val p = createHealthProxy(this)

        p.register(deviceLabel = "Pixel 8")
        p.setAutoImport(false)
        assertFalse(p.source()!!.autoImport.get(), "precondition: the user turned it off")

        p.register(deviceLabel = "Benedikt's Pixel")

        val row = p.source()!!
        assertFalse(row.autoImport.get(), "a re-register must NOT re-enable a disabled source")
        assertEquals("Benedikt's Pixel", row.deviceLabel.get(), "the descriptor does refresh")
        assertEquals(1, p.sources().size, "re-registering folds to one row")
    }

    @Test
    fun reRegisteringPreservesTheDenormalisedRunFigures() = runTest {
        val p = createHealthProxy(this)

        p.register()
        p.recordRun(itemCount = 165L, finishedAt = 2_000L)

        p.register(deviceLabel = "Renamed")

        val row = p.source()!!
        assertEquals(2_000L, row.lastRunAt?.get(), "a re-register must not clear the run figures")
        assertEquals(165L, row.lastItemCount?.get())
        assertEquals(HealthImportRun.STATUS_SUCCEEDED, row.lastStatus?.get())
    }

    @Test
    fun recordingARunFoldsItsFiguresOntoTheOwningSource() = runTest {
        val p = createHealthProxy(this)

        p.register()
        p.recordRun(startedAt = 1_000L, finishedAt = 2_000L, itemCount = 165L, workoutCount = 12L)

        val row = p.source()!!
        assertEquals(2_000L, row.lastRunAt?.get(), "the list reads lastRunAt, not the run rows")
        assertEquals(HealthImportRun.STATUS_SUCCEEDED, row.lastStatus?.get())
        assertEquals(165L, row.lastItemCount?.get())

        val runs = p.runs()
        assertEquals(1, runs.size)
        assertEquals(12L, runs.single().workoutCount.get())
    }

    @Test
    fun aLaterRunOverwritesTheDenormalisedFigures() = runTest {
        val p = createHealthProxy(this)

        p.register()
        p.recordRun(startedAt = 1_000L, finishedAt = 2_000L, itemCount = 165L)
        p.recordRun(startedAt = 3_000L, finishedAt = 4_000L, itemCount = 7L, status = HealthImportRun.STATUS_FAILED)

        val row = p.source()!!
        assertEquals(4_000L, row.lastRunAt?.get())
        assertEquals(HealthImportRun.STATUS_FAILED, row.lastStatus?.get(), "a failure is the last word too")
        assertEquals(2, p.runs().size, "both runs are kept — this is a history, not a status field")
    }

    /**
     * D11: an import from a file is an act, not a standing connection, so a Takeout run updates
     * no source row and — critically — does not mint one. The batch surface reads these runs.
     */
    @Test
    fun aTakeoutRunIsRecordedButMintsNoSourceRow() = runTest {
        val p = createHealthProxy(this)

        p.recordRun(
            kind = HealthImportRun.KIND_TAKEOUT,
            window = HealthImportRun.WINDOW_FILE,
            itemCount = 4_012L,
            workoutCount = 806L,
            byteCount = 91_000_000L,
            fileName = "takeout-20260801.zip",
        )

        assertEquals(0, p.sources().size, "Takeout is not a standing connection")

        val run = p.runs().single()
        assertEquals(HealthImportRun.KIND_TAKEOUT, run.kind.get())
        assertEquals(91_000_000L, run.byteCount?.get(), "megabytes are a file-import figure")
        assertEquals("takeout-20260801.zip", run.fileName?.get())
    }

    /** Recording the fact must not depend on the descriptor existing. */
    @Test
    fun aRunWithNoRegisteredSourceIsStillRecorded() = runTest {
        val p = createHealthProxy(this)

        p.recordRun(kind = HealthSource.KIND_APPLE_HEALTH, deviceId = OTHER_DEVICE)

        assertEquals(1, p.runs().size)
        assertNull(p.source(HealthSource.KIND_APPLE_HEALTH, OTHER_DEVICE), "no row is invented")
    }

    @Test
    fun setAutoImportOnAnUnknownSourceIsANoOp() = runTest {
        val p = createHealthProxy(this)

        p.setAutoImport(autoImport = false, deviceId = OTHER_DEVICE)

        assertEquals(0, p.sources().size, "a kill switch must not conjure the thing it kills")
    }

    /**
     * The point of the whole type (§0): sources are per-device rows that **sync**, so one
     * device's registry carries another device's Apple Health connection. Locally derived
     * sources could not represent this at all.
     */
    @Test
    fun sourcesFromDifferentDevicesCoexist() = runTest {
        val p = createHealthProxy(this)

        p.register(kind = HealthSource.KIND_HEALTH_CONNECT, deviceId = DEVICE, deviceLabel = "Pixel 8")
        p.register(kind = HealthSource.KIND_APPLE_HEALTH, deviceId = OTHER_DEVICE, deviceLabel = "iPhone 15")

        val all = p.sources().associateBy { it.key.get() }
        assertEquals(2, all.size)
        assertEquals(
            "iPhone 15",
            all[HealthSource.keyOf(HealthSource.KIND_APPLE_HEALTH, OTHER_DEVICE)]?.deviceLabel?.get()
        )

        p.setAutoImport(autoImport = false, kind = HealthSource.KIND_APPLE_HEALTH, deviceId = OTHER_DEVICE)

        assertFalse(
            p.source(HealthSource.KIND_APPLE_HEALTH, OTHER_DEVICE)!!.autoImport.get(),
            "D4: the flag is ours, so another device's import can be switched off from here"
        )
        assertTrue(
            p.source()!!.autoImport.get(),
            "and switching one off must not touch the other"
        )
    }
}
