@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Date
import kotlin.js.Promise
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.take
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.hub.management.HubManagementService
import org.tenshu.hub.management.client.hubManagementService
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.client.routineService

/**
 * Home on web (`web-app-suite.md` Wave 4, Home run A): the `@JsExport` surface behind
 * `@tenshu/sdk/home`, mirroring what the iOS route-1 Home reads (`HomeLocalRuntime` →
 * `readOnlySyncedHomeRepositories`, `home-and-mia-apps.md` §8.1) — the §2 MVP: devices, the hub
 * storage tile, the automations list and the activity feed.
 *
 * | Native (`:home-shared-logic`)                      | Here                                         |
 * |----------------------------------------------------|----------------------------------------------|
 * | `ClientDeviceSource.devices()` + `rooms()`         | [devices] + [observeDevices]                 |
 * | `HubAwareHomeDevicesRepository`'s Hub/Storage tile | [hubStorage] + [observeHubStorage]           |
 * | `SyncedAutomationsRepository.automations()`        | [automations] + [observeAutomations]         |
 * | `SyncedHomeActivityRepository.events()`            | [activityPage] + [observeActivityChanges]    |
 *
 * ⚠⚠ **READ-ONLY — decided** (`web-app-suite.md` §8 items 2 and 9). There is no write here, and none
 * could be added by mistake: the plugins behind it map no operation (`HomePlugin.kt`), so a device
 * command, a rename, an automation toggle / save / delete / run-now and a hub command all fail on web
 * as "no implementation". No device control, no editor, no pairing, no routine evaluation — the iOS
 * precedent, enforced below the UI. `HomeWebContractTest.theFacadeExposesNoWrite` pins this object's
 * exported names to the eight reads.
 *
 * Every call resolves `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, `kind` a catalogue
 * key ([HomeWebErrorKeys] or the service error's derived key). ⚠ **A failed read is a failure, never an
 * empty list** (`HomeBook.kt` rule 1): a service this runtime cannot reach is
 * `error_home_service_unavailable`, not "No devices yet".
 *
 * **Reactivity:** where native has a Flow there is an `observe…` push form whose `onError` is terminal
 * (it fires for every end but the subscriber's own cancel, the `Contacts.observeJob` contract). The
 * feed is snapshot + refetch over a keyset cursor; [observeActivityChanges] says when to refetch.
 *
 * ⚠⚠ Nothing here starts work: every push is the subscriber's own collector over a relation Flow and
 * ends with it. No ticker (the tile carries `staleAfterMs` instead), no timer, no evaluation.
 */
@JsExport
object Home {

    // ── devices ───────────────────────────────────────────────────────────────────────────────

    /**
     * Every bridged device with its state as stored: `{ ok, devices, rooms }`. Relation order, as
     * native. `state` is `null` until the device's state row has synced; `rooms` are the distinct
     * non-blank room strings, in device order.
     */
    fun devices(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val svc = service("homeDevice") { JsRuntime.tenshuClient.homeDeviceService }
            ?: return@promise unavailableResult("homeDevice")
        devicesResult(read("homeDevices") { svc.devicesQuery().getAll().homeRows(::deviceRow) },
            read("deviceStates") { svc.statesQuery().getAll().homeRows(::stateRow) })
    }

    /** [devices], live: a device or state row that changes here or arrives by sync re-emits. */
    fun observeDevices(callback: (dynamic) -> Unit, onError: (dynamic) -> Unit): () -> Unit =
        observeJob(onError) {
            val svc = JsRuntime.tenshuClient.homeDeviceService
            combine(
                svc.devicesQuery().flow().map { it.homeRows(::deviceRow) },
                svc.statesQuery().flow().map { it.homeRows(::stateRow) },
            ) { devices, states -> devicesResult(devices, states) }
                .collect { callback(it) }
        }

    // ── the hub storage tile ──────────────────────────────────────────────────────────────────

