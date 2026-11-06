package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.ImportRunStatus
import app.tenshu.fit.model.ImportRunWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DS-3's *Batch import* half, end to end. Lives in `desktopTest` because [TakeoutParser] needs a
 * platform XML reader — `commonTest` has no way to build one, which is also why the batch section
 * is absent on a shell with no parser bound.
 *
 * ⚠ D11 under test at the read layer: a file import produces **runs and no source row**, so the
 * batch section derives entirely from `HealthImportRun(kind = TAKEOUT)` and never appears in the
 * Data sources list.
 */
class BatchImportReadTest {

    private val DEVICE = "device-under-test"

    /**
     * A clock that advances a second per read.
     *
     * ⚠⚠ Not cosmetic. A run's identity is `"$deviceId:$startedAt"`, so two imports that *start
     * in the same millisecond* are the same row and the second folds onto the first — which is
     * the content-addressing working as designed, and unreachable in life (`importLock`
     * serialises imports, and a real one takes far longer than a millisecond). A test importing
     * two **empty** archives back to back reaches it easily, and did: this test failed in a warm
     * full-suite run and passed in isolation. Driving the clock makes the timestamps distinct by
     * construction instead of by luck.
     */
    private class TickingClock(private var ms: Long = 1_700_000_000_000L) : Clock {
        override fun now(): Instant {
            ms += 1_000L
            return Instant.fromEpochMilliseconds(ms)
        }
    }

    private fun repository(scope: CoroutineScope): FitProjectionRepository {
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
            },
            coroutineScope = scope,
            implementationFactoryOverride = { id, p ->
                healthServiceProxyImplementationFactory(id, p.fileDatabase)
            },
        ).also { it.executionContext.initHealthService() }

        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        return FitProjectionRepository(
            client,
            proxy.tContext,
            clock = TickingClock(),
            fileParser = TakeoutParser(ActivityFileParser(xml = JvmXmlReader())),
            resolveDeviceId = { DEVICE },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
    }

    private fun archive(name: String, bytes: Long) = ActivityFileSource.of(
        files = emptyList(),
        archiveName = name,
        archiveBytes = bytes,
    )

    @Test
    fun aFileImportFeedsTheBatchSectionAndNotTheSourcesList() = runTest {
        val repo = repository(this)

        repo.importFiles(archive("takeout-20260801.zip", 91_000_000L))

        val summary = assertNotNull(repo.batchImport().first())
        assertEquals(1, summary.runCount)
        assertEquals(
            ImportRunStatus.NoData,
            summary.lastStatus,
            "an export with nothing usable is a real answer, and the user picked the file",
        )

        assertTrue(
            repo.dataSources().first().none { it.kind != DataSourceKind.TenshuHealth },
            "D11: a file import mints no source row",
        )
    }

    /** D6 — the megabyte figure the trigger asked for, and it only exists on this path. */
    @Test
    fun theBatchHistoryCarriesTheArchiveNameAndSize() = runTest {
        val repo = repository(this)

        repo.importFiles(archive("takeout-20260801.zip", 91_000_000L))

        val entry = repo.importHistory(kind = null).first().single()
        assertEquals(ImportRunWindow.File, entry.window)
        assertEquals("takeout-20260801.zip", entry.fileName)
        assertEquals(91_000_000L, entry.byteCount)
        assertTrue(entry.isThisDevice)
    }

    @Test
    fun theNewestImportIsTheOneTheRowReports() = runTest {
        val repo = repository(this)

        repo.importFiles(archive("older.zip", 1_000L))
        repo.importFiles(archive("newer.zip", 2_000L))

        val summary = assertNotNull(repo.batchImport().first())
        assertEquals(2, summary.runCount)

        val history = repo.importHistory(kind = null).first()
        assertEquals(
            listOf("newer.zip", "older.zip"),
            history.map { it.fileName },
            "newest first — the history reads top-down like every other feed",
        )
    }
}
