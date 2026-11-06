package org.tenshu.routine.common.engine

/**
 * One firing in flight (`routines-engine.md` §4.5).
 *
 * [chain] is the list of routine ids that produced this firing, root first — it
 * is what makes a cycle *nameable* rather than merely throttled.
 */
class Firing(
    val id: String,
    val routineId: String,
    val triggerInstantEpochMs: Long,
    val chainRootFiringId: String,
    val depth: Int,
    val chain: List<String>,
    val wasFallback: Boolean = false,
)

/**
 * ⚠ Each refusal is a **named variant**, not a boolean. RE-D8/RE-D9 require the
 * reason to reach Home's Activity feed: a cycle the user authored is an
 * authoring mistake they should be *told about*, not something silently dropped.
 */
sealed interface Admission {
    data object Admit : Admission
    class Throttled(val retryAfterMs: Long) : Admission
    class Suspended(val reason: String) : Admission
    class CycleRefused(val chain: List<String>) : Admission
    class DepthExceeded(val depth: Int) : Admission
}

interface SafetyGate {
    fun admit(firing: Firing, nowEpochMs: Long): Admission

    /**
     * True when this routine's throttling has been sustained long enough that
     * the engine should suspend it (RE-D9).
     *
     * ⚠⚠ Suspension must be written as `Routine.state = SUSPENDED` with a
     * reason — **never** as `enabled = false`, which is indistinguishable from
     * the user's own toggle, silently destroys their intent, and leaves them
     * re-enabling a routine with no idea why it stopped.
     */
    fun shouldSuspend(routineId: String): Boolean

    /**
     * Whether a refusal for this routine should write its own `RoutineRun` row,
     * or coalesce into the window's existing one.
     *
     * ⚠⚠ Load-bearing: RE-D9's token bucket is also a **sync** brake, and if
     * every refused firing wrote its own row the brake would only change a state
     * field — a flapping sensor would flood the realm with `THROTTLED` rows
     * instead of `RAN` rows. The first refusal in a window writes one row and
     * the rest increment `RoutineRun.coalescedCount`, so the row rate is bounded
     * by *windows*, not by firings (§4.9). §6's runaway test asserts exactly
     * this, so a naive implementation fails it.
     */
    fun isFirstRefusalInWindow(routineId: String): Boolean

    /** The `firingId` of the row refusals are coalescing into, if any. */
    fun openRefusalRowFor(routineId: String): String?

    /** Records the row a window's refusals coalesce into. */
    fun recordRefusalRow(routineId: String, firingId: String)
}

/**
 * The default rails: a per-routine token bucket, a depth guard, a cycle guard,
 * and a flap detector that escalates to suspension.
 *
 * ⭐ The cycle check is **per firing, not a global rate limit**, because two
 * mutually-referencing routines are an authoring mistake the user should be told
 * about. The throttle catches the loops that are not syntactically visible.
 *
 * Pure apart from the `nowEpochMs` it is handed — no clock of its own, so the
 * §6 boundary tables are testable with a fake one.
 */
class DefaultSafetyGate(
    private val maxFiringsPerWindow: Int = MAX_FIRINGS_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
    private val maxChainDepth: Int = MAX_CHAIN_DEPTH,
    private val throttledWindowsBeforeSuspend: Int = THROTTLED_WINDOWS_BEFORE_SUSPEND,
    private val suspendedRoutineIds: MutableSet<String> = mutableSetOf(),
) : SafetyGate {

    private class Bucket(
        var windowStartMs: Long,
        var count: Int = 0,
        var consecutiveThrottledWindows: Int = 0,
        var refusalRowFiringId: String? = null,
    )

    private val buckets = mutableMapOf<String, Bucket>()

    override fun admit(firing: Firing, nowEpochMs: Long): Admission {
        if (firing.routineId in suspendedRoutineIds) {
            return Admission.Suspended("runaway: throttled in $throttledWindowsBeforeSuspend consecutive windows")
        }
        if (firing.depth > maxChainDepth) return Admission.DepthExceeded(firing.depth)
        // The chain holds every routine that led here; seeing this routine again
        // means the author wired a loop.
        if (firing.chain.dropLast(1).contains(firing.routineId)) {
            return Admission.CycleRefused(firing.chain)
        }

        val bucket = buckets.getOrPut(firing.routineId) { Bucket(windowStartMs = nowEpochMs) }
        if (nowEpochMs - bucket.windowStartMs >= windowMs) {
            // A window that closed without throttling breaks the escalation streak.
            if (bucket.count <= maxFiringsPerWindow) bucket.consecutiveThrottledWindows = 0
            bucket.windowStartMs = nowEpochMs
            bucket.count = 0
            bucket.refusalRowFiringId = null
        }

        bucket.count++
        if (bucket.count > maxFiringsPerWindow) {
            if (bucket.count == maxFiringsPerWindow + 1) {
                bucket.consecutiveThrottledWindows++
                if (bucket.consecutiveThrottledWindows >= throttledWindowsBeforeSuspend) {
                    suspendedRoutineIds += firing.routineId
                }
            }
            val retryAfter = (bucket.windowStartMs + windowMs - nowEpochMs).coerceAtLeast(0)
            return Admission.Throttled(retryAfter)
        }
        return Admission.Admit
    }

    override fun shouldSuspend(routineId: String): Boolean = routineId in suspendedRoutineIds

    override fun isFirstRefusalInWindow(routineId: String): Boolean =
        buckets[routineId]?.refusalRowFiringId == null

    override fun openRefusalRowFor(routineId: String): String? =
        buckets[routineId]?.refusalRowFiringId

    override fun recordRefusalRow(routineId: String, firingId: String) {
        buckets[routineId]?.refusalRowFiringId = firingId
    }

    companion object {
        /** RE-D9: 10 firings per rolling 10 minutes. */
        const val MAX_FIRINGS_PER_WINDOW = 10
        const val WINDOW_MS = 10L * 60 * 1000

        /** RE-D8 recursion guard. */
        const val MAX_CHAIN_DEPTH = 4

        /** RE-D9 escalation: throttled in this many consecutive windows ⇒ suspend. */
        const val THROTTLED_WINDOWS_BEFORE_SUSPEND = 3

        /**
         * RE-D6: fire if the engine woke within this of the trigger instant,
         * else record `MISSED`.
         *
         * ⚠ `DeviceCommand.STALE_WINDOW_MS` (2 min) does **not** bound this.
         * `IssueDeviceCommand` stamps `issuedAt` at *issue* time, so a routine
         * firing 14 minutes late issues a perfectly fresh command that the hub
         * happily executes. That window guards sync-backlog replay of the
         * command row; this one guards *the engine deciding to act late*, and it
         * is the only thing standing between a suspended laptop and a 3 a.m.
         * porch light.
         */
        const val CATCHUP_WINDOW_MS = 15L * 60 * 1000

        /**
         * RE-D2: how long a non-primary eligible device waits before firing when
         * no receipt has arrived. Sized to exceed one full sync round trip on
         * the 30 s default cadence, so the happy path never double-fires.
         */
        const val GRACE_MS = 90L * 1000
    }
}
