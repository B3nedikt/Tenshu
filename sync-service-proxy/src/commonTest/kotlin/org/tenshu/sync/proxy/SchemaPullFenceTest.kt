package org.tenshu.sync.proxy

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.service
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SchemaFenceStatus
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.fencedServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.common.module.reference as registerReference
import org.tenshu.core.common.module.service as registerService

/*
 * One synthetic single-service module (`SchemaRegistry.owningServiceOf` attributes by
 * "the service registered in the same ModuleConfiguration"), so the device declares a
 * KNOWN service at a known version and the epoch row can be seeded above / at / below it:
 *
 *   FENCE_SERVICE_ID at version 3, type `FenceThing` (Syncable, keyed on its name).
 *
 * UNKNOWN_SERVICE_ID is deliberately never registered — the "epoch names a service this
 * device does not have" case.
 */

private const val FENCE_SERVICE_ID = "test/fence/service"
private const val UNKNOWN_SERVICE_ID = "test/fence/unknownService"
private const val FENCE_SERVICE_VERSION = 3

private const val THING_ID = "test/fence/thing"
private const val THING_NAME = "$THING_ID/name"
private val THING_DATA_ID = stringDataId("$THING_ID/dataIdProvider", THING_NAME)

private val THING_SCHEMA: CompositeSchema = composite(
    id = THING_ID,
    traits = listOf(Syncable(), THING_DATA_ID),
    fields = { string(THING_NAME) },
)

private class ThingDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = THING_SCHEMA
}

