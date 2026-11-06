package app.tenshu.control.sync

import app.tenshu.control.ui.screens.sync.FakeSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.GenericErrorDto
import org.tenshu.core.common.impl.ResultFailureDto
import org.tenshu.core.common.impl.ResultSuccessDto
import org.tenshu.core.core.TContext
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit test for [OfferPairingMonitor]. Locks the two defects the extraction fixed, both of which
 * were invisible from the VM: an unbounded wait, and a poll that swallowed every failure.
 *
 * ⚠ The happy paths (approved / denied / mint-failure / cancel) are already covered end-to-end by
 * `PairingWizardViewModelTest`'s twelve offer cases, which run against the rewired VM. This file
 * deliberately covers only what those cannot see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfferPairingMonitorTest {

    private val context: TContext = run {
        val registry = createCoreTestRegistry()
        registry.registerConfig(syncServiceCommonModule)
        createTestContext(schemaRegistry = registry)
    }

    private fun session(): OfferPairingSession = OfferPairingSession.create(
        context = context,
        offerUri = "tenshu://offer?k=AAAA&s=BBBB",
        pairingSecretHash = "offer-hash-1",
    )

    /**
     * ⚠⚠ The eternal spinner. Before this, an offerer whose window elapsed with no decision polled
     * forever with nothing on screen. The bound is a deadline, not a failure count — a count would
     * have to answer "how many is too many", and every answer is wrong in one direction.
     */
    @Test
    fun theWindowExpiring_endsTheSession_andCancelsTheOfferProxySide() = runTest {
        val repo = FakeSyncRepository(offerSession = session())
        val monitor = OfferPairingMonitor(repo, pollScope = backgroundScope, teardownScope = backgroundScope)

        monitor.start("Desktop")
        runCurrent()
        assertEquals("tenshu://offer?k=AAAA&s=BBBB", monitor.offerUri.value)

        // Most of the way through the window: still live, still waiting.
        advanceTimeBy((OfferPairingMonitor.OFFER_WINDOW_SECONDS - 1) * 1_000L)
        runCurrent()
        assertFalse(monitor.failed.value, "the session must survive its whole window")

        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(monitor.failed.value, "an elapsed window must end the wait, not extend it")
        assertNull(monitor.offerUri.value, "a dead code must stop being shown")
        assertEquals(1, repo.cancelOfferCalls, "the offer must be cancelled proxy-side, not orphaned")
    }

    /**
     * ⚠⚠ A failing poll used to be `getOrNull() ?: continue` — no error, no end, no signal. It must
     * be visible, and it must NOT be terminal: a blip cannot be allowed to kill a live pairing.
     */
    @Test
    fun aFailingPoll_surfacesConnectionLost_withoutEndingTheSession() = runTest {
        val repo = FakeSyncRepository(offerSession = session())
        repo.offerStatusResult = ResultFailureDto(GenericErrorDto("relay unreachable"))
        val monitor = OfferPairingMonitor(repo, pollScope = backgroundScope, teardownScope = backgroundScope)

        monitor.start("Desktop")
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()

        assertTrue(monitor.connectionLost.value, "a failed poll must be visible")
        assertFalse(monitor.failed.value, "a failed poll must NOT be terminal")
        assertFalse(monitor.paired.value)
        assertFalse(monitor.denied.value)
    }

    /** A recovered relay clears the banner — the shape [HostPairingMonitor] already uses. */
    @Test
    fun aCleanPollAfterAFailure_clearsConnectionLost_andStillPairs() = runTest {
        val repo = FakeSyncRepository(offerSession = session())
        repo.offerStatusResult = ResultFailureDto(GenericErrorDto("relay unreachable"))
        val monitor = OfferPairingMonitor(repo, pollScope = backgroundScope, teardownScope = backgroundScope)

        monitor.start("Desktop")
        runCurrent()
        advanceTimeBy(2_100L)
        runCurrent()
        assertTrue(monitor.connectionLost.value)

        repo.offerStatusResult =
            ResultSuccessDto(OfferStatus.create(context, OfferStatus.APPROVED, sas = "123456"))
        advanceTimeBy(2_100L)
        runCurrent()

        assertFalse(monitor.connectionLost.value, "a clean poll must clear the banner")
        assertTrue(monitor.paired.value, "a blip must not have cost the pairing")
        assertEquals("123456", monitor.sas.value)
    }

    /** Re-entering Show must not orphan the code already on screen. */
    @Test
    fun startIsIdempotentWhileASessionIsLive() = runTest {
        val repo = FakeSyncRepository(offerSession = session())
        val monitor = OfferPairingMonitor(repo, pollScope = backgroundScope, teardownScope = backgroundScope)

        monitor.start("Desktop")
        runCurrent()
        monitor.start("Desktop")
        monitor.start("Desktop")
        runCurrent()

        assertEquals(1, repo.offerPairingCalls, "re-entering Show must re-adopt, not mint a second offer")
    }

    /**
     * ⚠⚠ The structural guarantee, and the one nothing proved before the scopes were split.
     *
     * The poll must end because its **scope** ended, not because someone remembered to call
     * [OfferPairingMonitor.cancel]. While the monitor took a single long-lived scope, an early
     * return added to `onCleared` would have left it polling against a dead screen with no test
     * and no compiler to notice. Nothing here calls `cancel()`.
     */
    @Test
    fun cancellingThePollScope_endsTheWork_withoutAnyoneCallingCancel() = runTest {
        val repo = FakeSyncRepository(offerSession = session())
        val pollScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val monitor = OfferPairingMonitor(
            repo,
            pollScope = pollScope,
            teardownScope = backgroundScope,
        )

        monitor.start("Desktop")
        runCurrent()
        assertEquals("tenshu://offer?k=AAAA&s=BBBB", monitor.offerUri.value)

        // The screen goes away. Only the scope ends — no cancel() call.
        pollScope.cancel()

        // An approval the poll would have seen, had it still been running.
        repo.offerStatusResult =
            ResultSuccessDto(OfferStatus.create(context, OfferStatus.APPROVED, sas = "123456"))
        advanceTimeBy(10_000L)
        runCurrent()

        assertFalse(monitor.paired.value, "a dead poll scope must end the poll; it kept polling")
    }
}
