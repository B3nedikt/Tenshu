package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.generateId
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.ChangesResponse
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.SyncRelay
import org.tenshu.core.core.TContext
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.TombstonesResponse
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ForgetThisDeviceImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SR-M.1b-pull isolation: each realm's inbound stream is decrypted under *its own*
 * keys by *its own* [SyncProcessor], advancing *its own* cursor. The leak-relevant
 * claim on the read side is the mirror of the push slice's: a block encrypted under
 * a shared realm's key must decrypt + apply only when pulled through that realm's
 * processor, and must be dropped (undecryptable) when it reaches the personal
 * processor. Both halves are asserted against the same shared-key-encrypted block.
 */
class MultiRealmPullIsolationTest {

    private val sharedKeys = SyncCryptoManager.DerivedKeys(
        sharedIdentityKey = ByteArray(32) { 0x33 },
        sharedVolumeKey = ByteArray(32) { 0x44 },
    )

    @Test
    fun pullSharedRealms_appliesSharedStreamUnderSharedKeys_personalCursorUntouched() = runTest {
        val fixture = fixture()
        val sharedDataId = fixture.sharedEntityDataId

        // Sanity: the shared entity isn't in the DB yet.
        assertNull(
            fixture.proxy.executionContext.database.getData(sharedDataId),
            "precondition: shared entity not yet ingested",
        )

        // The shared realm's relay holds one block encrypted under the SHARED key.
        val sharedRelay = MockSyncRelay(fixture.proxy.tContext)
        sharedRelay.pushBlock(fixture.sharedBlock)

        // Register the shared realm WITH its relay — the registry's processor
        // factory builds an inbound processor keyed to `sharedKeys`.
        fixture.components.realmRegistry.register(fixture.sharedRealm, sharedRelay)

        // One full cycle: personal pull (empty stubs) then the shared pull.
        fixture.components.scheduler.tickOnce()

        // The shared block decrypted under the shared keys and applied.
        val applied = fixture.proxy.executionContext.database.getData(sharedDataId) as? SyntheticTestData
        assertNotNull(applied, "shared-realm block must apply when pulled under its own keys")
        assertEquals("shared.txt", applied.name.get())
        assertEquals(7L, applied.value.get())

        // The shared realm's own cursor advanced (relay arrival seq = 1)…
        val runtime = fixture.components.realmRegistry.sharedRuntimesWithRelay().single()
        assertTrue(runtime.cursor > 0, "shared realm's pull cursor advanced")
        // …while the personal cursor stayed put — shared traffic never bumps it.
        assertEquals(0L, fixture.syncManager.lastSyncTimestamp, "personal cursor untouched by shared pull")
    }

    @Test
    fun personalPullCannotDecryptSharedRealmBlock() = runTest {
        val fixture = fixture()
        val sharedDataId = fixture.sharedEntityDataId

        // Feed the SAME shared-key-encrypted block through the PERSONAL stream (the
        // DI GetChanges op), with NO shared realm registered — so only the personal
        // processor (personal keys) ever sees it.
        fixture.proxy.additionalImplementations[GetChanges.ID] =
            changesReturning(fixture.proxy, fixture.sharedBlock)

        fixture.components.scheduler.tickOnce()

        // Personal keys can't decrypt a shared-keyed block → it's dropped, not applied.
        assertNull(
            fixture.proxy.executionContext.database.getData(sharedDataId),
            "a shared-realm block must NOT decrypt under the personal realm's keys",
        )
    }

