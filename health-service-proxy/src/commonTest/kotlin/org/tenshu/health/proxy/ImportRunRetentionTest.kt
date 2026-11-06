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
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D8 of `docs/proposals/fit-data-sources-restructure.md` — the import-run retention cap.
 *
 * ⚠⚠ A run log is the one health relation that grows on a *schedule* rather than with the
 * user's data: `HealthImportBootstrap` fires on every foreground. Without a ceiling this
 * `Syncable(Eager)` relation becomes a heartbeat broadcast into the realm.
 */
class ImportRunRetentionTest {

    private val DEVICE = "device-A"
    private val OTHER_DEVICE = "device-B"

    private suspend fun TestProxy.recordRun(
        startedAt: Long,
        kind: String = HealthSource.KIND_HEALTH_CONNECT,
        deviceId: String = DEVICE,
    ): Data? = executionContext.executeAsync(
        operationId = RecordImportRun.ID,
        caller = null,
        params = mapOf(
            RecordImportRun.Params.RUN to HealthImportRun.create(
                context = executionContext.context,
                deviceId = deviceId,
                kind = kind,
                startedAt = startedAt,
                finishedAt = startedAt + 100L,
                window = HealthImportRun.WINDOW_RECENT,
                status = HealthImportRun.STATUS_SUCCEEDED,
                itemCount = 1L,
                workoutCount = 1L,
            )
        )
    )

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
    fun theLogStaysAtTheCapAndKeepsTheNewestRuns() = runTest {
        val p = createHealthProxy(this)

        val over = HealthImportRun.RETENTION_CAP + 12
        repeat(over) { p.recordRun(startedAt = 1_000L + it) }

        val kept = p.runs()
        assertEquals(
            HealthImportRun.RETENTION_CAP,
            kept.size,
            "the log must settle at the cap, not grow with every foreground",
        )

        val oldest = kept.minOf { it.startedAt.get() }
        assertEquals(
            1_000L + (over - HealthImportRun.RETENTION_CAP),
            oldest,
            "the OLDEST runs are the ones dropped — a history that trims the newest is useless",
        )
        assertTrue(
            kept.any { it.startedAt.get() == 1_000L + over - 1 },
            "the run just recorded must survive its own trim",
        )
    }

    /**
     * ⚠ The group is (kind, device), not the whole relation. A device that imports hourly must
     * not be able to evict another device's entire history — and the other device could not
     * restore it, because it converges on the propagated tombstone.
     */
    @Test
    fun oneDevicesRunsNeverEvictAnothers() = runTest {
        val p = createHealthProxy(this)

        repeat(3) { p.recordRun(startedAt = 500L + it, deviceId = OTHER_DEVICE) }
        repeat(HealthImportRun.RETENTION_CAP + 20) { p.recordRun(startedAt = 1_000L + it) }

        val byDevice = p.runs().groupBy { it.deviceId.get() }
        assertEquals(
            3,
            byDevice[OTHER_DEVICE]?.size,
            "a busy device must not trim a quiet one's history",
        )
        assertEquals(HealthImportRun.RETENTION_CAP, byDevice[DEVICE]?.size)
    }

    @Test
    fun takeoutRunsAreCappedSeparatelyFromPlatformRuns() = runTest {
        val p = createHealthProxy(this)

        repeat(4) { p.recordRun(startedAt = 500L + it, kind = HealthImportRun.KIND_TAKEOUT) }
        repeat(HealthImportRun.RETENTION_CAP + 5) { p.recordRun(startedAt = 1_000L + it) }

        val byKind = p.runs().groupBy { it.kind.get() }
        assertEquals(
            4,
            byKind[HealthImportRun.KIND_TAKEOUT]?.size,
            "the batch-import history is its own group — the user's four Takeout imports are " +
                "exactly the rows they would look for, and a busy Health Connect must not take them",
        )
    }

    @Test
    fun aLogUnderTheCapIsLeftAlone() = runTest {
        val p = createHealthProxy(this)

        repeat(5) { p.recordRun(startedAt = 1_000L + it) }

        assertEquals(5, p.runs().size, "nothing is trimmed until the cap is actually exceeded")
    }
}