    /**
     * The storage tile: `{ ok, hub }`, `hub` the freshest hub snapshot with disk figures, or **null**
     * when no hub reports any (no tile — an answer, not a failure). `stale` is judged at read time.
     */
    fun hubStorage(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val svc = service("hubManagement") { JsRuntime.tenshuClient.hubManagementService }
            ?: return@promise unavailableResult("hubManagement")
        hubStorageResult(read("hubStatuses") { svc.statusesQuery().getAll().homeRows(::hubStatusRow) }, nowMs())
    }

    /** [hubStorage], live: a new hub snapshot re-emits. ⚠ No ticker re-derives `stale` (see `HomeBook.kt`). */
    fun observeHubStorage(callback: (dynamic) -> Unit, onError: (dynamic) -> Unit): () -> Unit =
        observeJob(onError) {
            JsRuntime.tenshuClient.hubManagementService.statusesQuery().flow()
                .collect { callback(hubStorageResult(it.homeRows(::hubStatusRow), nowMs())) }
        }

    // ── automations ───────────────────────────────────────────────────────────────────────────

    /**
     * Every automation: `{ ok, automations }`, relation order. `program` is the engine's own program
     * (trigger, conditions, actions) or `null` with `programStatus` `unsupportedVersion` / `malformed` —
     * a routine this build cannot describe is listed, never dropped (`HomeBook.kt` rule 3).
     */
    fun automations(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val svc = service("routine") { JsRuntime.tenshuClient.routineService }
            ?: return@promise unavailableResult("routine")
        val routines = read("routines") { svc.routinesQuery().getAll().homeRows(::routineRow) }
        automationsResult(routines, svc.lastRunsOf(routines))
    }

    /**
     * [automations], live: a routine change, or a change in the newest [RUN_SIGNAL_WINDOW] runs (a new
     * run, so a `lastRunAtMs`), re-emits. ⚠ The runs are watched through that bounded head, never the
     * whole relation, and a re-emit re-reads one run per routine ([lastRunsOf]) — see [runsHead].
     */
    fun observeAutomations(callback: (dynamic) -> Unit, onError: (dynamic) -> Unit): () -> Unit =
        observeJob(onError) {
            val svc = JsRuntime.tenshuClient.routineService
            combine(
                svc.routinesQuery().flow().map { it.homeRows(::routineRow) },
                // Only which runs are newest matters here, not their state: a patch is not a re-read.
                svc.runsHead().map { head -> (head as? HomeRead.Ok)?.value?.map { it.firingId to it.claimedAtMs } }
                    .distinctUntilChanged(),
            ) { routines, _ -> routines }
                .collect { routines -> callback(automationsResult(routines, svc.lastRunsOf(routines))) }
        }

    // ── the activity feed ─────────────────────────────────────────────────────────────────────

    /**
     * One keyset page of the activity feed, newest first: `{ ok, events, nextCursor }`.
     *
     * [cursor] is `null` (or `""`) for the head, else a previous page's `nextCursor`; `nextCursor` is
     * `null` after the last page. The token is opaque. [pageSize] must be `1..200`.
     *
     * ⚠ Keyset over the TOTAL order `(claimedAt desc, firingId asc)`, the cursor naming the last row
     * served (`HomeBook.kt` rule 4): a row served once is never served again and a row still present
     * after the cursor is never skipped, ties at the boundary millisecond included. A run arriving
     * BEFORE the cursor is not in later pages — refetch the head ([observeActivityChanges] says when).
     * Only runs with an outcome are rows (`SHOWN_RUN_STATES`); an in-flight `claimed` firing is not.
     */
    fun activityPage(cursor: String?, pageSize: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val at = ActivityCursor.decode(cursor)
            ?: return@promise homeFailure(HomeWebErrorKeys.BAD_CURSOR, "cursor '$cursor'")
        if (pageSize !in 1..MAX_ACTIVITY_PAGE_SIZE) {
            return@promise homeFailure(HomeWebErrorKeys.BAD_PAGE_SIZE, "pageSize $pageSize")
        }
        val svc = service("routine") { JsRuntime.tenshuClient.routineService }
            ?: return@promise unavailableResult("routine")
        val names = when (val r = read("routines") { svc.routineNamesQuery().getAll().homeRows(::routineName) }) {
            is HomeRead.Failed -> return@promise r.toJs()
            is HomeRead.Ok -> r.value.toMap()
        }
        // One bounded read: the outcome filter is in the chain (`runWindow`), so every row it returns
        // is served and no `claimed` row is ever paged past client-side.
        val shown = when (val r = scanAfter(svc, at, pageSize)) {
            is HomeRead.Failed -> return@promise r.toJs()
            is HomeRead.Ok -> r.value
        }
        // A short page is the end of the feed. ⚠ An exactly-full LAST page still carries a cursor, and
        // the page after it is `[]` + `null` — the normal end, not an error (`HomeApi.activityPage`).
        // ⚠ The cursor names the last row SERVED; skipped `claimed` rows behind it stay behind it.
        val next = if (shown.size < pageSize) null else shown.last().let { ActivityCursor(it.claimedAtMs, it.firingId) }
        okResult {
            it.events = shown.map { row -> activityJs(row, names) }.toTypedArray()
            it.nextCursor = next?.encode()
        }
    }