    /**
     * Per-shared-realm schema fence (`core-schema-evolution.md` §7.3): a shared realm
     * whose own epoch records a version above what this device declares is **not
     * pulled at all** — no fetch (cheaper than fetch-and-discard, and a pull that never
     * runs cannot half-apply), and therefore no cursor advance, so the skipped blocks
     * are still waiting once this device updates.
     *
     * The same block that `pullSharedRealms_appliesSharedStreamUnderSharedKeys…` proves
     * *does* apply is used here, so the only difference is the fence.
     */
    @Test
    fun fencedSharedRealm_isNotPulled_andItsCursorStandsStill() = runTest {
        val fixture = fixture()
        val sharedRelay = MockSyncRelay(fixture.proxy.tContext)
        sharedRelay.pushBlock(fixture.sharedBlock)
        fixture.components.realmRegistry.register(fixture.sharedRealm, sharedRelay)

        // The shared realm's OWN epoch row (namespace-keyed) records SyncService ABOVE
        // whatever this build declares — i.e. its owner writes shapes we would garble.
        // ⚠ Derived, never hardcoded: a literal stops being *above* current the moment
        // the service version moves, and a fence test whose premise is a literal stops
        // testing the fence.
        fixture.proxy.executionContext.database.saveData(
            RealmSchemaEpoch.create(
                context = fixture.proxy.tContext,
                epochId = RealmRegistry.epochKeyFor("shared-1"),
                versions = VersionVector.create(
                    context = fixture.proxy.tContext,
                    coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
                    services = listOf(
                        ServiceVersion.create(
                            fixture.proxy.tContext,
                            SyncService.ID,
                            fixture.proxy.schemaRegistry
                                .currentVersionVector(fixture.proxy.tContext)
                                .toMap()
                                .getValue(SyncService.ID) + 1,
                        ),
                    ),
                ),
            ),
        )

        fixture.components.scheduler.tickOnce()

        assertNull(
            fixture.proxy.executionContext.database.getData(fixture.sharedEntityDataId),
            "a fenced shared realm must not ingest — that is the whole point of the fence",
        )
        val runtime = fixture.components.realmRegistry.sharedRuntimesWithRelay().single()
        assertEquals(
            0L,
            runtime.cursor,
            "the cursor must not move, or the skipped blocks are lost once the device updates",
        )
    }

    /**
     * The converse, and the false positive that kept shared realms unfenced before
     * per-realm epoch keys: a *personal*-realm skew must never halt a shared folder
     * that this device reads perfectly well.
     */
    @Test
    fun aSharedRealmThatRefusesThisDevice_backsOff_ratherThanPullingEveryTick() = runTest {
        val fixture = fixture()
        val refusing = RefusingSharedRelay(fixture.proxy.tContext, MockSyncRelay(fixture.proxy.tContext))
        fixture.components.realmRegistry.register(fixture.sharedRealm, refusing)

        repeat(4) { fixture.components.scheduler.tickOnce() }

        // ⚠⚠ The failure must not be swallowed (e.g. by `getOrNull()`): a `401` on a shared realm
        // with no backoff, no health signal and no log retries at tick cadence forever — a request
        // storm on a path nothing is watching. One attempt per backoff window, not one per tick.
        assertEquals(
            1,
            refusing.getChangesCalls,
            "a refused shared realm must back off after its first 401, not pull on every tick",
        )
    }

    /**
     * ⚠⚠ "Forget this device" stops every shared realm (`admission-revocation-names-the-capability.md`
     * §7i.5). Each runtime holds its own copy of the forgotten identity and an in-memory cursor:
     * left registered, the scheduler went on pulling under the old id and key — straight back into
     * the store the forget had just wiped — and pushing the realm's queue as a removed device.
     */
    @Test
    fun forgetThisDevice_stopsEverySharedRealm_soNoDrainOrPullRunsUnderTheOldIdentity() = runTest {
        val fixture = fixture()
        val relay = CountingSharedRelay(MockSyncRelay(fixture.proxy.tContext))
        relay.delegate.pushBlock(fixture.sharedBlock)
        fixture.components.realmRegistry.register(fixture.sharedRealm, relay)
        val oldId = fixture.syncManager.currentDeviceId

        ForgetThisDeviceImplementation(
            syncManager = fixture.syncManager,
            revokeDevice = { _ -> NoOpRevoke() },
            nowMs = { 0L },
            realmRegistry = fixture.components.realmRegistry,
        ).execute<Data?>(fixture.proxy.executionContext, null, fixture.proxy.executionContext.implementationFactory, null, null)
        fixture.components.scheduler.tickOnce()

        assertEquals(0, relay.getChangesCalls, "no shared-realm pull may run after Forget")
        assertEquals(0, relay.pushCalls, "nor a shared-realm push")
        assertTrue(fixture.components.realmRegistry.sharedRealms().isEmpty(), "every shared runtime is gone")
        assertTrue(
            fixture.components.realmRegistry.sharedRealms().none { it.deviceId == oldId },
            "no context holds the forgotten id",
        )
        assertNull(
            fixture.proxy.executionContext.database.getData(fixture.sharedEntityDataId),
            "the wiped store must not be refilled from the realm the device left",
        )
    }

