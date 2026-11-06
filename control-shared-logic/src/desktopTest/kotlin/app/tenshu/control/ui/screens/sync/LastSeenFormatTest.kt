package app.tenshu.control.ui.screens.sync

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The roster's last-seen line. Its job is to make a device that has gone quiet **visibly different**
 * from one that has not — the fact that was missing when an orphaned `Device` row showed up as a
 * second, identically-named "iPhone" (`tech-debt.md`).
 */
class LastSeenFormatTest {

    private val now = 1_700_000_000_000L

    @Test
    fun absent_readsAsNoObservation_notAsLongAgo() {
        // ⚠ The distinction the whole line rests on: we have not heard from it *here*, which is
        // equally true of a quiet peer and of a dead identity. Rendering absence as "never" or as
        // an enormous age would state a verdict the data does not support.
        assertEquals("No activity seen yet", formatLastSeen(null, now))
        assertEquals("No activity seen yet", formatLastSeen(0L, now))
    }

    @Test
    fun bucketsReadAsAgesNotTimestamps() {
        assertEquals("Active just now", formatLastSeen(now - 30_000, now))
        assertEquals("Last seen 1 minute ago", formatLastSeen(now - 60_000, now))
        assertEquals("Last seen 5 minutes ago", formatLastSeen(now - 5 * 60_000, now))
        assertEquals("Last seen 1 hour ago", formatLastSeen(now - 3_600_000, now))
        assertEquals("Last seen 3 days ago", formatLastSeen(now - 3 * 86_400_000L, now))
        assertEquals("Last seen over a month ago", formatLastSeen(now - 400 * 86_400_000L, now))
    }

    @Test
    fun aQuietDeviceIsDistinguishableFromALiveOne() {
        // The property that matters, stated directly rather than via the exact strings above.
        val live = formatLastSeen(now - 1_000, now)
        val quiet = formatLastSeen(now - 90L * 86_400_000L, now)
        val never = formatLastSeen(null, now)
        assertTrue(live != quiet && quiet != never && live != never)
    }

    @Test
    fun aFutureTimestampDoesNotRenderNegatively() {
        // ⚠ Not hypothetical: the figure is the *author's* wall clock, read off the block HLC, so
        // a peer running ahead of us legitimately produces one. "-3 minutes ago" would look like a
        // bug in the roster rather than clock skew on the peer.
        assertEquals("Active just now", formatLastSeen(now + 600_000, now))
    }
}
