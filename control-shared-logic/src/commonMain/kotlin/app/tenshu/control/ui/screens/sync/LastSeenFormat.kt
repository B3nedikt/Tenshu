package app.tenshu.control.ui.screens.sync

/**
 * How the roster renders a device's last-seen observation.
 *
 * Shared rather than written twice because Compose and the SwiftUI roster must not disagree about
 * what "quiet" looks like — the whole point of the line is comparing two rows against each other,
 * and a device reading `3d ago` on one platform and `Last seen 3 days ago` on another undermines
 * exactly that comparison.
 *
 * ⚠ Buckets are coarse on purpose. The underlying figure is the **author's** wall clock (read off
 * the block HLC), so it carries their clock skew; rendering it to the minute would imply a
 * precision the number does not have. The question this answers is "has this device been quiet for
 * days?", not "when exactly".
 */
fun formatLastSeen(lastSeenMs: Long?, nowMs: Long): String {
    // ⚠ Absence is NOT "long ago": a device can be perfectly live and simply have written nothing
    // since this replica started observing. Saying "never" would read as a verdict on the device.
    if (lastSeenMs == null || lastSeenMs <= 0L) return "No activity seen yet"

    val deltaMs = (nowMs - lastSeenMs).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days >= 30 -> "Last seen over a month ago"
        days >= 1 -> "Last seen $days ${if (days == 1L) "day" else "days"} ago"
        hours >= 1 -> "Last seen $hours ${if (hours == 1L) "hour" else "hours"} ago"
        minutes >= 1 -> "Last seen $minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
        else -> "Active just now"
    }
}
