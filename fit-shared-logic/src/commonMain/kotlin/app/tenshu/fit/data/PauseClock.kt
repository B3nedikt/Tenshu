package app.tenshu.fit.data

/**
 * The recorder's active-time clock (RC-4, fit-app.md §13.4): elapsed **active** milliseconds
 * over a monotonic time source, with paused spans excluded. Replaces the wall-clock
 * `now − startMs` maths under which pause was cosmetic — paused time silently came back on
 * resume. Callers feed their platform's monotonic now (`SystemClock.elapsedRealtime()` on
 * Android, `NSProcessInfo.systemUptime` on iOS); the clock itself is pure and platform-free.
 *
 * Not thread-safe by itself — engines drive it from their confined capture context.
 */
class PauseClock {

    private var startMs = 0L
    private var pausedAccumMs = 0L
    private var pauseStartedMs: Long? = null

    fun start(nowMs: Long) {
        startMs = nowMs
        pausedAccumMs = 0L
        pauseStartedMs = null
    }

    /**
     * Restart the clock mid-session with [alreadyActiveSec] already on it — how a session killed by
     * the OS picks up where it left off (§13.5e). The monotonic source does not survive a process
     * death (and on Android does not survive a reboot at all), so the offset is carried in the
     * journal as active seconds and re-anchored to *this* process's clock here.
     *
     * ⚠ The dead stretch is not counted: it is neither active nor paused time, it simply never
     * happened as far as the workout is concerned — the same treatment a paused span gets.
     */
    fun resumeFrom(nowMs: Long, alreadyActiveSec: Int) {
        startMs = nowMs - alreadyActiveSec * 1000L
        pausedAccumMs = 0L
        pauseStartedMs = null
    }

    /** Idempotent — a second pause while paused is a no-op. */
    fun pause(nowMs: Long) {
        if (pauseStartedMs == null) pauseStartedMs = nowMs
    }

    /** Idempotent — resume while running is a no-op. */
    fun resume(nowMs: Long) {
        pauseStartedMs?.let {
            pausedAccumMs += nowMs - it
            pauseStartedMs = null
        }
    }

    val paused: Boolean get() = pauseStartedMs != null

    /** Active (un-paused) milliseconds since [start]; frozen while paused. */
    fun activeMs(nowMs: Long): Long {
        val pausedNow = pauseStartedMs?.let { nowMs - it } ?: 0L
        return (nowMs - startMs - pausedAccumMs - pausedNow).coerceAtLeast(0L)
    }

    fun activeSec(nowMs: Long): Int = (activeMs(nowMs) / 1000).toInt()
}