private val fenceTestModule = tModule {
    composite(interfaceClass = ThingDto::class, dataSchema = THING_SCHEMA, dtoFactory = ::ThingDto)
    registerReference(reference(THING_NAME))
    trait(THING_SCHEMA, THING_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    registerService(service(id = FENCE_SERVICE_ID, version = FENCE_SERVICE_VERSION))
}

/**
 * Seed a realm's epoch row directly — `database.saveData` fires no observer, so no emit.
 * [realmId] `null` = the personal realm; a shared realm's row is namespace-keyed.
 */
private fun TestProxy.seedEpoch(
    services: Map<String, Int>,
    coreVersion: Int = TENSHU_STORAGE_FORMAT_VERSION,
    realmId: String? = null,
) {
    executionContext.database.saveData(
        RealmSchemaEpoch.create(
            context = tContext,
            epochId = RealmRegistry.epochKeyFor(realmId),
            versions = VersionVector.create(
                context = tContext,
                coreVersion = coreVersion,
                services = services.entries.sortedBy { it.key }.map { (id, version) ->
                    ServiceVersion.create(tContext, id, version)
                },
            ),
        ),
    )
}

private fun TestProxy.fenceStatus(): SchemaFenceStatus? = executionContext.database.getData(
    DataId.StringDataId.create(
        context = tContext,
        type = SchemaFenceStatus.ID,
        keyValue = SchemaFenceStatus.SINGLETON_KEY,
    ),
) as? SchemaFenceStatus

private fun thing(context: TContext, name: String): Data =
    context.create(type = THING_ID, fields = listOf(name.toTString(context)))

/**
 * The realm schema **pull fence** verdict itself (`core-schema-evolution.md` §7.3,
 * E2d-2), exercised directly on [SchemaPullFence] so each rule is isolated from the
 * scheduler's other gates. The scheduler-level consequences (pull skipped, push still
 * drains, cursor frozen) are [SchemaPullFenceSchedulerTest].
 */
class SchemaPullFenceTest {

    private fun proxy(): TestProxy =
        TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
            schemaRegistry.registerConfig(syncServiceCommonModule)
            schemaRegistry.registerConfig(fenceTestModule)
        }

    @Test
    fun noEpochRow_notFenced() {
        val proxy = proxy()
        val fence = SchemaPullFence(proxy.executionContext)

        assertFalse(
            fence.evaluate(),
            "no epoch row ⇒ nobody has written at a recorded version ⇒ nothing to fence on",
        )
        val status = assertNotNull(proxy.fenceStatus(), "the verdict is published either way")
        assertFalse(status.fenced.get())
        assertEquals(emptyList(), status.fencedServices())
    }

    @Test
    fun epochAheadForKnownService_fencesAndNamesIt() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        val fence = SchemaPullFence(proxy.executionContext)

        assertTrue(fence.evaluate(), "the realm has emitted a version this device cannot parse")
        val status = assertNotNull(proxy.fenceStatus())
        assertTrue(status.fenced.get())
        assertEquals(
            listOf(FENCE_SERVICE_ID),
            status.fencedServices(),
            "the offending service is named so a banner can say WHICH app to update",
        )
    }

    @Test
    fun epochEqualToThisDevice_notFenced() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION))
        val fence = SchemaPullFence(proxy.executionContext)

        assertFalse(fence.evaluate(), "equal is current, not behind — the fence is exclusive")
        assertFalse(assertNotNull(proxy.fenceStatus()).fenced.get())
    }

    @Test
    fun epochBehindThisDevice_notFenced() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION - 1))
        val fence = SchemaPullFence(proxy.executionContext)

        assertFalse(
            fence.evaluate(),
            "new code reads old formats (§3's asymmetric contract) — being AHEAD never fences",
        )
        assertFalse(assertNotNull(proxy.fenceStatus()).fenced.get())
    }

    /**
     * §7.3 as scoped in [SchemaPullFence]: an epoch entry for a service this runtime has
     * no schemas for cannot garble anything here (nothing can materialize its types), so
     * fencing on it would halt *all* sync — including rows this device does read — over
     * data it was never going to touch.
     */
    @Test
    fun epochAheadForUnknownService_notFenced() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(UNKNOWN_SERVICE_ID to 99))
        val fence = SchemaPullFence(proxy.executionContext)

        assertFalse(
            fence.evaluate(),
            "an unregistered service's types can never be materialized here, so they cannot " +
                "garble a read — do not fence on them",
        )
        assertFalse(assertNotNull(proxy.fenceStatus()).fenced.get())
    }

    /**
     * ⚠ Core skew fences (§7.11). The relay's 426 gate does not judge payload/core versions
     * at all (§7.8), so without this an out-of-date device is fenced by nothing: it syncs
     * "successfully" while silently dropping every block.
     */
    @Test
    fun coreOnlyEpochAhead_fences() {
        val proxy = proxy()
        proxy.seedEpoch(
            services = emptyMap(),
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION + 5,
        )
        val fence = SchemaPullFence(proxy.executionContext)

        assertTrue(
            fence.evaluate(),
            "the realm holds blocks written under a newer store/wire floor than this build has",
        )
        val status = assertNotNull(proxy.fenceStatus())
        assertTrue(status.fenced.get())
        assertEquals(
            listOf(SchemaFenceStatus.CORE_SERVICE_ID),
            status.fencedServices(),
            "core has no service id of its own, so it rides the reserved one",
        )
    }

    @Test
    fun coreAheadFencesEvenWhenEveryServiceIsCurrent() {
        val proxy = proxy()
        proxy.seedEpoch(
            services = mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION),
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION + 5,
        )

        assertTrue(
            SchemaPullFence(proxy.executionContext).evaluate(),
            "core is the global store/wire floor — a rise means the shared primitives moved, " +
                "which no per-service entry can describe",
        )
    }

    @Test
    fun coreEqualOrBehind_doesNotFence() {
        val proxy = proxy()
        proxy.seedEpoch(services = emptyMap(), coreVersion = TENSHU_STORAGE_FORMAT_VERSION)
        assertFalse(
            SchemaPullFence(proxy.executionContext).evaluate(),
            "equal is current — the fence is exclusive, exactly as it is per service",
        )

        val older = proxy()
        older.seedEpoch(services = emptyMap(), coreVersion = TENSHU_STORAGE_FORMAT_VERSION - 1)
        assertFalse(
            SchemaPullFence(older.executionContext).evaluate(),
            "new code reads old formats (§3's asymmetric contract) — being AHEAD never fences",
        )
    }

    /**
     * The status row is not `Syncable`, so a rewrite emits no block — but at the 1.5 s hot
     * cadence a per-tick upsert would still be pointless store churn plus a stream
     * notification to every observing client. Write-on-change only.
     */
    @Test
    fun statusIsWrittenOnlyOnTransition() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        val fence = SchemaPullFence(proxy.executionContext)

        fence.evaluate()
        assertEquals(1, fence.statusWrites, "the first evaluation publishes the verdict")

        repeat(5) { fence.evaluate() }
        assertEquals(
            1,
            fence.statusWrites,
            "an unchanged verdict must not rewrite the row on every cycle",
        )

        // The realm's epoch is unchanged but this device is no longer behind it (the shape
        // of "the user updated the app"): a real transition, so exactly one more write.
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION))
        fence.evaluate()
        assertEquals(2, fence.statusWrites, "a changed verdict publishes once")
        fence.evaluate()
        assertEquals(2, fence.statusWrites, "and then stops again")
        assertFalse(assertNotNull(proxy.fenceStatus()).fenced.get())
    }

    // --- per-realm fencing (shared realms) ---

    /** A shared realm registered on this device, by ownerId. */
    private fun sharedFence(proxy: TestProxy, vararg realmIds: String) =
        SchemaPullFence(proxy.executionContext, sharedRealmIds = { realmIds.toList() })

    /**
     * The gap this closes. A shared realm whose owner writes at a version this device
     * lacks fences **that realm only** — the personal pull is untouched, because the
     * personal realm's own epoch says nothing is wrong with it.
     */
    @Test
    fun sharedRealmAhead_fencesThatRealmOnly() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION))
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1), realmId = "shared-1")
        val fence = sharedFence(proxy, "shared-1")

        assertFalse(fence.evaluate(), "the personal realm is current, so its pull must keep running")
        assertTrue(fence.isFenced("shared-1"), "the shared realm's own epoch is ahead of this device")
    }

    /**
     * The converse false positive, which is why the fence was scoped to the personal
     * realm before per-realm keys existed: another realm's skew must never halt an
     * invited member's collaboration in a realm that is perfectly readable.
     */
    @Test
    fun personalAhead_doesNotFenceASharedRealm() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION), realmId = "shared-1")
        val fence = sharedFence(proxy, "shared-1")

        assertTrue(fence.evaluate(), "the personal realm IS behind")
        assertFalse(fence.isFenced("shared-1"), "but this shared realm is current — keep pulling it")
    }

    @Test
    fun sharedRealmsAreJudgedIndependentlyOfEachOther() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1), realmId = "shared-ahead")
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION), realmId = "shared-current")
        val fence = sharedFence(proxy, "shared-ahead", "shared-current")

        fence.evaluate()
        assertTrue(fence.isFenced("shared-ahead"))
        assertFalse(fence.isFenced("shared-current"), "one stale folder must not stall the others")
        assertFalse(fence.isFenced("never-registered"), "an unknown realm is never fenced")
    }

    /**
     * The published signal is the **aggregate**: it answers "does this device need
     * updating", which is true even when only a shared folder is affected — that folder
     * has silently stopped updating, and without this the banner would never appear.
     */
    @Test
    fun statusAggregatesAcrossRealms_soASharedOnlyFenceStillBanners() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1), realmId = "shared-1")
        val fence = sharedFence(proxy, "shared-1")

        assertFalse(fence.evaluate(), "the personal pull is not fenced…")
        val status = assertNotNull(proxy.fenceStatus())
        assertTrue(status.fenced.get(), "…but the DEVICE still needs updating")
        assertEquals(listOf(FENCE_SERVICE_ID), status.fencedServices())
    }

    @Test
    fun aggregateStatusDeduplicatesServicesAcrossRealms() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 2), realmId = "shared-1")
        val fence = sharedFence(proxy, "shared-1")

        fence.evaluate()
        assertEquals(
            listOf(FENCE_SERVICE_ID),
            assertNotNull(proxy.fenceStatus()).fencedServices(),
            "the same service behind in two realms is one thing to update, not two",
        )
    }

    /**
     * The verdict reaches Control's own surfaces through the change callback, and it
     * fires exactly once per **transition** — the cadence the tray notification, the Android
     * notification and the desktop log all rely on to alert once per engagement.
     */
    @Test
    fun verdictCallbackFiresOncePerTransition() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        val seen = mutableListOf<List<String>>()
        val fence = SchemaPullFence(proxy.executionContext, onVerdictChanged = { seen += it })

        repeat(3) { fence.evaluate() }
        assertEquals(listOf(listOf(FENCE_SERVICE_ID)), seen, "engaged once, not once per tick")

        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION))
        repeat(3) { fence.evaluate() }
        assertEquals(
            listOf(listOf(FENCE_SERVICE_ID), emptyList()),
            seen,
            "clearing is a transition too, reported as an empty list",
        )
    }

    /** The production sink: the holder starts unknown and carries the last verdict. */
    @Test
    fun holderStartsUnknownAndCarriesTheVerdict() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        val holder = SyncHealthHolder()
        assertNull(
            holder.observeSchemaFence().value,
            "before the first evaluation the fence is unknown, never clear (skew-surface §7 Q3)",
        )

        SchemaPullFence(proxy.executionContext, onVerdictChanged = holder::onSchemaFence).evaluate()
        assertEquals(listOf(FENCE_SERVICE_ID), holder.observeSchemaFence().value)
    }

    /** No shared realms registered ⇒ byte-identical to the personal-only fence. */
    @Test
    fun withNoSharedRealms_behaviourIsUnchanged() {
        val proxy = proxy()
        proxy.seedEpoch(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        val fence = SchemaPullFence(proxy.executionContext)

        assertTrue(fence.evaluate())
        assertFalse(fence.isFenced("anything"), "nothing was evaluated, so nothing is fenced")
        assertEquals(listOf(FENCE_SERVICE_ID), assertNotNull(proxy.fenceStatus()).fencedServices())
    }

    @Test
    fun corruptEpochBytes_areTreatedAsAbsent() {
        val proxy = proxy()
        // The epoch's shape is frozen, so unreadable bytes mean a corrupted row, not a
        // skew — the same "treat as absent" rule `RealmSchemaEpochWriter` applies, so a
        // corrupt row can neither fence the device nor block its own overwrite.
        proxy.executionContext.database.saveData(
            RealmSchemaEpoch.create(
                context = proxy.tContext,
                versionsBytes = byteArrayOf(9, 9, 9, 9),
            ),
        )

        assertFalse(
            SchemaPullFence(proxy.executionContext).evaluate(),
            "a corrupt epoch row must not fence the device",
        )
    }
}


