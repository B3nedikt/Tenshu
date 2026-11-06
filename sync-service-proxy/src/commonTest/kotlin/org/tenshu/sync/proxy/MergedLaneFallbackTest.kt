package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TenshuException
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The merged lane's client half (`sync-lane-cursor-split.md` Option C / L3) and — the part
 * that actually keeps devices working — **how it degrades**.
 *
 * Two independent things can make the merged lane unavailable, and neither may be allowed to
 * look like a network failure:
 *
 *  1. the relay predates `/sync/log` (typed [RelayRouteUnsupportedError]);
 *  2. this proxy has no `GetSyncLog` implementation wired at all — iOS route-1 and the JS
 *     runtime assemble their own operation graphs, and half the harnesses in this repo
 *     register ops by hand.
 *
 * In both cases the two-lane path must carry the tick and the device must stay **online**.
 * The failure this guards against is nasty precisely because it hides itself: a device that
 * reports "offline" while a working fallback exists looks like a connectivity problem, and
 * the sync it is silently not doing looks like the relay's fault.
 */
class MergedLaneFallbackTest {

    private class Fixture(
        val proxy: TestProxy,
        val scheduler: SyncScheduler,
        val syncManager: SyncManagerImpl,
        val health: SyncHealthHolder,
        val syncLogCalls: () -> Int,
        val changesCalls: () -> Int,
        val tombstoneCalls: () -> Int,
    )

