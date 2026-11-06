package org.tenshu.sync.proxy

import org.tenshu.sync.ScheduleItem

/**
 * Layer-3 OS-woken scheduler (`docs/proposals/core-background-processing.md`
 * §5.1 / §8 Phase 6-7). The second driver of the **same** persisted
 * `ScheduleItem` registry the in-process [ScheduleDrainLoop] already drains —
 * "one store, two drivers":
 *
 *  - [ScheduleDrainLoop] fires while the proxy process is alive.
 *  - a `PlatformScheduler` hands the OS the cadence + constraints so the work
 *    can (re)start when the app is **closed** (Android WorkManager wakes a bound
 *    Control; the desktop daemon is always resident; iOS `BGTaskScheduler`).
 *
 * Bound per platform through **Koin**, not an `expect class` (the amended §8
 * Phase 6 decision): the interface lives here, and each platform's
 * `PlatformProxyModule` binds its actual — mirroring how `NetworkStateProvider`
 * is wired. Desktop/iOS bind [NoOpPlatformScheduler] (the resident process's
 * in-process loop already covers them); Android binds `WorkManagerScheduler`.
 *
 * `register`/`cancel` are called from the `ScheduleService` write path
 * (`ScheduleImplementation` / `CancelScheduledImplementation`) so the OS
 * schedule tracks the persisted registry. They are **advisory**: a platform
 * that ignores them loses only app-closed wakeups, never in-process draining.
 */
interface PlatformScheduler {

    /**
     * Hand [item]'s schedule + constraints to the OS scheduler, keyed on its
     * `dedupKey` so re-registering the same key replaces (never duplicates) the
     * OS-level work. Called after the item is persisted.
     *
     * @param punctual the op carries the `Punctual` trait (RE-D5) and must land
     *   at an instant rather than eventually. ⚖️ Resolved by the CALLER rather
     *   than here: `ScheduleImplementation` already reads the op's schema for
     *   the fail-closed `HeadlessSafe` check, and it is the only place with a
     *   schema provider — a platform binding has none.
     */
    fun register(item: ScheduleItem, punctual: Boolean = false)

    /**
     * Whether this scheduler is [item]'s **only** driver, so the in-process [ScheduleDrainLoop] must
     * not dispatch it (`control-background-work-off-fgs.md` §5.6).
     *
     * ⚠⚠ The answer is a function of the item alone — never of whether a [register] call happened in
     * this process — because the loop reads persisted rows from its first tick, before this process's
     * registrations have run. A registration that fails for an owned item must therefore not leave its
     * row behind: nothing would ever run it (`ScheduleImplementation`).
     *
     * ⚠ [punctual] is resolved by the caller from the op's schema, exactly as for [register]: a
     * punctual one-shot is the alarm's, and the loop stays its fallback.
     *
     * Default `false`: desktop, iOS, the hub and tests keep the in-process loop as their driver.
     */
    fun ownsDispatch(item: ScheduleItem, punctual: Boolean = false): Boolean = false

    /** Tear down any OS-level work registered under [dedupKey]. No-op if none. */
    fun cancel(dedupKey: String)

    /** Tear down all OS-level work this scheduler registered. */
    fun cancelAll()
}

/**
 * The default binding for platforms whose host process is already resident while
 * scheduling matters (desktop daemon, iOS in-process, and all test/JS wirings):
 * the in-process [ScheduleDrainLoop] drains the registry, so no OS hand-off is
 * needed. Register/cancel are no-ops.
 */
object NoOpPlatformScheduler : PlatformScheduler {
    override fun register(item: ScheduleItem, punctual: Boolean) {}
    override fun cancel(dedupKey: String) {}
    override fun cancelAll() {}
}
