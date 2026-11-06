package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [relayUnreachableForFencing] — the predicate D3's move fence reads
 * (`content-plane-refcount.md` §6d).
 *
 * ⚠⚠ Every case here breaks moves silently if it is wrong, and two of them are opposites, which is
 * why the predicate needs three states rather than a boolean. "Unknown" must fence when a relay
 * exists and must NOT fence when none does; a reachable relay that refuses this device's writes
 * must fence even though the banner says online.
 */
class MoveFenceSignalTest {

    @Test
    fun syncNotConfigured_doesNotFence() {
        // ⚠⚠ A never-funded local-only realm is never registered, so the scheduler "makes no relay
        // request at all" — reachability is therefore unknown for ever, and failing closed on
        // unknown would defer EVERY content-bearing move permanently, with replay re-deferring it
        // each pass. Moving files in local-only mode would simply stop working, silently.
        assertFalse(
            SyncHealthFigures(relaySyncConfigured = false, relayConfirmedReachable = null)
                .relayUnreachableForFencing(),
            "no relay means no peer can delete the old holder — there is nothing to fence against",
        )
        assertFalse(
            SyncHealthFigures(relaySyncConfigured = false, relayConfirmedReachable = false)
                .relayUnreachableForFencing(),
            "and that holds however reachability reads — the question does not apply",
        )
    }

    @Test
    fun configuredButUnknown_fences() {
        // The startup window. `online` defaults optimistically to true, so falling back to it here
        // would report a device that booted with no network as safe, and the move it let through
        // would never be replayable.
        assertTrue(
            SyncHealthFigures(relaySyncConfigured = true, relayConfirmedReachable = null)
                .relayUnreachableForFencing(),
            "a relay exists and we have no evidence about it — fail closed",
        )
    }

    @Test
    fun configuredAndConfirmed_doesNotFence() {
        assertFalse(
            SyncHealthFigures(relaySyncConfigured = true, relayConfirmedReachable = true)
                .relayUnreachableForFencing(),
            "positive, fresh, authorized evidence is the one case a move may proceed on",
        )
    }

    @Test
    fun configuredAndUnreachable_fences() {
        assertTrue(
            SyncHealthFigures(relaySyncConfigured = true, relayConfirmedReachable = false)
                .relayUnreachableForFencing(),
        )
    }

    @Test
    fun beforeAnyTick_fences() {
        // Defaults: nothing has reported, so we do not even know whether a relay exists. Fencing is
        // the safe reading, and it lasts only until the first tick.
        assertTrue(
            SyncHealthFigures().relayUnreachableForFencing(),
            "an unreported holder must not certify a move",
        )
    }

    @Test
    fun theBannerAndTheFenceAreAllowedToDisagree() {
        // ⚠ `online = true` with the fence closed is the NORMAL state during a 401 or 402 episode:
        // the relay is reachable (so the banner must not blame the user's network) while every
        // write is refused (so no move may proceed). A test asserting they agree would be wrong.
        val episode = SyncHealthFigures(
            online = true,
            entitlementBlocked = true,
            relaySyncConfigured = true,
            relayConfirmedReachable = false,
        )
        assertTrue(episode.online, "the banner reports the relay as up, because it is")
        assertTrue(episode.relayUnreachableForFencing(), "and the fence stays shut anyway")
    }

    // ── the write probe's mapping ───────────────────────────────────────────────────────────────

    private class CountingRelay(private val answer: org.tenshu.sync.common.WriteProbe) :
        org.tenshu.sync.common.SyncRelay by MockSyncRelay(org.tenshu.core.common.createTestContext(org.tenshu.core.common.createSyntheticTestRegistry())) {
        var probes = 0
        override suspend fun probeWrite(): org.tenshu.sync.common.WriteProbe { probes++; return answer }
    }

    @Test
    fun aLocalOnlyRealm_isNeverProbed() = kotlinx.coroutines.test.runTest {
        // ⚠⚠ A never-funded realm is never registered, so a probe would be refused and
        // every content-bearing move deferred — with no peer to fence against.
        val relay = CountingRelay(org.tenshu.sync.common.WriteProbe.REFUSED)
        assertEquals(null, relay.confirmWritableForFencing(SyncHealthFigures(relaySyncConfigured = false)))
        assertEquals(0, relay.probes, "no relay is configured, so nothing is asked")
    }

    @Test
    fun aConfiguredRelay_isAsked_andItsAnswerMapped() = kotlinx.coroutines.test.runTest {
        val configured = SyncHealthFigures(relaySyncConfigured = true)
        assertEquals(true, CountingRelay(org.tenshu.sync.common.WriteProbe.ACCEPTED).confirmWritableForFencing(configured))
        assertEquals(false, CountingRelay(org.tenshu.sync.common.WriteProbe.REFUSED).confirmWritableForFencing(configured))
        assertEquals(null, CountingRelay(org.tenshu.sync.common.WriteProbe.UNSUPPORTED).confirmWritableForFencing(configured))
        // Unknown (no tick yet) still asks: a relay may well be configured.
        val unknown = CountingRelay(org.tenshu.sync.common.WriteProbe.REFUSED)
        assertEquals(false, unknown.confirmWritableForFencing(SyncHealthFigures(relaySyncConfigured = null)))
        assertEquals(1, unknown.probes)
    }
}