    /**
     * ⚠⚠ A membership that arrives while the forget runs — the personal pull goes on until custody
     * is cleared — must not register a runtime under the identity being forgotten: it would
     * survive the forget and fail on the zeroed key forever.
     */
    @Test
    fun aMembershipArrivingMidForget_registersNothing() = runTest {
        val fixture = fixture()
        val registry = fixture.components.realmRegistry
        val relay = CountingSharedRelay(MockSyncRelay(fixture.proxy.tContext))
        var registeredDuringForget: Boolean? = null

        ForgetThisDeviceImplementation(
            syncManager = fixture.syncManager,
            // Runs inside the forget, where `SharedRealmJoinObserver` would fire on a pulled row.
            revokeDevice = { _ ->
                object : org.tenshu.core.common.proxy.executor.AsyncOperationImplementation {
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <T : Data?> execute(
                        executionContext: ExecutionContext,
                        operationContext: OperationContext?,
                        implementationFactory: OperationImplementationFactory,
                        caller: Any?,
                        params: Map<String, Data>?,
                    ): T {
                        registry.register(fixture.sharedRealm, relay)
                        registeredDuringForget = registry.isRegistered(fixture.sharedRealm.ownerId)
                        return null as T
                    }
                }
            },
            nowMs = { 0L },
            realmRegistry = registry,
        ).execute<Data?>(fixture.proxy.executionContext, null, fixture.proxy.executionContext.implementationFactory, null, null)

        assertEquals(false, registeredDuringForget, "registration is refused while a forget runs")
        assertTrue(registry.sharedRealms().isEmpty(), "and no runtime survives the forget")
        fixture.components.scheduler.tickOnce()
        assertEquals(0, relay.getChangesCalls)
        // After the forget the registry takes registrations again (the next realm's memberships).
        registry.register(fixture.sharedRealm, relay)
        assertTrue(registry.isRegistered(fixture.sharedRealm.ownerId), "the fence lifts when the forget ends")
    }

    /** A pull whose realm is unregistered while its fetch is in flight applies nothing. */
    @Test
    fun aSharedRealmUnregisteredMidFetch_appliesNothing() = runTest {
        val fixture = fixture()
        val registry = fixture.components.realmRegistry
        val relay = CountingSharedRelay(MockSyncRelay(fixture.proxy.tContext)) {
            registry.unregisterAll()
        }
        relay.delegate.pushBlock(fixture.sharedBlock)
        registry.register(fixture.sharedRealm, relay)

        fixture.components.scheduler.tickOnce()

        assertEquals(1, relay.getChangesCalls, "precondition: the fetch ran")
        assertNull(
            fixture.proxy.executionContext.database.getData(fixture.sharedEntityDataId),
            "a fetch answered after its realm was forgotten must not be applied",
        )
    }

    @Test
    fun personalRealmSkew_doesNotFenceTheSharedPull() = runTest {
        val fixture = fixture()
        val sharedRelay = MockSyncRelay(fixture.proxy.tContext)
        sharedRelay.pushBlock(fixture.sharedBlock)
        fixture.components.realmRegistry.register(fixture.sharedRealm, sharedRelay)

        // The PERSONAL epoch is ahead (bare key), the shared realm's is absent.
        fixture.proxy.executionContext.database.saveData(
            RealmSchemaEpoch.create(
                context = fixture.proxy.tContext,
                versions = VersionVector.create(
                    context = fixture.proxy.tContext,
                    coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
                    services = listOf(
                        ServiceVersion.create(fixture.proxy.tContext, SyncService.ID, 3),
                    ),
                ),
            ),
        )

        fixture.components.scheduler.tickOnce()

        assertNotNull(
            fixture.proxy.executionContext.database.getData(fixture.sharedEntityDataId),
            "the shared realm is current, so its collaboration must keep running",
        )
    }

