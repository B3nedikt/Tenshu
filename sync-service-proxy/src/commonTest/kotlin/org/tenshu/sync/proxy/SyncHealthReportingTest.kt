package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.core.TContext
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [SyncScheduler] reports its per-tick health into the [SyncHealthHolder]
 * (`drive-connectivity-service.md`): relay reachability from the pull outcome,
 * `isSyncing` reset at tick end, and the outbound backlog count. Mirrors
 * [SyncSchedulerGateTest]'s harness — step `tickOnce()` directly.
 */
class SyncHealthReportingTest {

    /** Counts every `PushBlock` the scheduler actually attempts, across all drains. */
    private var pushAttempts = 0

    /**
     * When set, every push fails carrying this marker text — `ENTITLEMENT_REJECTION_MARKER` for a
     * `402`, [UNAUTHORIZED_REJECTION_MARKER] for a `401`. Both are queue-wide rejections, so the drain
     * must abandon the pass rather than send one doomed request per queued block.
     */
    private var pushRejection: String? = null

    private class Fixture(
        val proxy: TestProxy,
        val scheduler: SyncScheduler,
        val syncObserver: SyncObserver,
        val syncManager: SyncManagerImpl,
        val health: SyncHealthHolder,
        /** Non-null when the fixture was asked to observe the registration repair. */
        val registrarRelay: RecordingRegistrarRelay? = null,
    )