    /**
     * Fires with a new version number on subscribe and whenever a routine (a run's name) changes or the
     * newest [RUN_SIGNAL_WINDOW] runs change — a run saved here or arriving by sync, or one of them
     * moving from `claimed` to its outcome — the signal to refetch the feed's head. ⚠ A counter, not
     * the rows: the pages stay keyset reads, and the runs are watched through that bounded head, never
     * the whole relation ([runsHead] says what that cannot see).
     */
    fun observeActivityChanges(callback: (Double) -> Unit, onError: (dynamic) -> Unit): () -> Unit =
        observeJob(onError) {
            val svc = JsRuntime.tenshuClient.routineService
            var version = 0.0
            combine(svc.routineNamesQuery().flow(), svc.runsHead().map { (it as? HomeRead.Ok)?.value }.distinctUntilChanged()) { _, _ -> Unit }
                .collect { version += 1; callback(version) }
        }

    // ── internals ─────────────────────────────────────────────────────────────────────────────

    private fun HomeDeviceService.devicesQuery() = homeDevices.select { fields(*HOME_DEVICE_FIELDS) }

    private fun HomeDeviceService.statesQuery() = deviceStates.select { fields(*DEVICE_STATE_FIELDS) }

    private fun HubManagementService.statusesQuery() = hubStatuses.select { fields(*HUB_STATUS_FIELDS) }

    private fun RoutineService.routinesQuery() = routines.select { fields(*ROUTINE_FIELDS) }

    // The feed joins names only; the program is not read for it.
    private fun RoutineService.routineNamesQuery() =
        routines.select { fields(Routine.Fields.ROUTINE_ID, Routine.Fields.NAME) }

    /**
     * The newest [RUN_SIGNAL_WINDOW] runs, live — the observe forms' signal that the runs changed. A
     * sorted window over the bare relation, so it is F1's fused top-N (`core-ordered-window-fusion.md`
     * §3.1 item 5: every stream re-run fuses): what each change re-reads is that window, never every
     * run. ⚠ What it cannot see: a run arriving by sync that sorts BELOW the window (a hub's backlog
     * with no newer row beside it), and a change to such a row. The next change inside the window
     * re-reads everything it feeds, so that staleness heals; the window is wide enough that a firing
     * still `claimed` sits in it when its outcome lands.
     */
    private fun RoutineService.runsHead(): Flow<HomeRead<List<RunHead>>> =
        routineRuns.newestRuns(RUN_SIGNAL_WINDOW).select { fields(*RUN_HEAD_FIELDS) }.flow().map { it.homeRows(::runHead) }

    /**
     * Each routine's newest run (`lastRunAtMs`, RE-D10: of ANY state, native's max over all runs) — one
     * filtered, sorted `take(1)` per routine, which fuses as top-N (`core-ordered-window-fusion.md` §9:
     * an `equalTo` on a stored field ahead of the sort). ⚠ Bounded in what it materialises (one row per
     * routine), NOT in what it walks: with no ordered index (F3) each is a thin pass over the relation.
     * A failed routines read is passed through — [automationsResult] reports it first.
     */
    private suspend fun RoutineService.lastRunsOf(routines: HomeRead<List<RoutineRow>>): HomeRead<Map<String, Long>> {
        if (routines !is HomeRead.Ok) return HomeRead.Ok(emptyMap())
        val newest = mutableMapOf<String, Long>()
        for (r in routines.value) {
            when (val run = read("routineRuns") { routineRuns.newestRunOf(r.routineId).select { fields(*RUN_HEAD_FIELDS) }.getAll().homeRows(::runHead) }) {
                is HomeRead.Failed -> return run
                is HomeRead.Ok -> run.value.firstOrNull()?.let { newest[r.routineId] = it.claimedAtMs }
            }
        }
        return HomeRead.Ok(newest)
    }

