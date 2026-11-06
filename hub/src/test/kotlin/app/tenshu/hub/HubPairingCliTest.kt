package app.tenshu.hub

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.core.TContext
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * The session deadline: [HubPairingCli.timeout] must bound a whole offer session, not just the
 * gaps between polls.
 *
 * ⚠⚠ Every case here runs on `runTest`'s virtual clock, so a `45.seconds` budget costs no wall
 * time — and that is only possible because the deadline is `withTimeout`. The wall-clock
 * `TimeSource.Monotonic` it replaced would have spun this loop millions of times instead.
 * `docs/tech-debt.md` § "Two 30-second budgets".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubPairingCliTest {

    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    private fun session() = OfferPairingSession.create(
        context = context,
        offerUri = "tenshu://offer?k=AAAA&s=BBBB",
        pairingSecretHash = "hash-1",
    )

    private fun status(state: String, sas: String = "") =
        OfferStatus.create(context, state, sas = sas)

    /** A relay that answers arming, then whatever [poll] says. */
    private inner class FakeOps(
        val arm: suspend () -> TResult<OfferPairingSession> = { ResultSuccessDto(session()) },
        val poll: suspend (Int) -> TResult<OfferStatus>,
    ) : PairingOps {
        var polls = 0
            private set

        override suspend fun offerPairing(deviceName: String) = arm()
        override suspend fun offerHubPairing(printedUri: String, deviceName: String) = arm()
        override suspend fun getOfferStatus(pairingSecretHash: String): TResult<OfferStatus> {
            polls++
            return poll(polls)
        }
    }

    private fun lines() = Collections.synchronizedList(mutableListOf<String>())

    private fun cli(ops: PairingOps, out: MutableList<String>) = HubPairingCli(
        ops = ops,
        out = { out += it },
        pollInterval = 200.milliseconds,
        timeout = 45.seconds,
    )

    @Test
    fun `an arming call that never answers is bounded, and says nothing was posted`() = runTest {
        val out = lines()
        val ops = FakeOps(arm = { awaitCancellation() }, poll = { error("never polled") })

        val outcome = cli(ops, out).runOnce("Hub")

        assertEquals(PairingOutcome.TimedOut, outcome)
        assertEquals(0, ops.polls, "the poll loop must never be reached")
        assertTrue(
            out.any { it.startsWith("[timeout]") && it.contains("did not arm") },
            "expected the arming-side timeout line, got: $out",
        )
    }

    @Test
    fun `a poll that never returns is bounded mid-call`() = runTest {
        val out = lines()
        val ops = FakeOps(poll = { awaitCancellation() })

        val outcome = cli(ops, out).runOnce("Hub")

        assertEquals(PairingOutcome.TimedOut, outcome)
        assertEquals(1, ops.polls, "the first poll wedges; there is no second")
        assertTrue(
            out.any { it.startsWith("[timeout]") && it.contains("No approval") },
            "expected the approval-side timeout line, got: $out",
        )
    }

    @Test
    fun `a relay that stays pending is bounded by the deadline`() = runTest {
        val out = lines()
        val ops = FakeOps(poll = { ResultSuccessDto(status(OfferStatus.PENDING)) })

        assertEquals(PairingOutcome.TimedOut, cli(ops, out).runOnce("Hub"))
        assertTrue(ops.polls > 1, "a pending relay is polled repeatedly, got ${ops.polls}")
    }

    @Test
    fun `runHubOnce is bounded the same way`() = runTest {
        val out = lines()
        val ops = FakeOps(arm = { awaitCancellation() }, poll = { error("never polled") })

        assertEquals(PairingOutcome.TimedOut, cli(ops, out).runHubOnce("tenshu://hub", "Hub"))
    }

    @Test
    fun `approval still resolves, and does not wait out the deadline`() = runTest {
        val out = lines()
        val ops = FakeOps(
            poll = { n ->
                if (n < 3) ResultSuccessDto(status(OfferStatus.PENDING))
                else ResultSuccessDto(status(OfferStatus.APPROVED, sas = "123456"))
            },
        )

        val outcome = cli(ops, out).runOnce("Hub")

        assertEquals(PairingOutcome.Approved("123456"), outcome)
        assertTrue(out.none { it.startsWith("[timeout]") }, "no timeout line on approval: $out")
        assertTrue(
            out.any { it.contains("Confirmation code") && it.contains("123456") },
            "the H1 flow shows the SAS, got: $out",
        )
    }

    @Test
    fun `a denial is terminal`() = runTest {
        val out = lines()
        val ops = FakeOps(poll = { ResultSuccessDto(status(OfferStatus.DENIED)) })

        assertEquals(PairingOutcome.Denied, cli(ops, out).runOnce("Hub"))
    }

    @Test
    fun `a transient poll failure keeps polling inside the budget`() = runTest {
        val out = lines()
        val ops = FakeOps(
            poll = { n ->
                if (n < 3) ResultFailureDto(GenericError.create(context, "relay blip"))
                else ResultSuccessDto(status(OfferStatus.APPROVED, sas = "654321"))
            },
        )

        assertEquals(PairingOutcome.Approved("654321"), cli(ops, out).runOnce("Hub"))
        assertEquals(3, ops.polls)
    }

    @Test
    fun `anOuterDeadlineIsNotReportedAsOurs`() = runTest {
        val out = lines()
        val ops = FakeOps(poll = { awaitCancellation() })

        // The CLI's own budget is 45 s; this caller allows 5 s. The expiry belongs to the
        // caller, so it must propagate — NOT be reported as this session timing out.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5.seconds) { cli(ops, out).runOnce("Hub") }
        }
        assertTrue(
            out.none { it.startsWith("[timeout]") },
            "the CLI must not claim someone else's deadline as its own; markers=" +
                "${out.filter { it.startsWith("[") }}",
        )
    }

    @Test
    fun `an arming failure is reported, not retried`() = runTest {
        val out = lines()
        val ops = FakeOps(
            arm = { ResultFailureDto(GenericError.create(context, "relay down")) },
            poll = { error("never polled") },
        )

        val outcome = cli(ops, out).runOnce("Hub")

        assertTrue(outcome is PairingOutcome.Failed, "expected Failed, got $outcome")
        assertEquals(0, ops.polls)
    }
}
