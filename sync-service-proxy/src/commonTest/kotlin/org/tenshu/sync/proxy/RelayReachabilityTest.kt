package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reported online/offline verdict (`sync-metered-plane-split.md` §8 follow-up). Each test
 * names one of the two ways the raw last-attempt flag was wrong, plus the case that must keep
 * working.
 */
class RelayReachabilityTest {

    private var now = 1_000_000L
    private fun subject() = RelayReachability { now }

    @Test
    fun neverAttempted_reportsOnline() {
        assertTrue(
            subject().isOnline(),
            "a device that has not tried yet must not flash Offline before its first relay call",
        )
    }

    @Test
    fun sustainedFailure_reportsOffline() {
        val r = subject()
        r.record(true)
        now += 1_000
        r.record(false)
        assertTrue(r.isOnline(), "one failed tick is not yet offline — it may be a blip")

        now += RelayReachability.CONFIRM_MS
        r.record(false)
        assertFalse(r.isOnline(), "a failure sustained past the grace window IS offline")
    }

    @Test
    fun singleFailureBetweenSuccesses_neverReportsOffline() {
        // The flap. At the hot 1.5s cadence this used to flip the banner to Offline for one tick.
        val r = subject()
        r.record(true)
        now += 1_500
        r.record(false)
        assertTrue(r.isOnline(), "a blip must not surface")
        now += 1_500
        r.record(true)
        assertTrue(r.isOnline(), "…and recovery is immediate")
    }

    @Test
    fun staleFailureStopsCountingOnceEvidenceExpires() {
        // THE on-device bug: a failed attempt, then no attempts at all (pull skipped — idle,
        // unregistered realm, schema fence). The verdict used to stand forever, and after the
        // plane split that masked the "Waiting for Wi-Fi" banner completely.
        val r = subject()
        r.record(true)
        now += 1_000
        r.record(false)
        now += RelayReachability.CONFIRM_MS
        r.record(false)
        assertFalse(r.isOnline(), "sanity: offline while the failure is fresh")

        // No further attempts — the scheduler stopped calling the relay.
        now += RelayReachability.EVIDENCE_TTL_MS + 1
        assertTrue(
            r.isOnline(),
            "expired evidence must stop claiming offline — the device is not known to be offline, " +
                "it simply has not tried lately",
        )
    }

    @Test
    fun activelyFailingDeviceKeepsReportingOffline() {
        // The guard on the fix: evidence expiry must not let a genuinely offline device drift back
        // to "online". It retries every tick, so its evidence is always fresh.
        val r = subject()
        r.record(true)
        repeat(200) {
            now += 1_500
            r.record(false)
        }
        assertFalse(r.isOnline(), "a device failing every tick stays offline indefinitely")
    }

    @Test
    fun neverReachedRelayAndFailing_reportsOfflineImmediately() {
        // No success to measure the grace window from; waiting would be waiting for an event that
        // has never happened.
        val r = subject()
        r.record(false)
        assertFalse(r.isOnline(), "a device that has never reached the relay and is failing is offline")
    }

    @Test
    fun nullOutcomeIsNotEvidence() {
        // A tick that attempted nothing must neither create nor refresh a verdict.
        val r = subject()
        r.record(true)
        now += 1_000
        r.record(false)
        now += RelayReachability.CONFIRM_MS
        r.record(false)
        assertFalse(r.isOnline(), "sanity: offline")

        // Ticks that skip the relay entirely must not keep the offline verdict alive.
        now += RelayReachability.EVIDENCE_TTL_MS + 1
        r.record(null)
        assertTrue(r.isOnline(), "a no-attempt tick does not refresh stale evidence")
    }

    // ── isConfirmedReachable: the FENCE's signal, not the banner's ──────────────────────────────

    @Test
    fun neverAttempted_isNotConfirmedForAFence() {
        // ⚠ The exact opposite of `neverAttempted_reportsOnline` above, and deliberately so. The
        // banner is optimistic so it never flashes; a fence is pessimistic so it never waves a
        // write through on no evidence. Same class, two callers, two correct answers.
        assertFalse(
            subject().isConfirmedReachable(),
            "no attempt yet is not evidence the relay would accept a write",
        )
    }

    @Test
    fun freshAuthorizedAcceptance_isConfirmed() {
        val r = subject()
        r.record(true, authorized = true)
        assertTrue(r.isConfirmedReachable(), "the relay answered and accepted this device")
    }

    @Test
    fun unauthorizedButReachable_isNotConfirmed() {
        // ⚠⚠ THE case this signal exists for. A 401 is booked as `online = true` on purpose — the
        // relay is plainly up, and blaming the user's network is the wrong advice — but every
        // metadata write is being refused. A move let through here creates a holder no peer will
        // ever see, while a peer stays free to delete the old one.
        val r = subject()
        r.record(true, authorized = false)
        assertTrue(r.isOnline(), "the banner must still say online — the relay IS reachable")
        assertFalse(
            r.isConfirmedReachable(),
            "but the fence must not: reachable and able-to-write are different questions",
        )
    }

    @Test
    fun authorizedAcceptanceGoesStale() {
        val r = subject()
        r.record(true, authorized = true)
        now += RelayReachability.FENCE_EVIDENCE_TTL_MS + 1
        assertFalse(
            r.isConfirmedReachable(),
            "an acceptance older than the fence's window is not evidence about now",
        )
        assertTrue(
            r.isOnline(),
            "while the banner, which asks a different question, is unaffected",
        )
    }

    @Test
    fun a401AfterAnAcceptance_revokesConfirmation() {
        // The episode shape: the device was fine, then the relay stopped accepting its identity.
        // Confirmation must drop on the first refused tick, not linger for the acceptance's TTL.
        val r = subject()
        r.record(true, authorized = true)
        now += 1_000
        r.record(true, authorized = false)
        assertFalse(
            r.isConfirmedReachable(),
            "the most recent answer refused this device; the earlier acceptance does not survive it",
        )
    }
}
