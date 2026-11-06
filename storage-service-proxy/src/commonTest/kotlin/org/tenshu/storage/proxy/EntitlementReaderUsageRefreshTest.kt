package org.tenshu.storage.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.identity.Entitlement
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **The storage gauge could not move while Control ran.**
 *
 * `GET /sync/usage` was reachable from exactly one place — `refreshFigures` — and nothing calls
 * that when bytes change hands. Its triggers are an [Entitlement] save, an owner transition, and
 * the not-yet-valid retry; an upload writes none of them, a reclamation writes none of them, and a
 * peer doing either writes nothing locally at all. So the fill level was fetched once at proxy
 * start and then frozen for the life of the process.
 *
 * ⚠⚠ That made the reclamation track untestable from the UI it is measured in: freeing bytes on the
 * relay left the gauge reading whatever it read at launch, so "5 GB of 5 GB" survived a delete that
 * had genuinely worked, and only a restart told the truth. The same staleness hid uploads — the
 * gauge never grew either.
 */
class EntitlementReaderUsageRefreshTest {

    private class UsageFake(initial: Pair<Long, Long?>?) {
        var answer: Pair<Long, Long?>? = initial
        var calls = 0
        val reader: suspend () -> Pair<Long, Long?>? = {
            calls++
            answer
        }
    }

    private fun TestProxy.seedValidEntitlement(owner: String) {
        executionContext.saveData(
            Entitlement.create(
                context = tContext,
                ownerId = owner,
                publisherId = "tenshu.dev",
                productId = EntitlementProducts.PREMIUM,
                validFrom = 0L,
                validUntil = 9_000_000_000L,
                publisherSignature = ByteArray(0),
            ),
        )
    }

    private fun newProxy(): TestProxy = TestProxy(
        schemaRegistry = createSyntheticTestRegistry().also {
            it.registerConfig(identityServiceCommonModule)
        },
    )

    /**
     * The regression itself: usage falls on the relay, nothing is saved, the owner never changes —
     * and the gauge must still catch up.
     */
    @Test
    fun usageThatChangesWithNoSaveAndNoOwnerChange_stillReachesTheGauge() = runTest {
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val usage = UsageFake(5_000L to 5_000L)
        var nowMs = 1_000L

        proxy.seedValidEntitlement("owner-x")
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-x" },
            now = { nowMs },
            scope = backgroundScope,
            usageReader = usage.reader,
        )
        runCurrent()
        assertEquals(
            5_000L,
            provider.observe().first().usedBytes,
            "precondition: the seed fetch reports the relay's figure",
        )

        // A delete lands on the relay and frees bytes. ⚠ Deliberately nothing else happens: no
        // entitlement write, no lock/unlock, no owner transition. This is the whole state of a
        // Control that has been sitting open while the user deleted a file.
        usage.answer = 1_200L to 5_000L
        nowMs += USAGE_REFRESH_INTERVAL_TEST_MS + 1_000L
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            1_200L,
            provider.observe().first().usedBytes,
            "reclaimed bytes must show up without restarting the proxy",
        )
        assertEquals(
            2,
            usage.calls,
            "and exactly once per interval — the 1s backstop tick must not become a 1s network poll",
        )
    }

    /** The interval is a bound, not a cadence: no time passing means no extra traffic. */
    @Test
    fun withinTheInterval_itDoesNotRefetch() = runTest {
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val usage = UsageFake(5_000L to 5_000L)
        var nowMs = 1_000L

        proxy.seedValidEntitlement("owner-x")
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-x" },
            now = { nowMs },
            scope = backgroundScope,
            usageReader = usage.reader,
        )
        runCurrent()

        // Half an interval of wall clock, and plenty of backstop ticks.
        nowMs += USAGE_REFRESH_INTERVAL_TEST_MS / 2
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, usage.calls, "the seed fetch only")
    }

    /**
     * ⚠ No entitlement ⇒ no fetch. A device that is not subscribed has nothing to render a fill
     * level against, and must not spend a request per interval discovering that.
     */
    @Test
    fun withNoEntitlement_itNeverQueriesUsage() = runTest {
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val usage = UsageFake(5_000L to 5_000L)
        var nowMs = 1_000L

        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-x" },
            now = { nowMs },
            scope = backgroundScope,
            usageReader = usage.reader,
        )
        runCurrent()

        nowMs += USAGE_REFRESH_INTERVAL_TEST_MS * 3
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(0, usage.calls, "nothing to fill against, nothing to ask")
    }

    /**
     * ⚠⚠ The pile-up guard. A fetch against an unreachable relay can hang for as long as the
     * transport allows; without the in-flight flag every interval that elapses meanwhile queues
     * another, so a device that loses connectivity for an hour comes back with sixty stacked
     * requests. Deleting the guard makes this test fail — the second interval starts a second call.
     */
    @Test
    fun aHungFetch_doesNotStackAnotherEachInterval() = runTest {
        val proxy = newProxy()
        val provider = EntitlementStorageProvider()
        val gate = CompletableDeferred<Pair<Long, Long?>?>()
        var calls = 0
        var nowMs = 1_000L

        proxy.seedValidEntitlement("owner-x")
        proxy.executionContext.initEntitlementStorageReader(
            provider = provider,
            ownerId = { "owner-x" },
            now = { nowMs },
            scope = backgroundScope,
            usageReader = {
                calls++
                gate.await()
            },
        )
        runCurrent()
        assertEquals(1, calls, "precondition: the seed fetch is in flight and has not answered")

        // Two whole intervals pass with the relay still not answering.
        repeat(2) {
            nowMs += USAGE_REFRESH_INTERVAL_TEST_MS + 1_000L
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(1, calls, "an unanswered fetch must not accumulate one more per interval")

        // Once it answers, the next interval is free to ask again.
        gate.complete(1_200L to 5_000L)
        runCurrent()
        nowMs += USAGE_REFRESH_INTERVAL_TEST_MS + 1_000L
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, calls, "and the guard releases rather than latching")
    }
}

/**
 * ⚠ A local mirror of the production interval, deliberately **not** the constant itself: the
 * constant is private to the reader, and a test that imported it would keep passing if the interval
 * were changed to a day. This value is what the tests assert the behaviour *at*; if it drifts from
 * the production one the timing assertions above fail, which is the intended alarm.
 */
private const val USAGE_REFRESH_INTERVAL_TEST_MS = 60_000L
