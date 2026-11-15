package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.proxy.impl.ForgetThisDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The retirement wait's exit conditions, on the **real** [ForgetThisDeviceImplementation].
 *
 * ## ⚠⚠ These assert the CLOCK, not only the wording
 *
 * A test that checked the returned string alone would pass with the 8-second wait fully intact —
 * which is the defect being fixed, and exactly the "green and wrong" shape this flow has already
 * produced repeatedly. `nowMs` is injected, so elapsed time is exact and asserting on it is cheap.
 *
 * Measured on device 2026-08-26: a relay answering `404 Realm not found` failed the admission strip
 * on every attempt, so the poll ran its full 8s each time. That was the bulk of a ~10s dead wait the
 * maintainer reported as *"confirming does nothing"*.
 */
class ForgetThisDeviceRetirementWaitTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        // The sync schemas, so the fake revoke can queue a real `DeleteBlock`.
        testProxy.schemaRegistry.registerConfig(org.tenshu.sync.common.syncServiceCommonModule)
        syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
            objectStore = InMemoryObjectStore(),
        )
        syncManager.initialize("mock phrase")
    }

    /** A clock that only advances when the code under test polls on it, so elapsed time is exact. */
    private class FakeClock {
        var now = 0L
    }

    /**
     * Stands in for `RevokeDeviceImplementation`, reporting the strip outcome the same way it does.
     * With [queueTombstone] it also leaves this device's own retirement in `PENDING_TOMBSTONES`, as
     * the real one does, and nothing here drains it: a push that never leaves.
     */
    private inner class FakeRevoke(
        private val onRelayStripFailed: () -> Unit,
        private val stripFails: Boolean,
        private val queueTombstone: Boolean,
    ) : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            if (queueTombstone) {
                executionContext.database.addToArray(
                    ArrayDatabaseReference(
                        arrayId = DataId.RelationDataId.create(
                            context = testProxy.tContext,
                            type = DeleteBlock.ID,
                            relationId = SyncService.Collections.PENDING_TOMBSTONES,
                        ),
                        isEntity = false,
                        elementSchemaId = DeleteBlock.ID,
                    ),
                    DeleteBlock.create(
                        context = testProxy.tContext,
                        blockId = "tomb-self",
                        ownerId = requireNotNull(syncManager.currentOwnerId),
                        hlc = syncManager.hlcManager.tick(),
                        targetEntityIdHash = "1234",
                        targetEntityType = "tenshu/identity/device",
                        revokedDeviceId = syncManager.currentDeviceId,
                    ),
                )
            }
            if (stripFails) onRelayStripFailed()
            return null as T
        }
    }

    private suspend fun run(
        stripFails: Boolean,
        clock: FakeClock,
        queueTombstone: Boolean = false,
        relayRefusesThisDevice: () -> Boolean = { false },
    ): String {
        val impl = ForgetThisDeviceImplementation(
            syncManager = syncManager,
            revokeDevice = { onStripFailed -> FakeRevoke(onStripFailed, stripFails, queueTombstone) },
            relayRefusesThisDevice = relayRefusesThisDevice,
            nowMs = {
                // Advance one poll interval per read, so a wait that runs to its bound shows up as
                // exactly the bound rather than spinning forever.
                clock.now += 150L
                clock.now
            },
        )
        val outcome = impl.execute<TString>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = null,
        )
        return outcome.get().orEmpty()
    }

    /**
     * ⚠ The one that matters. The relay refused the strip, so waiting out a push the *same* relay is
     * unlikely to accept buys nothing and costs every second of it — in front of a user who has
     * already committed to an irreversible action.
     */
    @Test
    fun relayStripFailed_stopsWaiting_andDoesNotClaimALeftoverRow() = runTest {
        val clock = FakeClock()

        val outcome = run(stripFails = true, clock = clock)

        assertTrue(
            outcome.contains("could not confirm"),
            "a refused strip means we never learned whether the tombstone landed; asserting a " +
                "leftover row would send the user hunting for something that may be fine — got: $outcome",
        )
        assertTrue(
            clock.now < 1_000L,
            "the short-circuit must not spend the retirement timeout (spent ${clock.now}ms)",
        )
    }

    /**
     * ⚠ The guard is the **strip result**, not "did it finish quickly". Both branches can return
     * fast, and only the wording separates them — a healthy relay with nothing left to push gets a
     * positive *"removed from your realm"*, never the short-circuit's hedge. Losing that distinction
     * would trade a correct answer for a vague one on every successful removal.
     */
    @Test
    fun stripSucceeded_confirmsRemovalRatherThanShortCircuiting() = runTest {
        val clock = FakeClock()

        val outcome = run(stripFails = false, clock = clock)

        assertTrue(
            outcome.contains("removed from your realm"),
            "a healthy strip with an empty tombstone queue is a CONFIRMED removal — got: $outcome",
        )
        assertTrue(
            !outcome.contains("could not confirm"),
            "the short-circuit must not fire when the relay accepted the strip — got: $outcome",
        )
    }

    /**
     * The bound itself: a healthy strip, a relay that still accepts this device, and a tombstone
     * that never leaves. The wait runs to [ForgetThisDeviceImplementation]'s timeout and then says
     * the row is still listed. This is the control for the refusal cases below: without it, a
     * short-circuit that fired unconditionally would pass them.
     */
    @Test
    fun aTombstoneThatNeverLeaves_runsTheWholeBound_andReportsTheLeftoverRow() = runTest {
        val clock = FakeClock()

        val outcome = run(stripFails = false, clock = clock, queueTombstone = true)

        assertTrue(outcome.contains("still listed"), "the bound reports the leftover row — got: $outcome")
        assertTrue(clock.now >= 8_000L, "nothing refused us, so the whole bound is waited (spent ${clock.now}ms)")
    }

    /**
     * ⚠⚠ The joiner's case. A joiner holds no A1, so the strip is never tried and cannot fail; the
     * only sign that its push is doomed is the scheduler's `401` state. A removed joiner pressing
     * Forget used to wait the full 8 s for a tombstone the relay would never take.
     */
    @Test
    fun relayRefusingThisDevice_stopsWaiting_withoutAStripFailure() = runTest {
        val clock = FakeClock()

        val outcome = run(stripFails = false, clock = clock, queueTombstone = true, relayRefusesThisDevice = { true })

        assertTrue(
            outcome.contains("could not confirm"),
            "refused by the relay, we never learned whether the row is still listed — got: $outcome",
        )
        assertTrue(clock.now < 1_000L, "the refusal must not spend the retirement timeout (spent ${clock.now}ms)")
    }

    /**
     * The refusal can arrive DURING the wait: the scheduler meets the `401` when it tries the very
     * push we are waiting on. The wait must notice on its next poll, not only at its start.
     */
    @Test
    fun aRefusalArrivingMidWait_stopsAtTheNextPoll() = runTest {
        val clock = FakeClock()
        var asks = 0

        val outcome = run(
            stripFails = false,
            clock = clock,
            queueTombstone = true,
            relayRefusesThisDevice = { ++asks > 3 },
        )

        assertTrue(outcome.contains("could not confirm"), "got: $outcome")
        assertTrue(asks == 4, "stopped at the first poll that saw the refusal (asked $asks times)")
        assertTrue(clock.now < 8_000L, "and well inside the bound (spent ${clock.now}ms)")
    }

    /**
     * An emptied queue is a confirmed removal even if the relay refuses this device afterwards: the
     * tombstone left, which is the thing the wait exists to learn. The queue is read first.
     */
    @Test
    fun anEmptyQueueIsAConfirmedRemoval_evenIfTheRelayNowRefusesUs() = runTest {
        val clock = FakeClock()

        val outcome = run(stripFails = false, clock = clock, relayRefusesThisDevice = { true })

        assertTrue(outcome.contains("removed from your realm"), "got: $outcome")
    }
}