    // ---- fixture ---------------------------------------------------------

    private class Fixture(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val components: SyncProxyComponents,
        val sharedRealm: RealmContext,
        val sharedBlock: SyncBlock,
        val sharedEntityDataId: DataId,
    )

    private suspend fun CoroutineScope.fixture(): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        // Personal inbound stream stays empty by default (the positive test runs a
        // full `tickOnce`); the negative test overrides GetChanges.
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("personal phrase placeholder words go here now")

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Stepped by hand via `tickOnce`, so the loops are never started rather than started
            // and then stopped — a loop that never starts has no first tick to race
            // (`SyncHealthReportingTest.fixture`). Hygiene here: the startup tick is inert — nothing
            // registered, nothing queued.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )

        val sharedRealm = RealmContext(
            ownerId = "shared-1",
            deviceId = syncManager.currentDeviceId,
            derivedKeys = sharedKeys,
            // A real (stub) device signing key so `buildSharedTip` can sign the poll's tip.
            deviceSigningKeyPair = crypto.generateDeviceSigningKeyPair(),
            masterKeyPair = null,
        )

        // Build the entity + a block encrypted under the SHARED identity key,
        // mirroring `SyncObserver.generateSyncBlock`'s wire shape exactly.
        val entity = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, name = "shared.txt", value = 7L),
            properties = emptyMap(),
        )
        val sharedDataId = proxy.executionContext.getDataId(entity)!!
        val payload = DataBlockPayload.create(proxy.tContext, propertyId = null, value = entity)
        val serialized = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), proxy.versionStampBytes())
        val block = org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = proxy.tContext,
            blockId = generateId(),
            ownerId = "shared-1",
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = sharedDataId.getDataIdHash().toString(),
            plaintext = serialized,
            identityKey = sharedKeys.sharedIdentityKey,
        )

        return Fixture(proxy, syncManager, components, sharedRealm, block, sharedDataId)
    }

    private fun changesReturning(proxy: TestProxy, vararg blocks: SyncBlock) =
        object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                @Suppress("UNCHECKED_CAST")
                return ChangesResponse.create(
                    executionContext.context, blocks.toList(), emptyList(), 1L
                ) as T
            }
        }

    private fun emptyChanges() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return ChangesResponse.create(
                executionContext.context, emptyList(), emptyList(), 0L
            ) as T
        }
    }

    private fun emptyTombstones() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return TombstonesResponse.create(
                executionContext.context, emptyList(), 0L
            ) as T
        }
    }
}

/**
 * Answers every `getChanges` with a relay `401`, and delegates the rest.
 *
 * ⚠ The marker is built from [UNAUTHORIZED_REJECTION_MARKER], never hand-typed: a fake whose error
 * text drifts from the producer's passes against a detector that never fires in production.
 */
private class RefusingSharedRelay(
    private val context: TContext,
    delegate: MockSyncRelay,
) : SyncRelay by delegate {
    var getChangesCalls = 0
        private set

    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: SignedHlcTip,
    ): TResult<ChangesResponse> {
        getChangesCalls++
        return TResult.Failure.create(
            context,
            GenericError.create(context, "$UNAUTHORIZED_REJECTION_MARKER: 401 — Invalid token"),
        )
    }
}

/** Counts relay calls; [onGetChanges] runs inside the fetch (to unregister mid-flight). */
private class CountingSharedRelay(
    val delegate: MockSyncRelay,
    private val onGetChanges: () -> Unit = {},
) : SyncRelay by delegate {
    var getChangesCalls = 0
        private set
    var pushCalls = 0
        private set

    override suspend fun getChanges(sinceTimestamp: Long, tip: SignedHlcTip): TResult<ChangesResponse> {
        getChangesCalls++
        onGetChanges()
        return delegate.getChanges(sinceTimestamp, tip)
    }

    override suspend fun pushBlock(
        block: SyncBlock,
        edges: List<org.tenshu.core.common.content.ContentEdge>,
    ): TResult<Data?> {
        pushCalls++
        return delegate.pushBlock(block, edges)
    }
}

private class NoOpRevoke : org.tenshu.core.common.proxy.executor.AsyncOperationImplementation {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T = null as T
}