    /**
     * Up to [n] runs with an outcome strictly after [at], in the feed's order — each read a filtered,
     * sorted window that fuses as top-N ([runWindow]). ⚠ The chain has no string `>` predicate, so the
     * rest of the boundary millisecond is read from its start and cut by id here, widening only while a
     * full window is still all at or before the cursor (one millisecond's firings: a handful at most);
     * everything older is one bounded window below it — Fit's History read.
     */
    private suspend fun scanAfter(svc: RoutineService, at: ActivityCursor, n: Int): HomeRead<List<RunRow>> {
        val rows = mutableListOf<RunRow>()
        if (at.afterId != null) {
            var window = n
            while (true) {
                val batch = when (val r = read("routineRuns") { svc.runRows(svc.routineRuns.runWindow(at.beforeMs, fromMs = at.beforeMs).take(window)) }) {
                    is HomeRead.Failed -> return r
                    is HomeRead.Ok -> r.value
                }
                val after = batch.filter { it.afterCursor(at) }
                if (after.size >= n || batch.size < window || window == Int.MAX_VALUE) {
                    rows += after.take(n)
                    break
                }
                window = if (window > Int.MAX_VALUE / 2) Int.MAX_VALUE else window * 2
            }
        }
        if (rows.size < n) {
            val olderThan = if (at.afterId != null) at.beforeMs - 1 else at.beforeMs
            if (olderThan >= 0) {
                when (val r = read("routineRuns") { svc.runRows(svc.routineRuns.runWindow(olderThan).take(n - rows.size)) }) {
                    is HomeRead.Failed -> return r
                    is HomeRead.Ok -> rows += r.value
                }
            }
        }
        return HomeRead.Ok(rows)
    }

    private suspend fun RoutineService.runRows(window: TList<RoutineRun>): HomeRead<List<RunRow>> =
        window.select { fields(*ROUTINE_RUN_FIELDS) }.getAll().homeRows(::runRow)

    /** The service, or `null` when this runtime cannot resolve it — [unavailableResult]'s case. */
    private inline fun <S> service(what: String, get: () -> S): S? =
        try {
            get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[home] $what service unavailable: ${e.message}")
            null
        }

    private fun unavailableResult(what: String): dynamic =
        homeFailure(HomeWebErrorKeys.SERVICE_UNAVAILABLE, "$what service is not registered in this runtime")

    /** A read that answers, or the typed failure a throw becomes (rule 1) — never an empty list. */
    private suspend fun <T> read(what: String, body: suspend () -> HomeRead<T>): HomeRead<T> =
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            unavailable(what, e)
        }

    private fun nowMs(): Long = Date.now().toLong()

    /**
     * Runs one push stream. ⚠ [onError] is terminal and fires for every end but the subscriber's own
     * cancel — a throw, or a flow that completed (the `Contacts.observeJob` contract). It receives the
     * promise reads' typed `{ kind, message }`: `error_home_service_unavailable` for a throw,
     * `error_home_stream_ended` for a flow that completed.
     */
    private fun observeJob(onError: (dynamic) -> Unit, body: suspend () -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                body()
                homeError(HomeWebErrorKeys.STREAM_ENDED.value, HOME_OBSERVE_ENDED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                homeError(HomeWebErrorKeys.SERVICE_UNAVAILABLE.value, e.message ?: e.toString())
            }
            onError(failure)
        }
        return { job.cancel() }
    }
}

/** The terminal diagnostic for a Home observe stream that completed rather than failed. */
internal const val HOME_OBSERVE_ENDED = "home stream ended"
