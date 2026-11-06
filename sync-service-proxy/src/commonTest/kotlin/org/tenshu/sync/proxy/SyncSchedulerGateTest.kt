package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTByteArray
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Path A (`bgproc-scheduling-roles.md`) + the plane split (`sync-metered-plane-split.md`).
 *
 * ⚠ **The metered [SyncGate] no longer gates this scheduler's cycle.** It used to skip the whole
 * drain/pull, which withheld the kilobyte metadata plane *and the inbound pull* while file bytes
 * went out ungated from `SyncObserver.emitFileContent` — the inversion §0 documents. Enforcement
 * moved to the content plane, so what this class pins now is:
 *
 *  - metadata push + inbound pull run **regardless** of the metered gate (the regression guard:
 *    re-adding a `shouldSync()` branch around the tick re-introduces the silent staleness);
 *  - the `storageGate` (local-first) still gates the personal realm exactly as before;
 *  - `forceDrainOnce()` now releases deferred **content**, one-shot;
 *  - the deferred health verdict keys on the content journal, not the block backlog.
 *
 * Content-plane behaviour itself (journal on a closed gate, drain, coalescing, drop-if-deleted)
 * lives in [MeteredContentGateTest], which drives the observer directly.
 *
 * Mirrors `SyncMetadataOperationTest`'s harness — a mocked `PushBlock` captures
 * the outbound block; we step `tickOnce()` directly (deterministic, no polling
 * loop).
 */
class SyncSchedulerGateTest {

    private class Fixture(
        val proxy: TestProxy,
        val scheduler: SyncScheduler,
        val syncObserver: SyncObserver,
        val syncManager: SyncManagerImpl,
        val pushed: () -> SyncBlock?,
        val pushCount: () -> Int,
        val registrationCount: () -> Int,
        val pullCount: () -> Int,
        val health: () -> SyncHealthFigures,
        val liveness: () -> Int,
        val contentPutCount: () -> Int,
        val fileDatabase: InMemoryFileDatabase,
        /** Last value the scheduler published to `StorageCapacityHolder.queuedContentBytes` (ST-7). */
        val queuedContentBytes: () -> Long,
    )