    /**
     * @param wireMergedLane register a `GetSyncLog` implementation at all. `false` models a
     *   proxy whose operation graph never included it.
     * @param relaySupportsMergedLane when wired, whether it answers or reports the route
     *   missing — i.e. an older relay behind a current client.
     */
    /**
     * ⚠ [scope] must be the enclosing `runTest` scope, so the proxy's own machinery (emit
     * fan-out, session scope) rides the test's virtual clock. The sync loops themselves are
     * never started (`startBackgroundLoops = false` below): every exact call count here is off
     * by one the moment a startup tick gets to run — `the merged lane is the default path
     * expected:<1> but was:<2>`, forced 3/3 red with "start, then `stopAndAwait`" and 3/3 green
     * with the loops un-started (2026-09-03). `SyncHealthReportingTest.fixture` has the why.
     */
    private suspend fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        wireMergedLane: Boolean = true,
        relaySupportsMergedLane: Boolean = true,
    ): Fixture {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = scope,
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val syncLogCalls = intArrayOf(0)
        val changesCalls = intArrayOf(0)
        val tombstoneCalls = intArrayOf(0)

        proxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
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
                changesCalls[0]++
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
                tombstoneCalls[0]++
                @Suppress("UNCHECKED_CAST")
                return TombstonesResponse.create(
                    context = executionContext.context,
                    tombstones = emptyList(),
                    nextCursor = 0L
                ) as T
            }
        }
        if (wireMergedLane) {
            proxy.additionalImplementations[GetSyncLog.ID] = object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    syncLogCalls[0]++
                    if (!relaySupportsMergedLane) {
                        // Exactly what `GetSyncLogImplementation` throws for a bare 404.
                        throw TenshuException(
                            RelayRouteUnsupportedError.create(executionContext.context, "/sync/log")
                        )
                    }
                    @Suppress("UNCHECKED_CAST")
                    return SyncLogResponse.create(
                        context = executionContext.context,
                        blocks = emptyList(),
                        tombstones = emptyList(),
                        peerTips = emptyList(),
                        nextCursor = 0L,
                        hasMore = false
                    ) as T
                }
            }
        }

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")
        syncManager.currentRealmContext()?.deviceId?.takeIf { it.isNotEmpty() }
            ?.let { proxy.preRecordDeviceSchemaVersion(it) }

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Stepped by hand via `tickOnce`; see the KDoc above.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Fixture(
            proxy = proxy,
            scheduler = components.scheduler,
            syncManager = syncManager,
            health = components.syncHealthHolder,
            syncLogCalls = { syncLogCalls[0] },
            changesCalls = { changesCalls[0] },
            tombstoneCalls = { tombstoneCalls[0] },
        )
    }

    /** See the twin in `SyncHealthReportingTest` — keeps setup traffic out of the assertions. */
    private fun TestProxy.preRecordDeviceSchemaVersion(deviceId: String) {
        executionContext.saveData(
            DeviceSchemaVersion.create(
                context = tContext,
                deviceId = deviceId,
                versions = schemaRegistry.currentVersionVector(tContext),
            ),
            WriteOrigin.SYNC_INGEST,
        )
    }

    @Test
    fun aSupportingRelayIsPulledThroughTheMergedLaneAlone() = runTest {
        val f = fixture(this)
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()

            assertEquals(1, f.syncLogCalls(), "the merged lane is the default path")
            assertEquals(
                0, f.changesCalls() + f.tombstoneCalls(),
                "the merged lane supersedes both single-kind lanes — calling them too would " +
                    "double every pull's cost and re-open the dual-cursor problem it removes"
            )
            assertTrue(f.health.current().online)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun anOlderRelayFallsBackToBothLanes_andIsNOTReportedOffline() = runTest {
        val f = fixture(this, relaySupportsMergedLane = false)
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()

            assertEquals(1, f.changesCalls(), "the tick must still pull via the fallback")
            assertEquals(1, f.tombstoneCalls())
            assertTrue(
                f.health.current().online,
                "a relay older than this client is WORKING — reporting the device offline " +
                    "would be a self-hiding lie: the fallback lane syncs fine while the UI " +
                    "blames the network"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun theUnsupportedVerdictLatches_soItCostsOneWastedRequestNotOnePerTick() = runTest {
        val f = fixture(this, relaySupportsMergedLane = false)
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()

            assertEquals(
                1, f.syncLogCalls(),
                "the answer cannot change under a running relay, so re-probing every tick " +
                    "would burn a request per tick forever against an older deployment"
            )
            assertEquals(3, f.changesCalls(), "every tick still pulls")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun aProxyWithoutTheOperationWiredFallsBack_ratherThanGoingOffline() = runTest {
        // ⚠ The case that broke six existing tests when this landed: an unimplemented op
        // raises a bare `IllegalStateException` from `ExecutionContext.execute`, which — if
        // caught as a generic failure — flips the device to offline and stops the pull dead.
        // The scheduler therefore *asks* whether the op is wired instead of discovering it by
        // exception; string-matching that message would have been the fragile alternative.
        val f = fixture(this, wireMergedLane = false)
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()

            assertEquals(1, f.changesCalls())
            assertEquals(1, f.tombstoneCalls())
            assertTrue(
                f.health.current().online,
                "a proxy that never wired the merged lane is not an unreachable relay"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun theMergedLaneResumesFromTheLOWEROfTheTwoLaneCursors() = runTest {
        // ⚠ `min`, not `max`. Only `min` is provably delivered in BOTH kinds. Adopting `max`
        // would skip every tombstone between the two cursors — the skip-the-backlog failure
        // `memory/project_sync_cursor_store_desync` records, which is silent data loss rather
        // than a visible stall.
        val f = fixture(this)
        try {
            f.syncManager.realmRelayRegistered = true
            // The exact on-device shape from §2.1: blocks ran far ahead of tombstones.
            f.syncManager.lastSyncTimestamp = 71_391L
            f.syncManager.lastTombstoneSyncTimestamp = 29_544L

            var askedSince = -1L
            f.proxy.additionalImplementations[GetSyncLog.ID] = object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    askedSince = (params?.get(GetSyncLog.Params.SINCE_TIMESTAMP) as org.tenshu.core.number.TInteger).get()
                    @Suppress("UNCHECKED_CAST")
                    return SyncLogResponse.create(
                        context = executionContext.context,
                        blocks = emptyList(),
                        tombstones = emptyList(),
                        peerTips = emptyList(),
                        nextCursor = 80_000L,
                        hasMore = false
                    ) as T
                }
            }

            f.scheduler.tickOnce()

            assertEquals(29_544L, askedSince, "adopting `max` would silently skip ~42 000 seq of tombstones")
            // And once merged, BOTH cursors track the merged value — so a later fallback to
            // the two-lane path resumes correctly with no special case.
            assertEquals(80_000L, f.syncManager.lastSyncTimestamp)
            assertEquals(80_000L, f.syncManager.lastTombstoneSyncTimestamp)
        } finally {
            f.scheduler.stop()
        }
    }
}
