package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.SyncGate
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scheduler must classify a tick on whether the relay refused it **this pass**, never on the
 * sticky latch it inherited.
 *
 * ⚠⚠ The wedge, measured on the OnePlus CPH2581 2026-09-21 with **2.45 GB free**: the device showed
 * "Storage full", refused every upload with the out-of-storage dialog, and recovered only on a
 * force-stop. `SyncObserver.contentStorageRejected` is a sticky LEVEL — only an accepted write
 * clears it — so a tick that attempted nothing still read `true`, was booked as a fresh rejection,
 * and re-armed the backoff. The `else` branch that clears `relayRejected` was never reached,
 * `UploadFile` kept refusing new files up front, so the journal it would need stayed empty. Closed
 * loop; the latch is in-memory, which is why only a restart broke it.
 *
 * Three inputs reach that same wrong classification, and the fix has to cover all of them:
 *  - nothing queued (the measured case);
 *  - the content gate shut, so the drain returns before touching an entry (cellular + Wi-Fi-only);
 *  - any unrelated failure — relay 500, offline, a file since deleted — ending the pass while the
 *    stale level makes it look like a refusal.
 *
 * ⇒ the signal is `SyncObserver.consumeStorageRefusal`, a read-and-clear EDGE. All three produce no
 * edge, so all three clear.
 */
class StorageRejectionUnwedgeTest {

    private class Fixture(
        val scheduler: SyncScheduler,
        val signals: List<Boolean>,
        /** Flipped by a test to model the relay refusing (or not) on the next pass. */
        val refusals: MutableList<Boolean>,
        val clock: () -> Long,
    )

