package app.tenshu.control.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recency bound on the tray's headline divergence state
 * ([divergenceIsFresh] / [DIVERGENCE_HEADLINE_WINDOW_MS]) —
 * `sync-hlc-tip-device-identity.md` TC-1 step 3.
 *
 * ⚠⚠ This bounds a **surface**, not the detector. `SyncManager.divergenceWarning` still holds the
 * warning, and `getSyncState` still reports it as `divergenceWarning` past the window — what
 * expires is only its claim on being the *one* thing the tray says. The pairing that makes the
 * bound meaningful lives in `HlcDivergenceTrackerTest.repeatedRegression_keepsTheFirstStamp`:
 * without that, a re-read of the same stale tip would restamp the warning on every poll and
 * nothing here could ever return false.
 */
class DivergenceFreshnessTest {

    private val now = 1_000_000_000L

    @Test
    fun noWarningIsNeverFresh() {
        assertFalse(divergenceIsFresh(observedAtMillis = null, nowMs = now))
    }

    @Test
    fun justSeenIsFresh() {
        assertTrue(divergenceIsFresh(observedAtMillis = now, nowMs = now))
    }

    @Test
    fun insideTheWindowIsFresh() {
        assertTrue(
            divergenceIsFresh(
                observedAtMillis = now - DIVERGENCE_HEADLINE_WINDOW_MS + 1,
                nowMs = now,
            )
        )
    }

    @Test
    fun exactlyAtTheWindowIsStillFresh() {
        // The boundary is inclusive; an off-by-one here would demote a warning a millisecond
        // early, which is harmless but makes the constant mean something other than it says.
        assertTrue(
            divergenceIsFresh(observedAtMillis = now - DIVERGENCE_HEADLINE_WINDOW_MS, nowMs = now)
        )
    }

    @Test
    fun pastTheWindowIsStale() {
        assertFalse(
            divergenceIsFresh(
                observedAtMillis = now - DIVERGENCE_HEADLINE_WINDOW_MS - 1,
                nowMs = now,
            )
        )
    }

    @Test
    fun theObservedCaseAgesOut() {
        // The 2026-08-28 report: an iPhone tip regression first seen at 15:07, still the tray's
        // headline at 15:57 with the device online, idle and caught up. Fifty minutes must not
        // still be breaking news.
        val fiftyMinutes = 50 * 60 * 1000L
        assertFalse(divergenceIsFresh(observedAtMillis = now - fiftyMinutes, nowMs = now))
    }

    @Test
    fun aStampInTheFutureCountsAsFresh() {
        // Wall clock moved backwards between the tracker's stamp and this read. Treating the
        // result as "ancient" would hide a warning raised seconds ago — the failure direction
        // that costs information rather than attention.
        assertTrue(divergenceIsFresh(observedAtMillis = now + 60_000L, nowMs = now))
    }
}
