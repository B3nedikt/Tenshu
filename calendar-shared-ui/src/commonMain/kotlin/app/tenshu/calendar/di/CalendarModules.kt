package app.tenshu.calendar.di

import app.tenshu.calendar.data.calendarControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.tenshu.calendar.data.CalendarDeviceIdentity
import app.tenshu.calendar.data.CalendarMirrorRepository
import app.tenshu.calendar.data.CalendarRepository
import app.tenshu.calendar.data.TenshuCalendarRepository
import app.tenshu.calendar.ui.CalendarInteropViewModel
import app.tenshu.calendar.ui.CalendarManagementViewModel
import app.tenshu.calendar.ui.CalendarViewModel
import app.tenshu.calendar.ui.EventEditorViewModel
import app.tenshu.calendar.ui.ExternalCopyViewModel
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get

/**
 * Koin assembly for the Calendar app (CA-2b). Both shells do
 * `startKoin { modules(listOf(calendarClientModule, platformClientModule) + calendarModules()) }`.
 */
fun calendarModules(): List<Module> = listOf(
    calendarRepositoryModule(),
    calendarViewModelsModule(),
)

fun calendarRepositoryModule(): Module = module {
    // The scope the reachability probe and the SK-1/2/3 mirrors live on. ⚠ App-lifetime, not
    // screen-lifetime: the banner has to keep telling the truth while the user is on a screen that
    // never reads anything.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // SK-1b: the seam between the schema announce and the shell banner's words. A single, because
    // the engine reads it and the client module writes it, in whichever order Koin resolves.
    single { MutableSchemaSkewSource() }

    /**
     * Calendar's **one** Control-reachability engine (`ADR 0025`; the fit-app.md §9.14 recipe).
     *
     * ⚠⚠ Until now this existed only on iOS (`CalendarLocalRuntime`), so on Android and desktop an
     * unreachable Control rendered an empty month with **no explanation at all** — the silent-blank
     * failure `:app-reachability` was extracted to prevent. The factory was there; nothing called
     * it, and `PlatformClientModule`'s own comment already said "the reachability banner is what
     * says so" about a banner that did not exist.
     */
    single {
        calendarControlReachability(
            get<TenshuClient>(),
            probeScope = get(),
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            schemaFence = get<MutableSchemaSkewSource>().fence,
            proxyHost = get<MutableSchemaSkewSource>().host,
            // ADR 0025 §Decision 8: Control installed and answering, no realm on this device → the
            // wall. The same two reads as Control's landing router; `get()` throws on a failed read,
            // which the engine reads as "cannot tell".
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired(get<TenshuClient>().let { client ->
                {
                    realmPresence(
                        isLocked = client.syncService.getUnlockStatus().get().isLocked.get(),
                        ownerId = client.syncService.getSyncStatus().get().ownerId?.get(),
                    )
                }
            }),
        )
    }

    // ⚠ One instance behind both interfaces (CX-1b): mirrors read and write the same calendars
    // and events, and two repositories would mean two device-identity resolvers.
    single<CalendarRepository> { get<TenshuCalendarRepository>() }
    single<CalendarMirrorRepository> { get<TenshuCalendarRepository>() }

    single {
        val client = get<TenshuClient>()
        TenshuCalendarRepository(
            client = client,
            reachability = get(),
            // ⚠ A resolver, not a value: sync identity is not up the instant the
            // app starts, and a blank id read once would strand every per-device
            // preference for the session (`CalendarDeviceIdentity`).
            deviceIdentity = CalendarDeviceIdentity {
                client.syncService.getSyncStatus().getOrNull()?.deviceId?.get()
            },
        )
    }
}

/**
 * ⭐⭐ **The one place a clock and a timezone are allowed in, and it is here on
 * purpose.** Every layer beneath — the expander, the resolver, the view model —
 * takes its "now" as a parameter, precisely so that none of them can disagree
 * about what today is. The composition root is where an ambient answer stops
 * being ambient and becomes an argument.
 *
 * ⚠ The device zone is read once per view-model construction, so an app left
 * open across midnight or flown across a timezone keeps yesterday's answer until
 * the screen is recreated. Acceptable for CA-2 and worth fixing when there is a
 * lifecycle hook to hang it on (CA-4).
 */
fun calendarViewModelsModule(): Module = module {
    viewModel {
        val zone = TimeZone.currentSystemDefault()
        CalendarViewModel(
            repository = get(),
            today = Clock.System.now().toLocalDateTime(zone).date,
        )
    }

    // ⚠ No clock and no parameters — the calendars screen has no notion of
    // "now" at all, which is why it is the only view model here with a bare
    // constructor.
    viewModel {
        CalendarManagementViewModel(repository = get(), mirrors = getOrNull(), refresher = getOrNull())
    }

    // ⚠ Also clockless, and deliberately separate from the management VM: import
    // and export are a *flow* with their own busy/result states, and folding them
    // into the list's state would make every calendar row recompose while a file
    // is being written.
    viewModel { CalendarInteropViewModel(repository = get(), mirrors = getOrNull()) }

    // ⚠ The reader is optional: only a platform with a calendar store binds one (Android, in
    // `CalendarApplication`), and its absence is what hides *Copy from this phone* elsewhere.
    viewModel {
        ExternalCopyViewModel(
            repository = get(),
            reader = getOrNull(),
            mirrors = getOrNull(),
            // ⚠ Bound only where a calendar store exists (Android's `CalendarApplication`); its
            // absence keeps *Keep in sync* off everywhere else.
            refresher = getOrNull(),
            background = getOrNull(),
        )
    }

    // ⚠ The date is a *parameter*, not read from the clock here: opening the
    // editor from a day cell must start on that day, and a `Clock` read would
    // quietly override the user's selection with today.
    viewModel { (initialDate: LocalDate?, initialTime: LocalTime?) ->
        EventEditorViewModel(
            repository = get(),
            initialDate = initialDate,
            initialTime = initialTime,
        )
    }
}
