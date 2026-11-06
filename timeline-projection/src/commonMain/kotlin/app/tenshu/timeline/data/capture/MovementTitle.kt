package app.tenshu.timeline.data.capture

/**
 * Neutral fallback title, e.g. "Walk · 2.4 km" (ADR 0016: the projector localises later).
 *
 * ⚠ The ONE generator: capture stamps it on a new movement moment (`MovementMapper`), the rail sweep
 * re-stamps it, and the feed projection regenerates it for a collapsed ride — so a collapsed ride and a
 * natively-whole one produce byte-identical titles. Moved here verbatim from `MovementMapper.moveTitle`
 * (which now delegates) because the projection is compiled for JS (`timeline-app.md` §Web scope 4a).
 */
fun movementTitle(mode: String, distanceM: Long): String {
    val label = mode.replaceFirstChar { it.uppercaseChar() }
    return "$label · ${movementDistanceLabel(distanceM)}"
}

private fun movementDistanceLabel(distanceM: Long): String =
    if (distanceM >= 1000) {
        val tenthsKm = (distanceM + 50) / 100 // round to 0.1 km
        "${tenthsKm / 10}.${tenthsKm % 10} km"
    } else {
        "$distanceM m"
    }
