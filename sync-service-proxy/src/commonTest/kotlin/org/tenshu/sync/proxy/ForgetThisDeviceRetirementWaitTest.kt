package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.syncCryptoManager
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

    /** Stands in for `RevokeDeviceImplementation`, reporting the strip outcome the same way it does. */
    private class FakeRevoke(
        private val onRelayStripFailed: () -> Unit,
        private val stripFails: Boolean,
    ) : AsyncOperationImplementation {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            if (stripFails) onRelayStripFailed()
            return null as T
        }
    }

    private suspend fun run(stripFails: Boolean, clock: FakeClock): String {
        val impl = ForgetThisDeviceImplementation(
            syncManager = syncManager,
            revokeDevice = { onStripFailed -> FakeRevoke(onStripFailed, stripFails) },
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

    // ⚠ NOT covered here: the 8s timeout branch (healthy strip, a tombstone that never drains).
    // Reaching it needs a populated PENDING_TOMBSTONES, which this harness does not build. That
    // bound is unchanged by this slice — the short-circuit returns before it or not at all.
}