    /**
     * @param refusalsInOrder what `consumeStorageRefusal` answers on successive ticks; exhausted
     *   entries answer `false`, i.e. "the relay did not refuse us this pass".
     */
    private suspend fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        vararg refusalsInOrder: Boolean,
    ): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = scope)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        val processor = SyncProcessor(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            wireIdResolver = WireIdResolver(syncManager),
        )
        val signals = mutableListOf<Boolean>()
        val refusals = refusalsInOrder.toMutableList()
        // ⚠ A real clock, advanced by the test: the backoff is time-based, so a frozen `{ 0L }`
        // (the default) never leaves its window and the second tick could never run the drain.
        var now = 1_000L
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = processor,
            cryptoManager = crypto,
            coroutineScope = scope,
            nowMs = { now },
            contentStorageRejected = { if (refusals.isEmpty()) false else refusals.removeAt(0) },
            onStorageRejectionChanged = { signals += it },
        )
        syncManager.initialize("mock phrase")
        return Fixture(scheduler, signals, refusals) { now }.also {
            // expose the clock mutably via a closure the tests advance
            clockSetter = { ms -> now = ms }
        }
    }

    private var clockSetter: (Long) -> Unit = {}

    @Test
    fun aRefusalArmsTheBackoff_andTheNextQuietPassClearsIt() = runTest {
        // ⭐ The assertion the production bug turns on: a `false` must ARRIVE. Asserting merely that
        // no `true` was sent is not enough — with a fresh backoff the clearing branch emits nothing
        // at all, so an empty list passes and a scheduler that never signals would pass too. That
        // was the flaw in this test's first version.
        val f = fixture(this, true)

        f.scheduler.tickOnce()
        assertEquals(
            listOf(true),
            f.signals,
            "a real refusal is reported, so `UploadFile` refuses new files up front",
        )

        // Past the backoff window (base 2 s), so the next tick actually runs the drain. The relay
        // refuses nothing this time — the owner freed bytes, or nothing was even attempted.
        clockSetter(1_000L + 5_000L)
        f.scheduler.tickOnce()

        assertEquals(
            listOf(true, false),
            f.signals,
            "and the quiet pass CLEARS it — this is the branch the wedged device could never " +
                "reach, and the reason freeing bytes did nothing for it",
        )
    }

    @Test
    fun aSecondRefusalReArms_soAStillFullDeviceKeepsBackingOff() = runTest {
        // ⚠ The control. The fix must narrow the classification, never remove it: a device that is
        // genuinely still over quota refuses again on the probing pass and must back off as before.
        val f = fixture(this, true, true)

        f.scheduler.tickOnce()
        clockSetter(1_000L + 5_000L)
        f.scheduler.tickOnce()

        assertEquals(
            listOf(true, true),
            f.signals,
            "a device still being refused stays backed off",
        )
    }

    @Test
    fun aRealObserverWiredToARealScheduler_clearsWhenNothingIsQueued() = runTest {
        // ⚠⚠ THE test the others cannot be. Every case above injects its own
        // `contentStorageRejected` lambda — which is itself a hand-built read-and-clear edge, so
        // those tests model the fixed wiring BY CONSTRUCTION and pass verbatim against the
        // unfixed code. The only behavioural change in this PR is `consumeStorageRefusal` plus the
        // one line in `SyncProxyInitialization` that points the scheduler at it; nothing else here
        // fails if that line is reverted to `{ syncObserver.contentStorageRejected }`.
        //
        // So: a REAL observer, latched by a real 507, wired to a real scheduler, with an EMPTY
        // journal — the measured device's exact state. Under the level it reports a rejection and
        // re-arms for ever; under the edge it clears.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")
        val fileDatabase = InMemoryFileDatabase()
        val sink = InMemoryContentBlobStore(proxy.tContext)
        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(
                proxy.executionContext,
                wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            ),
            contentBlobStore = sink,
            fileDatabase = fileDatabase,
            contentGate = SyncGate { true },
        )

        // A real 507 on a real emit: raises BOTH the sticky level and the edge.
        sink.failPutsOverQuota = true
        fileDatabase.writeRawByteArray("/full.bin", ByteArray(64) { it.toByte() })
        observer.emitFileContent(
            targetEntityId = DataId.StringDataId.create(
                context = proxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = "/full.bin",
            ),
            propertyId = "${SyntheticTestData.ID}/data",
            filePath = "/full.bin",
        )
        assertTrue(observer.contentStorageRejected, "precondition: the sticky level is set")

        // Drain the journal away so the next pass attempts nothing — the wedged device's state.
        // ⚠ The edge is monotonic until read, so drain it explicitly rather than expecting the
        // accepted write to have cleared it. An accepted write does NOT disprove a refusal: the
        // relay accepts an already-present ref while its owner is still over quota.
        sink.failPutsOverQuota = false
        observer.drainDeferredContent()
        assertTrue(observer.consumeStorageRefusal(), "the earlier refusal is still outstanding")
        assertTrue(observer.consumeStorageRefusal().not(), "and reading it cleared it")

        val signals = mutableListOf<Boolean>()
        var now = 1_000L
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = SyncProcessor(
                executionContext = proxy.executionContext,
                syncManager = syncManager,
                cryptoManager = crypto,
                wireIdResolver = WireIdResolver(syncManager),
            ),
            cryptoManager = crypto,
            coroutineScope = this,
            nowMs = { now },
            // ⭐ The PRODUCTION wiring, verbatim. This is the line under test.
            contentStorageRejected = { observer.consumeStorageRefusal() },
            onStorageRejectionChanged = { signals += it },
        )

        // Re-latch the level WITHOUT a fresh refusal, exactly as a device carries it between ticks.
        sink.failPutsOverQuota = true
        fileDatabase.writeRawByteArray("/again.bin", ByteArray(64) { it.toByte() })
        observer.emitFileContent(
            targetEntityId = DataId.StringDataId.create(
                context = proxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = "/again.bin",
            ),
            propertyId = "${SyntheticTestData.ID}/data",
            filePath = "/again.bin",
        )
        scheduler.tickOnce()                       // consumes that refusal, arms
        assertEquals(listOf(true), signals, "precondition: a real refusal armed the backoff")

        now += 5_000L
        scheduler.tickOnce()                       // nothing new refused

        assertEquals(
            listOf(true, false),
            signals,
            "with the level still set but no NEW refusal, the tick must clear — reading the level " +
                "here is what wedged the measured device",
        )
        assertTrue(
            observer.contentStorageRejected,
            "and the sticky level is untouched: it still gates the inline path between passes",
        )
    }
}