    private suspend fun CoroutineScope.fixture(
        syncGate: SyncGate = SyncGate { true },
        storageGate: SyncGate = SyncGate { true },
        pushFails: Boolean = false,
    ): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val captured = arrayOfNulls<SyncBlock>(1)
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
                captured[0] = params?.get(PushBlock.Params.BLOCK) as? SyncBlock
                pushes[0]++
                // A generic (non-entitlement) push failure leaves the block queued, so the
                // backlog stays non-empty — the shape that keeps the drain-liveness raised.
                if (pushFails) throw RuntimeException("relay unreachable")
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
                return org.tenshu.sync.TombstonesResponse.create(
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

        // Lazy realm genesis (Phase 2): a registrar over its own recording relay so the
        // test can assert the scheduler runs the deferred POST /sync/realm only inside
        // the open storage gate.
        val registrarRelay = MockSyncRelay(proxy.tContext)
        val realmRegistrar = RealmRegistrar(syncManager, crypto, registrarRelay)

        val backgroundLiveness = org.tenshu.core.common.proxy.BackgroundLiveness()
        val queuedBytes = longArrayOf(-1L)
        // Content plane wired so the metered gate has something to actually gate — without these
        // `emitFileContent` no-ops and the deferral journal can never fill.
        val fileDatabase = InMemoryFileDatabase()
        val contentSink = InMemoryContentBlobStore(proxy.tContext)
        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            syncGate = syncGate,
            storageGate = storageGate,
            realmRegistrar = realmRegistrar,
            backgroundLiveness = backgroundLiveness,
            contentBlobStore = contentSink,
            fileDatabase = fileDatabase,
            // Every test here steps the scheduler by hand via `tickOnce` / `drainNow`, so the
            // loops are never started rather than started and then stopped — a loop that never
            // starts has no first tick to race (`SyncHealthReportingTest.fixture`). Hygiene here:
            // the startup tick was inert under forcing (3/3 green either way, 2026-09-03). The
            // `f.scheduler.stop()` calls inside the tests are now no-ops and were left in place.
            startBackgroundLoops = false,
            onQueuedContentBytes = { queuedBytes[0] = it },
            platformScheduler = NoOpPlatformScheduler,
        )
        return Fixture(
            proxy,
            components.scheduler,
            components.syncObserver,
            syncManager = syncManager,
            pushed = { captured[0] },
            pushCount = { pushes[0] },
            registrationCount = { registrarRelay.createdRealms().size },
            pullCount = { pulls[0] },
            health = { components.syncHealthHolder.current() },
            liveness = { backgroundLiveness.active.value },
            contentPutCount = { contentSink.putCount },
            fileDatabase = fileDatabase,
            queuedContentBytes = { queuedBytes[0] },
        )
    }

    /**
     * Write a file's bytes and ask the observer to emit its content — the shape
     * `UploadFileImplementation` produces. Under a closed content gate this journals a deferred
     * emit instead of `PUT`ting; under an open one it uploads immediately.
     */
    private suspend fun Fixture.emitContent(path: String = "/photo.jpg") {
        fileDatabase.writeRawByteArray(path, ByteArray(64) { it.toByte() })
        syncObserver.emitFileContent(
            targetEntityId = DataId.StringDataId.create(
                context = proxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = path,
            ),
            propertyId = "${SyntheticTestData.ID}/data",
            filePath = path,
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

    // --- sync-metered-plane-split.md §3: the metered gate does not reach the metadata plane.

    @Test
    fun closedMeteredGate_stillDrainsMetadata() = runTest {
        // THE regression guard for the reported bug. Metadata is kilobytes and is what makes the
        // file list coherent; withholding it on cellular is what left Drive claiming "N items
        // waiting" forever while the expensive traffic (bytes) went out anyway.
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle() // block generation is async (Facet A)
            f.scheduler.tickOnce()
            assertNotNull(
                f.pushed(),
                "the metered gate must NOT withhold the metadata plane — it gates content only",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun closedMeteredGate_stillPulls() = runTest {
        // The other half, and the one nothing surfaced: a closed gate used to skip drainAndPull
        // entirely, so a phone on cellular silently received nothing from its peers — with no
        // banner at all whenever the outbound queue happened to be empty.
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            assertTrue(
                f.pullCount() >= 1,
                "the metered gate must NOT stop the inbound pull (silent-staleness guard)",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * `control-background-work-off-fgs.md` §5.3: the push job's progress signal is what the relay
     * ACCEPTED this tick, not the backlog's size, which new work arriving as fast as old work leaves
     * keeps flat.
     */
    @Test
    fun drainNowStalled_reportsAWriteTheRelayAccepted() = runTest {
        val f = fixture(syncGate = SyncGate { true })
        try {
            assertEquals(false, f.scheduler.drainNowStalled().sent, "nothing queued: nothing sent")
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            val verdict = f.scheduler.drainNowStalled()
            assertNotNull(f.pushed())
            assertEquals(true, verdict.sent, "an accepted push is progress")
            assertEquals(false, verdict.stalled)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun allowingGate_drainsAsBefore() = runTest {
        val f = fixture(syncGate = SyncGate { true })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle() // block generation is async (Facet A)
            f.scheduler.tickOnce()
            assertNotNull(f.pushed(), "an allowing SyncGate drains the pending block")
        } finally {
            f.scheduler.stop()
        }
    }

    // --- The deferred health verdict + the "Sync now over cellular" escape hatch, both now keyed
    // on deferred CONTENT (`sync-metered-plane-split.md` §4/§8) rather than the block backlog.

    @Test
    fun closedGateWithDeferredContent_reportsDeferredHealth() = runTest {
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.scheduler.stop()
            f.emitContent()
            f.scheduler.tickOnce()
            assertEquals(0, f.contentPutCount(), "closed gate withholds the file's bytes")
            assertTrue(
                f.health().deferred,
                "a closed gate with a file waiting reports deferred=true",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun closedGateWithOnlyMetadataBacklog_notDeferred() = runTest {
        // The reported bug, as a test. A block backlog is NOT what "Waiting for Wi-Fi" means:
        // PENDING_BLOCKS is realm-wide (every app's Syncable writes) and is no longer
        // metered-gated at all, so it must never drive the deferred verdict.
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()
            assertFalse(
                f.health().deferred,
                "a block backlog must not read as 'waiting for Wi-Fi' — only deferred content does",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun deferredContentFiguresReachTheHealthHolder() = runTest {
        // The figures the ContentTransferStatus row publishes — files and bytes, so the banner can
        // say "N files" instead of counting the realm-wide block queue.
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.scheduler.stop()
            f.emitContent("/a.jpg")
            f.emitContent("/b.jpg")
            f.scheduler.tickOnce()
            val h = f.health()
            assertEquals(2, h.deferredContentCount, "both waiting files are counted")
            assertEquals(128L, h.deferredContentBytes, "…and their bytes (64 each)")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun blockingGateEmptyBacklog_notDeferred() = runTest {
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.scheduler.tickOnce()
            assertFalse(
                f.health().deferred,
                "a closed gate with nothing waiting is just idle, not deferred",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun forceDrainOnce_uploadsDeferredContentPastClosedGate() = runTest {
        val f = fixture(syncGate = SyncGate { false })
        try {
            // The one-shot override is consumed by *our* explicit tick — the fixture never
            // starts the poll loop, so this `stop()` is a leftover no-op.
            f.scheduler.stop()
            f.emitContent()
            assertEquals(0, f.contentPutCount(), "sanity: the gate deferred the upload")
            f.scheduler.forceDrainOnce() // the user tapped "Sync now over cellular"
            f.scheduler.tickOnce()
            assertTrue(
                f.contentPutCount() > 0,
                "forceDrainOnce() uploads the deferred content past a closed (WifiOnly) gate",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun forceDrainOnce_isOneShot() = runTest {
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.scheduler.stop() // deterministic: only our explicit ticks run (see above)
            f.emitContent("/first.jpg")
            f.scheduler.forceDrainOnce()
            f.scheduler.tickOnce()
            val afterOverride = f.contentPutCount()
            assertTrue(afterOverride > 0, "the override uploads the first deferred file")

            // A second file under the still-closed gate, with no fresh override, must NOT upload —
            // the override was consumed by the first tick.
            f.emitContent("/second.jpg")
            f.scheduler.tickOnce()
            assertEquals(
                afterOverride,
                f.contentPutCount(),
                "forceDrainOnce() is one-shot — the next file stays deferred",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    // --- bgproc Phase 6: drainNow() is the on-demand background entry (the Android
    // SyncWorker). It must run the same gated drain as a loop tick, so background sync
    // honours Wi-Fi-only by going through the gate (the old pull-only worker bypassed it).

    @Test
    fun drainNow_drainsPendingBlock_likeATick() = runTest {
        val f = fixture(syncGate = SyncGate { true })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.drainNow()
            assertNotNull(f.pushed(), "drainNow() runs the full drain like a loop tick")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun drainNow_syncsMetadataButHoldsContent_underBlockingGate() = runTest {
        // The background entry point splits exactly like a loop tick: an OS-woken drain on
        // cellular keeps the file list converging (metadata) while honouring Wi-Fi-only for the
        // bytes. Before the split it did neither.
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.scheduler.stop()
            f.proxy.enqueueOutboundBlock()
            f.emitContent()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.drainNow()
            assertNotNull(f.pushed(), "drainNow() still syncs metadata on a metered network")
            assertEquals(
                0,
                f.contentPutCount(),
                "drainNow() honours Wi-Fi-only for file content",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * Local-first (ADR 0019): a blocking `storageGate` (no storage subscription)
     * keeps the personal realm local-only — the queued block stays put and never
     * reaches the relay, even though the network-policy `syncGate` allows.
     */
    @Test
    fun blockingStorageGate_skipsPersonalDrain() = runTest {
        val f = fixture(storageGate = SyncGate { false })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle() // block generation is async (Facet A)
            f.scheduler.tickOnce()
            assertNull(
                f.pushed(),
                "no storage subscription must keep the personal realm local-only"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun allowingStorageGate_drainsPersonalRealm() = runTest {
        val f = fixture(storageGate = SyncGate { true })
        try {
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle() // block generation is async (Facet A)
            f.scheduler.tickOnce()
            assertNotNull(
                f.pushed(),
                "a bound storage subscription drains the personal realm as before"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * Lazy realm genesis (Phase 2): the first tick under an open storage gate runs
     * the deferred `POST /sync/realm` before draining.
     */
    @Test
    fun openStorageGate_runsDeferredRegistration() = runTest {
        val f = fixture(storageGate = SyncGate { true })
        try {
            f.scheduler.tickOnce()
            assertEquals(
                1,
                f.registrationCount(),
                "an open storage gate registers the realm on the first tick"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun closedStorageGate_skipsDeferredRegistration() = runTest {
        val f = fixture(storageGate = SyncGate { false })
        try {
            f.scheduler.tickOnce()
            assertEquals(
                0,
                f.registrationCount(),
                "no subscription ⇒ the realm stays local-only, no relay genesis"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * Cross-device entitlement bootstrap (storage-entitlement-sync.md): a device
     * with a closed storage gate (no *local* subscription) but an
     * already-**registered** realm still PULLS — that inbound pull is what delivers
     * the owner's `Entitlement` written on another device (e.g. the genesis device
     * when the user subscribed on a paired phone). Push/genesis stay gated; only the
     * read side opens.
     */
    @Test
    fun registeredRealm_pullsDespiteClosedStorageGate() = runTest {
        val f = fixture(storageGate = SyncGate { false })
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            // >= 1 (not == 1) predates the un-started loops; the claim under test is that
            // pull runs at all despite the closed storage gate.
            assertTrue(
                f.pullCount() >= 1,
                "a registered realm pulls even without a local subscription"
            )
            // Push side stays gated (genesis does not re-run); the outbound-drain
            // half is covered by blockingStorageGate_skipsPersonalDrain.
            assertEquals(
                0,
                f.registrationCount(),
                "genesis stays gated — pull does not re-run POST /sync/realm"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    /**
     * The local-first floor is preserved: a never-funded realm
     * (`realmRelayRegistered == false`) with a closed storage gate makes **no**
     * relay request at all — neither push nor pull.
     */
    @Test
    fun unregisteredRealm_closedStorageGate_skipsPull() = runTest {
        val f = fixture(storageGate = SyncGate { false })
        try {
            f.scheduler.tickOnce()
            assertEquals(
                0,
                f.pullCount(),
                "a never-registered realm with no subscription makes no relay request"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    // --- Phase L2-DRAIN (core-background-processing.md §4.6): the scheduler raises
    // BackgroundLiveness while it has a *permitted, pushable* backlog so the Android FGS
    // keeps the process alive across a long bulk drain; it lowers it once the backlog
    // clears or the gate closes (a metered device must never pin the FGS under Wi-Fi-only).

    @Test
    fun permittedBacklogThatCannotDrain_holdsLiveness() = runTest {
        // Open gates but the push keeps failing (relay unreachable): the backlog stays
        // non-empty and pushable, so liveness must be HELD at 1 — this is the shape that
        // keeps the process alive across a slow/long drain instead of dying mid-pass.
        val f = fixture(pushFails = true)
        try {
            f.scheduler.stop() // deterministic: only our explicit tick drives liveness
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()
            assertEquals(
                1,
                f.liveness(),
                "a permitted backlog that hasn't drained holds the process-liveness signal",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun fullyDrainedBacklog_releasesLiveness() = runTest {
        // The push succeeds and the queue empties within the tick → nothing left to keep
        // the process alive for, so liveness returns to 0.
        val f = fixture()
        try {
            f.scheduler.stop()
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()
            assertNotNull(f.pushed(), "sanity: the block drained")
            assertEquals(0, f.liveness(), "a drained backlog releases the liveness signal")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun deferredContentUnderClosedGate_doesNotHoldLiveness() = runTest {
        // The load-bearing metered subtlety (§4.6): a WifiOnly device on metered must NOT pin a
        // foreground service under a policy that forbids the upload. The file is *deferred*
        // (health.deferred, tested above) and waits for Wi-Fi; it doesn't busy-hold the process.
        val f = fixture(syncGate = SyncGate { false })
        try {
            f.scheduler.stop()
            f.emitContent()
            f.scheduler.tickOnce()
            assertEquals(
                0,
                f.liveness(),
                "metered-deferred content must not hold the foreground service",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun closedStorageGateWithBacklog_doesNotHoldLiveness() = runTest {
        // Push isn't permitted (no storage subscription), so the backlog can't drain —
        // holding the process alive would be a permanent busy-hold. Liveness stays idle
        // even though the metered gate is open.
        val f = fixture(storageGate = SyncGate { false })
        try {
            f.scheduler.stop()
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()
            assertEquals(
                0,
                f.liveness(),
                "a backlog that can't push (no subscription) must not hold the FGS",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun stop_releasesLiveness() = runTest {
        // Defensive: a scheduler stopped with a backlog still queued must not leave the
        // process pinned alive.
        val f = fixture(pushFails = true)
        try {
            f.scheduler.stop()
            f.proxy.enqueueOutboundBlock()
            f.syncObserver.awaitEmitIdle()
            f.scheduler.tickOnce()
            assertEquals(1, f.liveness(), "sanity: liveness is held before stop")
        } finally {
            f.scheduler.stop()
        }
        assertEquals(0, f.liveness(), "stop() releases the liveness signal")
    }

    @Test
    fun eachTickPublishesTheJournalsOutstandingBytes_soTheUploadPreflightCanSeeTheQueue() = runTest {
        // ⚠⚠ ST-7. The upload gate reads `StorageCapacityHolder`, which is fed from here — without
        // this publish the pre-flight measures a new file against raw headroom and waves it onto a
        // queue that cannot drain. Measured 2026-09-06: 31.8 MB journalled, 836 bytes actually free,
        // and every new upload still accepted.
        val f = fixture(syncGate = SyncGate { false })   // closed content gate ⇒ the journal fills
        f.emitContent("/a.jpg")
        f.emitContent("/b.jpg")

        f.scheduler.tickOnce()

        assertEquals(
            128L,
            f.queuedContentBytes(),
            "two 64-byte files waiting must be reported as 128 bytes of queue",
        )
        f.scheduler.stop()
    }

    @Test
    fun anEmptyJournalPublishesZero_soADrainedQueueStopsChargingAgainstNewUploads() = runTest {
        // ⚠ The release half. A figure that only ever grew would permanently shrink the headroom
        // every future upload is measured against — refusing files onto a realm with room, which is
        // a worse failure than the one ST-7 fixes.
        val f = fixture(syncGate = SyncGate { true })    // open gate ⇒ content uploads and clears
        f.emitContent("/a.jpg")

        f.scheduler.tickOnce()

        assertEquals(0L, f.queuedContentBytes(), "an uploaded file leaves no queue behind it")
        f.scheduler.stop()
    }

}