    private suspend fun CoroutineScope.fixture(
        storageGate: SyncGate = SyncGate { true },
        getChangesFails: Boolean = false,
        /**
         * `GetChanges` fails the way a relay that has forgotten this device does: a typed failure
         * carrying `401 Unauthorized`. Distinct from [getChangesFails] (an unreachable relay) — the
         * whole point of the 401 path is that those two must not report the same thing.
         */
        getChangesUnauthorized: () -> Boolean = { false },
        /**
         * Build the real [RealmRegistrar] over a recording relay and wire it into the scheduler
         * exactly as production does — so a test can observe whether the *scheduler* performs the
         * repair, not merely whether the registrar is capable of it.
         */
        observeRegistrar: Boolean = false,
        /**
         * Controllable wall clock. ⚠ The default `{ 0L }` freezes the backoff windows *closed*
         * (`nowMs() < nextAttemptAtMs` is permanently true after one rejection), so any test that
         * needs the probing boundary to open must pass a clock it can advance.
         */
        nowMs: () -> Long = { 0L },
    ): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        proxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                pushAttempts++
                pushRejection?.let { marker ->
                    throw TenshuException(
                        GenericError.create(executionContext.context, marker),
                    )
                }
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
                if (getChangesFails) error("relay unreachable")
                if (getChangesUnauthorized()) {
                    throw TenshuException(
                        GenericError.create(
                            executionContext.context,
                            // Built from the constant, not a hand-typed status line: the previous
                            // literal ("401 Unauthorized") was a shape the production relay never
                            // sends over h2, and this fake being gentler than the wire is exactly
                            // what let the inert detector pass. See UNAUTHORIZED_REJECTION_MARKER.
                            "$UNAUTHORIZED_REJECTION_MARKER: 401 — Invalid token",
                        ),
                    )
                }
                @Suppress("UNCHECKED_CAST")
                return ChangesResponse.create(
                    context = executionContext.context,
                    blocks = emptyList(),
                    peerTips = emptyList(),
                    nextCursor = 0L
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
                    nextCursor = 0L
                ) as T
            }
        }

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")
        // Keep the outbound queue empty at t0 — see preRecordDeviceSchemaVersion.
        syncManager.currentRealmContext()?.deviceId?.takeIf { it.isNotEmpty() }
            ?.let { proxy.preRecordDeviceSchemaVersion(it) }

        val registrarRelay =
            if (observeRegistrar) RecordingRegistrarRelay(proxy.tContext) else null
        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            storageGate = storageGate,
            realmRegistrar = registrarRelay?.let { RealmRegistrar(syncManager, crypto, it) },
            nowMs = nowMs,
            // Stepped by hand via `tickOnce`, so the loops are never started — not started and then
            // stopped. Stopping a started loop holds only by luck:
            //
            //  - `stop()` (a bare cancel) lets a tick already inside `tickOnce` run on and pull
            //    while the test steps its own — two `tickOnce` stacks on two threads.
            //  - `stopAndAwait()` (cancel + join) still loses whenever the loop's first tick gets
            //    to run before the cancel lands: that tick's `ensureRegistered` hits the recording
            //    relay before this test sets `realmRelayRegistered`, so `createRealmCalls` reads 1
            //    at the test's tick 1 — deterministic with a single yield between `start()` and
            //    the cancel.
            //
            // A loop that never starts has no first tick to race.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Fixture(
            proxy,
            components.scheduler,
            components.syncObserver,
            syncManager = syncManager,
            health = components.syncHealthHolder,
            registrarRelay = registrarRelay,
        )
    }

    /**
     * Pre-record this device's `DeviceSchemaVersion` row so `initSyncService`'s
     * E4-2 publisher finds it unchanged and stays silent.
     *
     * ⚠ Written with [WriteOrigin.SYNC_INGEST] — an ordinary save would emit the
     * very blocks this is here to avoid. Without it a fresh proxy starts with a
     * non-empty outbound queue (the version row **plus** the realm-epoch bump it
     * triggers), and every assertion in this file about "the backlog" or "nothing
     * waiting" is counting setup traffic. The publisher has its own coverage in
     * `DeviceSchemaVersionPublisherTest`; here it is noise.
     */
    private fun TestProxy.preRecordDeviceSchemaVersion(deviceId: String) {
        val registry = schemaRegistry
        executionContext.saveData(
            DeviceSchemaVersion.create(
                context = tContext,
                deviceId = deviceId,
                versions = registry.currentVersionVector(tContext),
            ),
            WriteOrigin.SYNC_INGEST,
        )
    }

    private fun TestProxy.enqueueOutboundBlock() {
        val entity = Entity.create<SyntheticTestData>(
            context = tContext,
            composite = SyntheticTestData.Factory.create(
                context = tContext,
                name = "sync.txt",
                value = 100L
            ),
            properties = emptyMap()
        )
        executionContext.saveData(entity)
    }

    @Test
    fun aQueueWideRejection_stopsThePass_insteadOfOneDoomedRequestPerBlock() = runTest {
        // ⚠⚠ The backoff bounds how often a PASS runs, not the fan-out inside one: without this
        // stop, a device with no entitlement emits a burst of one rejected `POST /sync/push` per
        // queued block in every pass, however far apart the passes are.
        // A 402 is scoped to the owner's entitlement and a 401 to this device's identity, so after
        // the first refusal every remaining block in the pass is refused for the same reason.
        //
        // Asserted for BOTH classes, because the 401 path reuses the same drain: whatever bounds one
        // has to bound the other, or "one probe per window" means one probe per *block* per window.
        for (marker in listOf(ENTITLEMENT_REJECTION_MARKER, UNAUTHORIZED_REJECTION_MARKER)) {
            pushAttempts = 0
            pushRejection = marker
            val f = fixture()
            try {
                repeat(12) { f.proxy.enqueueOutboundBlock() }
                f.syncObserver.awaitEmitIdle()
                f.scheduler.tickOnce()

                assertEquals(
                    1,
                    pushAttempts,
                    "a queue-wide rejection ($marker) must cost ONE request, not one per queued block",
                )
            } finally {
                f.scheduler.stop()
                pushRejection = null
            }
        }
    }

    @Test
    fun aPerBlockFailure_stillTriesTheRestOfTheQueue() = runTest {
        // The complement, so the fix above cannot be "stop on any failure": a transient per-block
        // failure (network blip, one bad block) says nothing about the blocks behind it, and
        // abandoning the pass there would strand a whole queue behind one dud.
        pushAttempts = 0
        pushRejection = "Server error: 500 Internal Server Error"
        val f = fixture()
        try {
            repeat(5) { f.proxy.enqueueOutboundBlock() }
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()

            assertTrue(
                pushAttempts >= 5,
                "a transient failure must not abandon the pass, attempted only $pushAttempts",
            )
        } finally {
            f.scheduler.stop()
            pushRejection = null
        }
    }

    @Test
    fun unauthorizedPull_reportsDeviceUnrecognized_andNotOffline() = runTest {
        // A relay that has forgotten this device answers 401 to everything. Two things must be
        // true: the state is reported at all, and it is NOT reported as
        // offline — the relay answered, so "Offline" would send the user to check their network
        // while the real problem (nothing will ever sync until this device is re-admitted) goes
        // unsaid.
        val f = fixture(getChangesUnauthorized = { true })
        try {
            f.syncManager.realmRelayRegistered = true // opens the pull path
            f.scheduler.tickOnce()
            val figures = f.health.current()
            assertTrue(figures.deviceUnrecognized, "a 401 must report the device as unrecognised")
            assertTrue(figures.online, "the relay ANSWERED — it is reachable, so this is not offline")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun unauthorizedPull_schedulerReRegisters_onlyAtTheProbingBoundary() = runTest {
        // The repair `docs/sync/design.md` always claimed the sync path had. `RealmRegistrar`
        // latches genesis once-ever and the only caller of `ReregisterDevice` was the host pairing
        // poll, so a device whose registration the relay lost retried ~8k times/hour forever with
        // nothing ever re-asserting it. Now: back off, repair at the probing boundary, resume.
        //
        // ⚠ The assertion that matters is that the SCHEDULER does this — the registrar being
        // capable of it was never the missing half.
        val clock = FakeClock()
        val f = fixture(
            getChangesUnauthorized = { true },
            observeRegistrar = true,
            nowMs = { clock.now },
        )
        val relay = f.registrarRelay!!
        try {
            f.syncManager.realmRelayRegistered = true

            f.scheduler.tickOnce()
            assertTrue(f.health.current().deviceUnrecognized, "tick 1 records the 401")
            assertEquals(0, relay.createRealmCalls, "tick 1 only records it — the window opens later")

            // Still inside the first window: no repair, and no storm either.
            f.scheduler.tickOnce()
            assertEquals(0, relay.createRealmCalls, "inside the backoff window nothing is attempted")

            // The window elapses → the next tick repairs, exactly once. Advancing by the cap rather
            // than one base delay because each failing tick re-arms (and grows) the window, so the
            // boundary is not at a fixed multiple of the base — the cap bounds it either way.
            clock.advance(RejectionBackoff.DEFAULT_MAX_DELAY_MS)
            f.scheduler.tickOnce()
            assertEquals(1, relay.createRealmCalls, "the scheduler re-asserts the genesis at the boundary")

            // ⚠ And it does NOT storm afterwards: the relay accepted the repair but still 401s the
            // traffic, which must re-arm the window instead of repairing on every tick.
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()
            assertEquals(1, relay.createRealmCalls, "a repair the relay accepted must not retry per tick")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun unauthorizedPull_stopsPullingInsideTheWindow_thenProbesOncePerWindow() = runTest {
        // The storm this bounds was mostly PULLS — 19,646 rejected `POST /sync/log` of the ~40k
        // requests measured in five hours. Gating only the push would have halved the traffic and
        // read as fixed, so assert the pull count directly: one attempt per window, not per tick.
        val clock = FakeClock()
        var pulls = 0
        val f = fixture(
            getChangesUnauthorized = { pulls++; true },
            nowMs = { clock.now },
        )
        try {
            f.syncManager.realmRelayRegistered = true

            f.scheduler.tickOnce()
            assertEquals(1, pulls, "the first tick pulls and gets the 401")

            // Three more ticks inside the window must not reach the relay at all.
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()
            assertEquals(1, pulls, "inside the backoff window the pull is skipped entirely")

            // Past the window: exactly one probing pull.
            clock.advance(RejectionBackoff.DEFAULT_MAX_DELAY_MS)
            f.scheduler.tickOnce()
            assertEquals(2, pulls, "the window boundary allows exactly one probing pull")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun unauthorizedThenAccepted_clearsTheState_soItCannotStickOnAHealthyDevice() = runTest {
        // The flag must reflect what the relay says *now*, not latch. A repaired device — or one
        // whose 401 was a passing clock skew — has to fall back to a plain online reading, or the
        // banner keeps telling a perfectly healthy device it was removed from the account.
        val clock = FakeClock()
        var unauthorized = true
        val f = fixture(getChangesUnauthorized = { unauthorized }, nowMs = { clock.now })
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            assertTrue(f.health.current().deviceUnrecognized, "precondition: the 401 was recorded")

            // The relay accepts us again (repaired, or the skew passed).
            unauthorized = false
            clock.advance(RejectionBackoff.DEFAULT_BASE_DELAY_MS)
            f.scheduler.tickOnce()

            assertFalse(
                f.health.current().deviceUnrecognized,
                "an accepted pull must clear the unrecognised state",
            )
            assertTrue(f.health.current().online)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun successfulPull_marksOnline_andClearsSyncing() = runTest {
        val f = fixture()
        try {
            f.syncManager.realmRelayRegistered = true // opens the pull path
            f.scheduler.tickOnce()
            val figures = f.health.current()
            assertTrue(figures.online, "a successful GetChanges marks the relay reachable")
            assertFalse(figures.isSyncing, "isSyncing is reset once the tick completes")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun failedPull_marksOffline() = runTest {
        val f = fixture(getChangesFails = true)
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            assertFalse(
                f.health.current().online,
                "a failed GetChanges marks the relay unreachable",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun queuedBlock_reportedAsPendingCount() = runTest {
        // Storage gate closed → the block stays queued (never pushed), so the health
        // report surfaces the backlog the banner turns into a "Syncing" state.
        val f = fixture(storageGate = SyncGate { false })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()
            assertEquals(
                1,
                f.health.current().pendingBlockCount,
                "the queued outbound block is reported as backlog",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
    }
}

/**
 * Records the relay calls [RealmRegistrar.reregisterThisDevice] makes, and accepts the genesis —
 * the shape of a relay whose data was wiped: the realm record is gone, so `createRealm` succeeds
 * rather than 409ing, and the admit-device fallback is never needed.
 */
private class RecordingRegistrarRelay(private val context: TContext) : SyncRelay {
    var createRealmCalls = 0
        private set
    var admitDeviceCalls = 0
        private set

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        createRealmCalls++
        return TResult.Success.create(context, null)
    }

    override suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        admitDeviceCalls++
        return TResult.Success.create(context, null)
    }

    override suspend fun fetchRealmEpoch(
        ownerId: String,
    ): TResult<org.tenshu.core.number.TInteger> = error("not used")

    override suspend fun pushBlock(block: SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> = error("not used")
    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: org.tenshu.sync.SignedHlcTip,
    ) = error("not used")
    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> =
        error("not used")
    override suspend fun getTombstones(sinceTimestamp: Long) = error("not used")
    override suspend fun fetchEntityBlocks(
        entityWireId: String,
    ): TResult<org.tenshu.core.collection.list.TList<SyncBlock>> = error("not used")
    override suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
    ): TResult<Data?> = error("not used")
    override suspend fun getPairingRequests(
        ownerId: String,
    ): org.tenshu.sync.common.SyncRelay.PairingRequestsFetch =
        error("not used")
    override suspend fun postPairingResponse(
        pairingSecret: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> = error("not used")
    override suspend fun ackPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun getPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun recoveryChallenge() = error("not used")
    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray,
    ) = error("not used")
}
