package org.tenshu.calendar.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.client.calendarServiceClientModule
import org.tenshu.calendar.common.calendarServiceCommonModule
import org.tenshu.calendar.proxy.calendarServiceProxyImplementationFactory
import org.tenshu.calendar.proxy.initCalendarService
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.createCoreTestRegistry
import app.tenshu.notify.NoopNotificationPresenter
import org.tenshu.calendar.proxy.IcsSubscriptionFetcher
import org.tenshu.calendar.proxy.NoopIcsSubscriptionFetcher
import app.tenshu.notify.NotificationPresenter
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory

fun createCalendarTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(calendarServiceCommonModule)
    registerConfig(calendarServiceClientModule)
    // ⚠ The §6.4 headless defaults are `RealmSetting` rows, so a proxy that
    // cannot materialise one cannot resolve a default — the registry needs the
    // preference schema even though Calendar declares no preference op itself.
    registerConfig(preferenceServiceCommonModule)
    // ⚠ The *client* module is what registers the service RO factory; without
    // it `getService(PreferenceService.ID)` dies on a missing request-object
    // factory rather than anything naming the omission.
    registerConfig(preferenceServiceClientModule)
}

/**
 * Single-client fixture for the calendar ops.
 *
 * ⚠ Wires **both** implementation factories: the calendar tests write their
 * defaults through `WriteSetting`, which belongs to `:preference-service`.
 * Dispatch falls through to the preference factory when the calendar one does
 * not recognise the op id.
 */
fun createCalendarProxy(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /**
     * Where a due reminder is shown (CA-5c). ⚠ No-op by default so the ~30
     * tests that predate reminders need no argument, and so a test that *does*
     * care has to say so.
     */
    presenter: NotificationPresenter = NoopNotificationPresenter,
    nowEpochMs: () -> Long = { 0L },
    zoneId: () -> String = { "UTC" },
    /** CA-7's subscription fetch; no-op unless a test supplies a real one. */
    fetcher: IcsSubscriptionFetcher = NoopIcsSubscriptionFetcher,
) =
    TestProxy(
        schemaRegistry = createCalendarTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, _ ->
            calendarServiceProxyImplementationFactory(
                id = id,
                presenter = presenter,
                fetcher = fetcher,
                nowEpochMs = nowEpochMs,
                zoneId = zoneId,
            )
                ?: preferenceServiceProxyImplementationFactory(id)
        },
    ).also {
        // ⚠ No implementation factory passed: the re-arm observer needs the
        // `Schedule` op, which `:sync-service-proxy` owns and this fixture does
        // not wire. Tests drive `deliverDueReminders()` directly, which is the
        // half that can be checked here anyway — the arming half is
        // `AlarmManager`, and no test process can see it.
        it.executionContext.initCalendarService()
    }

/** The `TenshuEnvironment` a test drives the SDK chain through. */
fun environmentFor(proxy: TestProxy): TenshuEnvironment {
    val executor = proxy.localTaskExecutor
    return TenshuEnvironment(
        taskExecutor = executor,
        schemaRegistry = proxy.schemaRegistry,
        requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
        tContext = proxy.tContext,
    )
}

/**
 * Reads a row straight out of the store by key.
 *
 * ⚠ Deliberately bypasses the chain DSL: these assertions are about **what the
 * op wrote**, and going through a query would let a read-path bug mask a
 * write-path one (or the reverse).
 */
fun TestProxy.event(eventId: String): Event? =
    executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = Event.ID,
            keyValue = eventId,
        )
    ) as? Event

fun TestProxy.calendarRow(calendarId: String): Calendar? =
    executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = Calendar.ID,
            keyValue = calendarId,
        )
    ) as? Calendar

fun TestProxy.override(eventId: String, recurrenceId: String): EventOverride? =
    executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = EventOverride.ID,
            keyValue = EventOverride.keyOf(eventId, recurrenceId),
        )
    ) as? EventOverride

/**
 * A [TenshuClient] over a test proxy, for repository-level tests.
 *
 * ⚠ In-process: client and proxy share a `LocalTaskExecutor`, so this exercises
 * the chain DSL and the op dispatch but not the IPC transport. That split is
 * deliberate — the transport is the platform's to prove, and a repository test
 * that needed a socket would be testing the wrong layer.
 */
fun TestProxy.asTenshuClient(): TenshuClient =
    DefaultTenshuClient(environmentFor(this))
