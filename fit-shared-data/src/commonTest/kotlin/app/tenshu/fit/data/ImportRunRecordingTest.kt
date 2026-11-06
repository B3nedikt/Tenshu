package app.tenshu.fit.data

import app.tenshu.fit.data.ImportedDailyActivity
import app.tenshu.fit.data.ImportedHealth
import app.tenshu.fit.data.ImportedWorkout
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.HealthSource
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/proposals/fit-data-sources-restructure.md` §2.2 — an import is recorded as a durable,
 * synced fact, not only a `MutableStateFlow` that dies with the process.
 *
 * The two rules under test are the ones with teeth: **D7**, that an empty automatic refresh
 * writes nothing (`HealthImportBootstrap` fires on every foreground, so the alternative is a
 * heartbeat broadcast to every device), and **D4**, that the auto-import switch stops
 * unattended passes without touching manual ones.
 */
class ImportRunRecordingTest {

    private val DEVICE = "device-under-test"

    private class FakeImporter(
        /** `var` so a test can change what the source returns between refreshes. */
        var outcome: ImportOutcome,
        private val source: ImportSource = ImportSource.HealthConnect,
    ) : HealthImporter {
        var imports = 0
            private set

        override fun availableSources() = listOf(source)
        override suspend fun import(
            source: ImportSource,
            categories: Set<CategoryId>,
            window: ImportWindow,
        ): ImportOutcome {
            imports++
            yield()
            return outcome
        }
    }

    private class Harness(val proxy: TestProxy, val repository: FitProjectionRepository) {

        fun runs(): List<HealthImportRun> = relation(
            HealthService.Relations.IMPORT_RUNS,
            HealthImportRun.ID,
        ).filterIsInstance<HealthImportRun>()

        fun sources(): List<HealthSource> = relation(
            HealthService.Relations.SOURCES,
            HealthSource.ID,
        ).filterIsInstance<HealthSource>()

        private fun relation(relationId: String, elementId: String) =
            proxy.executionContext.getArrayData(
                ArrayDatabaseReference(
                    arrayId = DataId.RelationDataId.create(
                        context = proxy.executionContext.context,
                        type = TList.ID,
                        relationId = relationId
                    ),
                    isEntity = false,
                    elementSchemaId = elementId
                )
            )?.toList().orEmpty()
    }

    private fun harness(
        scope: CoroutineScope,
        importer: HealthImporter,
        probeScope: CoroutineScope? = null,
        controlHasRealm: app.tenshu.reachability.RealmProbe = app.tenshu.reachability.RealmProbe.None("tests: no realm probe unless one is passed"),
        resolveDeviceId: suspend () -> String? = { DEVICE },
    ): Harness {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
        }
        val proxy = TestProxy(
            schemaRegistry = registry,
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
        return Harness(
            proxy,
            FitProjectionRepository(
                client,
                proxy.tContext,
                importer = importer,
                resolveDeviceId = resolveDeviceId,
                probeScope = probeScope,
                probeIntervalMs = 50,
                probe = { true },
                controlHasRealm = controlHasRealm,
            ),
        )
    }

    private fun withWorkout(workoutId: String = "w1") = ImportOutcome.Success(
        ImportedHealth(
            workouts = listOf(
                ImportedWorkout(
                    workoutId = workoutId,
                    activityType = "Running",
                    source = "HealthConnect",
                    startMs = 1_000L,
                    endMs = 2_000L,
                    durationSec = 1,
                )
            ),
            dailyActivities = listOf(ImportedDailyActivity(date = "2026-08-13", steps = 4_699)),
        )
    )

    /**
     * ⚠⚠ Pins the gate closing mid-pass: the realm gate starts holding after the import's entry
     * check but before its bookkeeping. Source registration and the run record are synced writes
     * too, and go through the gated run recorder — so neither lands, no health row is written, and
     * the pass counts as held. Paired with the gate staying open: source registered, run recorded.
     */
    @Test
    fun aGateThatHoldsBeforeRegistrationRefusesTheBookkeepingToo() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            for (turnsHeld in listOf(true, false)) {
                val probeScope = CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
                )
                var answer: Boolean? = true
                var flipped = false
                lateinit var h: Harness
                suspend fun awaitVerdict(v: app.tenshu.reachability.RealmVerdict) =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(10_000) {
                            while (h.repository.reach.realmVerdict.value != v) kotlinx.coroutines.delay(10)
                        }
                    }
                try {
                    h = harness(
                        this, FakeImporter(withWorkout("gated-w1")),
                        probeScope = probeScope,
                        controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer },
                        // Resolved on the way to the bookkeeping write: the moment the realm read
                        // lands ABSENT, after the entry check has already let the pass in.
                        resolveDeviceId = {
                            if (turnsHeld && !flipped) {
                                flipped = true
                                // Control drops; its re-read answers "no realm".
                                answer = false
                                h.repository.reach.markUnreachable()
                                awaitVerdict(app.tenshu.reachability.RealmVerdict.ABSENT)
                            }
                            DEVICE
                        },
                    )
                    awaitVerdict(app.tenshu.reachability.RealmVerdict.PRESENT)
                    h.repository.startImport(CategoryId.entries.toSet(), automatic = true)

                    if (turnsHeld) {
                        assertTrue(h.sources().isEmpty(), "source registration refused")
                        assertTrue(h.runs().isEmpty(), "no run record either")
                        assertEquals(1L, h.repository.realmHeldStops(), "the pass counts as held")
                    } else {
                        assertEquals(1, h.sources().size)
                        assertEquals(1, h.runs().size)
                        assertEquals(0L, h.repository.realmHeldStops())
                    }
                } finally {
                    probeScope.cancel()
                }
            }
        }

    @Test
    fun animportThatFoundSomethingIsRecordedAndFoldsOntoTheSource() = runTest {
        val h = harness(this, FakeImporter(withWorkout()))

        h.repository.startImport(setOf(CategoryId.Activity))

        val run = h.runs().single()
        assertEquals(HealthSource.KIND_HEALTH_CONNECT, run.kind.get())
        assertEquals(DEVICE, run.deviceId.get())
        assertEquals(HealthImportRun.STATUS_SUCCEEDED, run.status.get())
        assertEquals(2L, run.itemCount.get(), "a workout plus a daily activity")
        assertEquals(1L, run.workoutCount.get(), "workouts are counted separately — users read that one")
        assertNull(run.byteCount?.get(), "megabytes are a file-import figure, never a platform one")

        val source = h.sources().single()
        assertEquals(2L, source.lastItemCount?.get(), "the list reads the source row, not the runs")
        assertEquals(HealthImportRun.STATUS_SUCCEEDED, source.lastStatus?.get())
        assertTrue(source.autoImport.get(), "a newly registered source imports automatically")
    }

    /**
     * ⚠⚠ Pins that `itemCount` is what the store reported as **new**, not what was *read*. A
     * platform importer re-returns its whole window on every refresh, so counting the read would
     * write an identical run row on every foreground; counting new rows makes an unchanged
     * re-read zero, and D7's rule bites.
     */
    @Test
    fun aSecondIdenticalImportRecordsNothing() = runTest {
        val h = harness(this, FakeImporter(withWorkout()))

        h.repository.startImport(setOf(CategoryId.Activity))
        assertEquals(1, h.runs().size, "the first import really did add rows")
        assertEquals(2L, h.runs().single().itemCount.get())

        // Same payload, same keys — the store already holds every row.
        h.repository.startImport(setOf(CategoryId.Activity), automatic = true)

        assertEquals(
            1,
            h.runs().size,
            "a re-read of an unchanged window adds nothing, so it is not a run worth recording",
        )
    }

    /** …but a genuinely new row still lands, and counts as one rather than as the window. */
    @Test
    fun anImportThatAddsOneRowRecordsOne() = runTest {
        val importer = FakeImporter(withWorkout())
        val h = harness(this, importer)

        h.repository.startImport(setOf(CategoryId.Activity))
        h.repository.startImport(setOf(CategoryId.Activity))
        assertEquals(1, h.runs().size, "precondition: the repeat added nothing")

        importer.outcome = withWorkout(workoutId = "w2")
        h.repository.startImport(setOf(CategoryId.Activity))

        assertEquals(2, h.runs().size)
        val newest = h.runs().maxByOrNull { it.startedAt.get() }
        assertEquals(1L, newest?.itemCount?.get(), "one new workout, not the whole window")
    }

    /**
     * ⚠⚠ **D7, and the whole reason it exists.** This is the every-foreground case. Recording it
     * would put a row in a `Syncable(Eager)` relation on a schedule rather than on the user's
     * data — and the source row would still be registered, which is the part that *should*
     * happen.
     */
    @Test
    fun anEmptyAutomaticRefreshRecordsNoRun() = runTest {
        val h = harness(this, FakeImporter(ImportOutcome.Success(ImportedHealth())))

        repeat(5) { h.repository.startImport(setOf(CategoryId.Activity), automatic = true) }

        assertTrue(h.runs().isEmpty(), "an empty RECENT refresh must write nothing at all")
        assertEquals(1, h.sources().size, "but the source is still registered — that is not a run")
    }

    /**
     * The mirror of the case above, and what stops the rule from being "never record an empty
     * import": the user deliberately asked for their full history, and *"we looked, and there was
     * nothing"* is the answer they came for.
     */
    @Test
    fun anEmptyFullHistoryImportIsRecordedBecauseTheUserAskedForIt() = runTest {
        val h = harness(this, FakeImporter(ImportOutcome.Success(ImportedHealth())))

        h.repository.startImport(setOf(CategoryId.Activity), ImportWindow.FullHistory)

        val run = h.runs().single()
        assertEquals(HealthImportRun.WINDOW_FULL_HISTORY, run.window.get())
        assertEquals(HealthImportRun.STATUS_NO_DATA, run.status.get())
        assertEquals(0L, run.itemCount.get())
    }

    @Test
    fun missingPermissionsAreRecordedBecauseAFailureIsInformation() = runTest {
        val h = harness(this, FakeImporter(ImportOutcome.PermissionsRequired(setOf("steps"))))

        h.repository.startImport(setOf(CategoryId.Activity), automatic = true)

        assertEquals(
            HealthImportRun.STATUS_PERMISSIONS_MISSING,
            h.runs().single().status.get(),
        )
    }

    /**
     * ⚠ Sample data is fixture data, not the user's. Recording it would put a fabricated figure
     * in a durable, synced log; the live status still says "Sample data, not yours".
     */
    @Test
    fun sampleDataIsNeverRecordedAsAnImport() = runTest {
        val h = harness(this, FakeImporter(ImportOutcome.Sample(withWorkout().health)))

        h.repository.startImport(setOf(CategoryId.Activity))

        assertTrue(h.runs().isEmpty(), "a fixture must not become a durable claim about the user")
    }

    /**
     * ⚠⚠ **D4.** The switch stops *unattended* passes. It must not stop the user pressing
     * "Import now" — switching off automatic passes is not the same as switching off the source,
     * and the detail screen offers both.
     */
    @Test
    fun theAutoImportSwitchStopsAutomaticPassesOnly() = runTest {
        val importer = FakeImporter(withWorkout())
        val h = harness(this, importer)

        // Register the source, then switch its automatic passes off.
        h.repository.startImport(setOf(CategoryId.Activity))
        assertEquals(1, importer.imports)
        HealthImportRunRecorder(
            client = h.repository.let { _ ->
                DefaultTenshuClient(
                    TenshuEnvironment(
                        taskExecutor = h.proxy.localTaskExecutor,
                        schemaRegistry = h.proxy.schemaRegistry,
                        tContext = h.proxy.tContext,
                        requestChainFactory = { ctx ->
                            LocalRequestChain(h.proxy.localTaskExecutor, ctx)
                        },
                    ),
                )
            },
            context = h.proxy.tContext,
            resolveDeviceId = { DEVICE },
            beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE,
        ).setAutoImport(HealthSource.KIND_HEALTH_CONNECT, DEVICE, enabled = false)

        assertFalse(h.sources().single().autoImport.get(), "precondition: the user switched it off")

        h.repository.startImport(setOf(CategoryId.Activity), automatic = true)
        assertEquals(1, importer.imports, "an automatic pass must not run once it is switched off")

        h.repository.startImport(setOf(CategoryId.Activity))
        assertEquals(2, importer.imports, "but a manual import still works")
    }

    @Test
    fun theNoOpRuleMatchesTheEmptyAutomaticCaseExactly() {
        // NO_DATA on RECENT is the only silent combination — the one an empty foreground
        // refresh produces. Everything else is worth a row.
        assertFalse(
            HealthImportRunRecorder.worthRecording(
                HealthImportRun.WINDOW_RECENT, HealthImportRun.STATUS_NO_DATA, 0L,
            )
        )
        assertTrue(
            HealthImportRunRecorder.worthRecording(
                HealthImportRun.WINDOW_RECENT, HealthImportRun.STATUS_SUCCEEDED, 1L,
            )
        )
        assertTrue(
            HealthImportRunRecorder.worthRecording(
                HealthImportRun.WINDOW_RECENT, HealthImportRun.STATUS_FAILED, 0L,
            )
        )
        assertTrue(
            HealthImportRunRecorder.worthRecording(
                HealthImportRun.WINDOW_FULL_HISTORY, HealthImportRun.STATUS_NO_DATA, 0L,
            )
        )
        assertTrue(
            HealthImportRunRecorder.worthRecording(
                HealthImportRun.WINDOW_FILE, HealthImportRun.STATUS_NO_DATA, 0L,
            )
        )
    }
}
