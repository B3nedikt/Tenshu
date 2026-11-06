package app.tenshu.sdk.internal

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.calendar.RefreshSubscriptions
import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.calendar.proxy.EventMerger
import org.tenshu.calendar.proxy.ReminderStateMerger
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
import org.tenshu.calendar.proxy.initCalendarService
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Calendar-service plugin: the op surface behind `@tenshu/sdk/calendar` (`Calendar.kt`,
 * `web-app-suite.md` Wave 2, run A). It replaces calendar's F7 sync-only entry in
 * `SyncedServicePlugins.kt`, with the same schema and the same two mergers, plus the ops and the
 * relation maintainer.
 *
 * ⚠⚠ **A tab runs no calendar background work** (`calendar-app.md` §Web scope W1/W2):
 *  - [operationFactory] maps **neither** [DeliverDueReminders] (it fires a notification and
 *    re-arms the next wake through `Schedule`) **nor** [RefreshSubscriptions] (it fetches feeds
 *    from third parties). Both answer `null` here, so a dispatch of either fails as "no
 *    implementation"; it never runs half-wired against a no-op presenter or fetcher.
 *  - [initialize] calls `initCalendarService()` **with no factory**. That installs only the
 *    generic `RelationMaintainer` (so `CalendarService.events` & co. are readable; without it every
 *    read is empty). The factory argument is what would add `ReminderRearmObserver`, which arms
 *    reminders on every event change, and the Snooze/Dismiss action handler. Neither runs here.
 *  - Nothing registers `registerSubscriptionRefreshSchedule`, so there is no refresh timer.
 *  `CalendarWebTest.subscriptions_areRowsOnly_…` dispatches both excluded ops against the real
 *  runtime and requires the "no implementation" failure; `theExcludedOps_haveNoImplementation_…`
 *  checks no factory in the chain (core, this plugin, any other) maps either id.
 *
 * ⚠ Ordered **before** `SyncPlugin`, like the F7 entry it replaces: the mergers ride the
 * [JsRuntimePlugin.mergers] seam, and the factory never dials the relay, so it belongs in the
 * pre-sync arms of the dispatch chain.
 */
internal object CalendarPlugin : JsRuntimePlugin {
    override val id: String = "calendar"

    override val schemaConfigs =
        listOf(calendarServiceCommonModule, calendarServiceClientModule)

    // CA-2j: the union of a series' deleted occurrences, and a dismissal a concurrent snooze
    // cannot undo.
    override val mergers = listOf(EventMerger(), ReminderStateMerger())

    override fun operationFactory(ctx: RuntimeContext) =
        // ⚠ The emitter is read per dispatch: `SyncPlugin.initialize` publishes it. `null` (no realm
        // yet) makes *Stop syncing* device-local, the factory's documented posture.
        OperationImplementationFactory { id -> calendarWebOperation(id, ctx.tombstoneEmitterHolder.emitter) }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        // ⚠ No `implementationFactory`: see the KDoc. The maintainer is the whole of it.
        executionContext.initCalendarService()
    }

    /** The op ids a browser must never run: background work, not data (see the KDoc). */
    internal val EXCLUDED_OPS: Set<String> = setOf(DeliverDueReminders.ID, RefreshSubscriptions.ID)
}

internal fun calendarWebOperation(id: String, tombstoneEmitter: TombstoneEmitter? = null) =
    if (id in CalendarPlugin.EXCLUDED_OPS) null
    else calendarServiceProxyImplementationFactory(id, tombstoneEmitter = tombstoneEmitter)
