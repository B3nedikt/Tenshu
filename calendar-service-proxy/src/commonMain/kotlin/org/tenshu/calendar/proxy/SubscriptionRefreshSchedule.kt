package org.tenshu.calendar.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.calendar.RefreshSubscriptions
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/** Stable schedule id; coalesces re-registration on each startup. */
const val SUBSCRIPTION_REFRESH_DEDUP_KEY = "tenshu/calendar/subscriptionRefresh"

/**
 * How often a feed is re-fetched.
 *
 * ⚠⚠ **Six hours, and the number is a privacy decision as much as a freshness
 * one.** Every fetch tells the publisher the user's device is awake and where it
 * is, so the cadence *is* the disclosure rate (threat model A5, CA-7). Holiday
 * feeds and school terms change a handful of times a year; hourly polling would
 * multiply the disclosure twenty-four-fold to learn nothing.
 */
const val SUBSCRIPTION_REFRESH_PERIOD_MS = 6L * 60 * 60 * 1000

/**
 * Registers the periodic subscription refresh (CA-7b).
 *
 * ⚠ **Unmetered only.** A holiday feed is not worth cellular data, and the
 * `OfflineKeeper` precedent settled the same question the same way. A user who
 * wants it *now* has *Refresh* in the UI, which bypasses the constraint by
 * calling the op directly.
 *
 * ⚠ Idempotent: `dedupKey` coalesces, so registering on every startup keeps
 * exactly one item.
 *
 * ⚠⚠ **Registering costs nothing when there are no subscriptions.** The op finds
 * its own work and does nothing with an empty list, so a user who never
 * subscribes never makes a request — which is what lets "creating a subscription
 * is the consent" be true with an always-registered schedule behind it.
 */
suspend fun ExecutionContext.registerSubscriptionRefreshSchedule(
    periodMs: Long = SUBSCRIPTION_REFRESH_PERIOD_MS,
) {
    try {
        scheduleSubscriptionRefresh(periodMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // ⚠⚠ Best-effort by decision, the `registerOfflineKeeperSchedule` shape and
        // for its reason: the call site is a bare `sessionScope.launch { … }` with
        // no exception handler, so anything thrown here reaches the thread's
        // uncaught handler and on Android kills Control. Registering a six-hourly
        // maintenance tick must not be able to take the proxy down.
        //
        // ⚠ Named rather than silent: losing it means subscribed calendars quietly
        // stop updating, which looks like a stale feed rather than a missing
        // schedule.
        println("CalendarSubscriptions -> schedule registration failed, feeds will not refresh: $e")
    }
}

private suspend fun ExecutionContext.scheduleSubscriptionRefresh(periodMs: Long) {
    executeAsync<Data>(
        operationId = Schedule.ID,
        caller = null,
        params = mapOf(
            Schedule.Params.OP_ID to RefreshSubscriptions.ID.toTString(context),
            Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                context = context,
                kind = ScheduleSpec.KIND_PERIODIC,
                minPeriodMs = periodMs,
            ),
            Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(
                context = context,
                network = ConstraintSpec.NETWORK_UNMETERED,
            ),
            Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
            Schedule.Params.DEDUP_KEY to SUBSCRIPTION_REFRESH_DEDUP_KEY.toTString(context),
        ),
    )
}