/**
 * The fence's scheduler-level consequences (`core-schema-evolution.md` §7.3): **pull
 * skipped, push allowed, cursor frozen**. Driven through the real `initSyncService`
 * wiring with mocked relay ops — the `SyncSchedulerGateTest` harness. `TestProxy` is
 * given the [TestScope] so the proxy's machinery lands on virtual time, and the polling
 * loops are never started at all (`startBackgroundLoops = false`) — a `== 0` claim needs
 * the loop genuinely inert, and "started, then stopped" is not that
 * (`SyncHealthReportingTest.fixture`).
 */
class SchemaPullFenceSchedulerTest {

    /** The cursor a successful pull would advance to, so "unchanged" is a real assertion. */
    private val relayCursor = 42L

    private class Fixture(
        val proxy: TestProxy,
        val scheduler: SyncScheduler,
        val syncObserver: SyncObserver,
        val syncManager: SyncManagerImpl,
        val healthHolder: SyncHealthHolder,
        val pushCount: () -> Int,
        val pullCount: () -> Int,
    )

    /**
     * Seeds the epoch row **before** `initSyncService`, so no tick can observe a
     * pre-seed state, and builds the components with the loops un-started.
     */
    private suspend fun TestScope.fixture(
        epochServices: Map<String, Int>? = null,
        epochCore: Int = TENSHU_STORAGE_FORMAT_VERSION,
    ): Fixture {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this,
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(fenceTestModule)
        if (epochServices != null) proxy.seedEpoch(epochServices, epochCore)

        val pushes = intArrayOf(0)
        val pulls = intArrayOf(0)
        proxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                pushes[0]++
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
        }
        proxy.additionalImplementations[GetChanges.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                pulls[0]++
                @Suppress("UNCHECKED_CAST")
                return ChangesResponse.create(
                    context = executionContext.context,
                    blocks = emptyList(),
                    peerTips = emptyList(),
                    nextCursor = relayCursor,
                ) as T
            }
        }
        proxy.additionalImplementations[GetTombstones.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                @Suppress("UNCHECKED_CAST")
                return TombstonesResponse.create(
                    context = executionContext.context,
                    tombstones = emptyList(),
                    nextCursor = relayCursor,
                ) as T
            }
        }

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")
        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            syncGate = SyncGate { true },
            storageGate = SyncGate { true },
            // Deterministic: only our explicit ticks run, because the loops are never started —
            // not started and then stopped (`SyncHealthReportingTest.fixture`). Hygiene here: the
            // startup tick is inert.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Fixture(
            proxy,
            components.scheduler,
            components.syncObserver,
            syncManager,
            components.syncHealthHolder,
            pushCount = { pushes[0] },
            pullCount = { pulls[0] },
        )
    }

    /** A Syncable write of the KNOWN service's type → one pending outbound block. */
    private suspend fun Fixture.enqueueOutboundBlock() {
        proxy.executionContext.saveData(thing(proxy.tContext, "t1"))
        syncObserver.awaitEmitIdle()
        syncObserver.awaitEmitIdle() // the epoch row's own re-entrant save, if any
    }

    @Test
    fun fencedRealm_skipsPull_butStillPushes_andLeavesTheCursorAlone() = runTest {
        val f = fixture(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        try {
            f.enqueueOutboundBlock()

            f.scheduler.tickOnce()

            assertEquals(0, f.pullCount(), "a fenced realm must not fetch changes at all")
            assertTrue(
                f.pushCount() >= 1,
                "pull-fenced, PUSH-ALLOWED (§7.3): the old device's pending writes still go " +
                    "out — upgraded peers upcast them, so nothing is stranded",
            )
            assertEquals(
                0L,
                f.syncManager.lastSyncTimestamp,
                "a fenced pull must never look 'caught up' — the skipped blocks have to still " +
                    "be waiting once this device updates",
            )
            val status = assertNotNull(f.proxy.fenceStatus(), "the tick publishes the signal")
            assertTrue(status.fenced.get())
            assertEquals(listOf(FENCE_SERVICE_ID), status.fencedServices())
            // The production scheduler forwards the verdict to the health holder, which is
            // what Control's tray, OS notification and desktop log read. Pinned at this level
            // because a wiring that forgot the callback would leave every one of them silent.
            assertEquals(listOf(FENCE_SERVICE_ID), f.healthHolder.observeSchemaFence().value)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun currentRealm_pullsAndAdvancesTheCursor_withAClearStatus() = runTest {
        val f = fixture(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION))
        try {
            f.enqueueOutboundBlock()

            f.scheduler.tickOnce()

            assertEquals(1, f.pullCount(), "an unfenced realm pulls as before")
            assertEquals(
                relayCursor,
                f.syncManager.lastSyncTimestamp,
                "and advances the cursor — which is what makes the fenced case's 0 meaningful",
            )
            val status = assertNotNull(f.proxy.fenceStatus())
            assertFalse(status.fenced.get())
            assertEquals(emptyList(), status.fencedServices())
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun epochBehindThisDevice_pullsAsBefore() = runTest {
        val f = fixture(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION - 1))
        try {
            f.scheduler.tickOnce()
            assertEquals(1, f.pullCount(), "being ahead of the realm never fences (§3)")
            assertEquals(relayCursor, f.syncManager.lastSyncTimestamp)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun unknownServiceAheadInTheEpoch_stillPulls() = runTest {
        val f = fixture(mapOf(UNKNOWN_SERVICE_ID to 99))
        try {
            f.scheduler.tickOnce()

            assertEquals(
                1,
                f.pullCount(),
                "a service this device doesn't know cannot garble its reads — do not stop syncing",
            )
            assertEquals(relayCursor, f.syncManager.lastSyncTimestamp)
            assertFalse(assertNotNull(f.proxy.fenceStatus()).fenced.get())
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * ⚠ Core skew skips the pull (§7.11). Pulling, a device behind on
     * `TENSHU_STORAGE_FORMAT_VERSION` drops every block on the floor
     * (`decryptAndParse → null`), looking healthy the whole time.
     */
    @Test
    fun coreOnlyEpochAhead_skipsThePullAndFreezesTheCursor() = runTest {
        val f = fixture(
            epochServices = emptyMap(),
            epochCore = TENSHU_STORAGE_FORMAT_VERSION + 5,
        )
        try {
            f.scheduler.tickOnce()

            assertEquals(
                0,
                f.pullCount(),
                "blocks written under a newer store/wire floor would be dropped, not read — " +
                    "so do not fetch them and do not look caught up",
            )
            assertEquals(0L, f.syncManager.lastSyncTimestamp, "cursor frozen, as for any fence")
            assertTrue(assertNotNull(f.proxy.fenceStatus()).fenced.get())
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * The entity shape too, not only bare composites: `SyntheticTestData` rows are what
     * the other scheduler tests enqueue, and a fenced tick must drain them just the same.
     */
    @Test
    fun fencedRealm_drainsAnEntityBacklog() = runTest {
        val f = fixture(mapOf(FENCE_SERVICE_ID to FENCE_SERVICE_VERSION + 1))
        try {
            f.proxy.executionContext.saveData(
                Entity.create<SyntheticTestData>(
                    context = f.proxy.tContext,
                    composite = SyntheticTestData.Factory.create(
                        context = f.proxy.tContext,
                        name = "sync.txt",
                        value = 100L,
                    ),
                    properties = emptyMap(),
                ),
            )
            f.syncObserver.awaitEmitIdle()
            f.syncObserver.awaitEmitIdle()

            f.scheduler.tickOnce()

            assertTrue(f.pushCount() >= 1, "the fence closes the read side only")
            assertEquals(0, f.pullCount())
        } finally {
            f.scheduler.stop()
        }
    }
}
