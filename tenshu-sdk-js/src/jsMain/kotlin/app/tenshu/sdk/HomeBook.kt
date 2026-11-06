@file:OptIn(ExperimentalJsExport::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.equalTo
import org.tenshu.core.inRange
import org.tenshu.core.oneOf
import org.tenshu.core.number.TInteger
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.hub.management.HubStatus
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.ProgramParse
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.Trigger

// The rows and rules `Home.kt` reads by — a second reader of the relations native Home reads through
// `:home-shared-logic` (`ClientDeviceSource`, `ClientHubStatusSource` + `HubAwareHomeDevicesRepository`,
// `ClientRoutineSource` + `SyncedAutomationsRepository` / `SyncedHomeActivityRepository`). That module
// is Compose-free but has no `js` target (House Rule 5 keeps `*-shared-logic` off the web), so the
// extraction is copied here, the Contacts precedent (`ContactsBook.kt`), and pinned by
// `HomeWebContractTest`. The web-only rules, each with its reason:
//  1. **A failed read is a failure, never an empty list.** Native folds every failure to `[]`
//     (`emptyOnUnreachable`) behind its reachability banner; the web has none, and "No devices yet" for
//     a read that failed is a statement about the user's home that is false.
//  2. **Device state is carried as stored**, sentinels mapped to `null` exactly as `ClientDeviceSource`
//     maps them. There is no per-kind fold into a UI model (`SyncedDeviceAwareHomeDevicesRepository`'s
//     `DeviceState.Light` & co.): that is the app's rendering, and the d.ts says which field means what.
//  3. **Automations carry the engine's program, not the editor's model.** Native maps a routine through
//     `AutomationProgramMapper.toAutomation`, which drops what the editor cannot express (a device-
//     event, geofence or voice trigger) — a guard against an edit rewriting a routine. The web has no
//     editor, so it describes every parsed program, and a program it cannot parse is still listed with
//     `program: null` and why (`programStatus`): a routine that exists is never silently missing.
//  4. **The activity feed is keyset-paged** over the TOTAL order `(claimedAt desc, firingId asc)`, the
//     cursor naming the last row served — [ActivityCursor], Fit's History cursor over runs.
//     `RoutineRun.RETAIN_ROWS` / `RETAIN_MS` are declared but nothing prunes by them, so the feed
//     can grow without bound — hence every run read here is a bounded window that fuses as top-N
//     (`core-ordered-window-fusion.md` F1/F4): a page, one newest run per routine, the observe head.
//     ⚠ Bounded in what is materialised, sorted and sent, not in what is walked: with no ordered
//     index (F3) each window is still one thin pass over the relation (`tech-debt.md`).

/** Most activity rows one page may carry across the bridge. */
internal const val MAX_ACTIVITY_PAGE_SIZE = 200

/**
 * A hub snapshot older than this is stale: twice the hub's 1 h publish heartbeat
 * (`HubAwareHomeDevicesRepository.DEFAULT_STALE_AFTER_MS`, the same number).
 */
internal const val HUB_STALE_AFTER_MS: Long = 2 * 60L * 60 * 1000

internal object HomeWebErrorKeys {
    /** A malformed activity cursor — a caller bug, said loudly. */
    val BAD_CURSOR = ErrorCopyKey("error_home_bad_cursor")
    /** `pageSize` outside `1..MAX_ACTIVITY_PAGE_SIZE`. */
    val BAD_PAGE_SIZE = ErrorCopyKey("error_home_bad_page_size")
    /**
     * A Home service this runtime cannot reach — not registered, or its read threw instead of
     * answering. ⚠ The web's "not connected": a failure, never an empty list (rule 1).
     */
    val SERVICE_UNAVAILABLE = ErrorCopyKey("error_home_service_unavailable")
    /** An `observe…` stream whose flow completed instead of failing — still terminal (`observeJob`). */
    val STREAM_ENDED = ErrorCopyKey("error_home_stream_ended")
}

/** The `{ kind, message }` an `observe…` `onError` receives — the promise reads' `error`, unwrapped. */
internal fun homeError(kind: String, message: String?): dynamic = failureResult(kind, message).error

internal fun homeFailure(error: TError): dynamic = failureResult(error.errorCopyKey().value, error.toString())

internal fun homeFailure(key: ErrorCopyKey, message: String? = null): dynamic = failureResult(key.value, message)

/** A Home read's outcome: rows, or the typed failure it ends in (rule 1). */
internal sealed interface HomeRead<out T> {
    class Ok<T>(val value: T) : HomeRead<T>
    class Failed(val kind: String, val message: String?) : HomeRead<Nothing>
}

internal fun HomeRead.Failed.toJs(): dynamic = failureResult(kind, message)

internal fun <E : Data, R> TResult<TList<E>>.homeRows(row: (E) -> R): HomeRead<List<R>> = fold(
    onSuccess = { HomeRead.Ok(it.toList().map(row)) },
    onFailure = { HomeRead.Failed(it.errorCopyKey().value, it.toString()) },
)

/** A read that threw rather than answering (no such service, a dead dispatch): rule 1's failure. */
internal fun unavailable(what: String, e: Throwable): HomeRead.Failed =
    HomeRead.Failed(HomeWebErrorKeys.SERVICE_UNAVAILABLE.value, "$what: ${e.message ?: e::class.simpleName}")

// ── devices (natively `ClientDeviceSource`) ──────────────────────────────────────────────────────

internal val HOME_DEVICE_FIELDS = arrayOf(
    HomeDevice.Fields.DEVICE_ID, HomeDevice.Fields.BRIDGE_ID, HomeDevice.Fields.KIND,
    HomeDevice.Fields.MANUFACTURER, HomeDevice.Fields.MODEL, HomeDevice.Fields.CAPABILITY_IDS,
    HomeDevice.Fields.NAME, HomeDevice.Fields.ROOM, HomeDevice.Fields.JOINED_AT_EPOCH_MS,
)

internal val DEVICE_STATE_FIELDS = arrayOf(
    DeviceState.Fields.DEVICE_ID, DeviceState.Fields.ONLINE, DeviceState.Fields.POWER,
    DeviceState.Fields.LEVEL_PERCENT, DeviceState.Fields.TEMPERATURE_DECI_C, DeviceState.Fields.HUMIDITY_PERCENT,
    DeviceState.Fields.BATTERY_PERCENT, DeviceState.Fields.COLOR_TEMP_MIREDS, DeviceState.Fields.POWER_DRAW_DECI_W,
    DeviceState.Fields.LAST_COMMAND_ID, DeviceState.Fields.LAST_COMMAND_OUTCOME, DeviceState.Fields.FRESH_AS_OF,
)

internal class DeviceRow(
    val deviceId: String,
    val bridgeId: String,
    val kind: String,
    val manufacturer: String,
    val model: String,
    val capabilityIds: String,
    val name: String,
    val room: String,
    val joinedAtMs: Long,
)

internal class StateRow(
    val deviceId: String,
    val online: Boolean,
    val power: Long,
    val levelPercent: Long,
    val temperatureDeciC: Long,
    val humidityPercent: Long,
    val batteryPercent: Long,
    val colorTempMireds: Long,
    val powerDrawDeciW: Long,
    val lastCommandId: String,
    val lastCommandOutcome: String,
    val freshAsOfMs: Long,
)

internal fun deviceRow(e: Entity<out HomeDevice>) = DeviceRow(
    deviceId = e.getFieldValue<TString>(HomeDevice.Fields.DEVICE_ID).get(),
    bridgeId = e.getFieldValue<TString>(HomeDevice.Fields.BRIDGE_ID).get(),
    kind = e.getFieldValue<TString>(HomeDevice.Fields.KIND).get(),
    manufacturer = e.getFieldValue<TString>(HomeDevice.Fields.MANUFACTURER).get(),
    model = e.getFieldValue<TString>(HomeDevice.Fields.MODEL).get(),
    capabilityIds = e.getFieldValue<TString>(HomeDevice.Fields.CAPABILITY_IDS).get(),
    name = e.getFieldValue<TString>(HomeDevice.Fields.NAME).get(),
    room = e.getFieldValue<TString>(HomeDevice.Fields.ROOM).get(),
    joinedAtMs = e.getFieldValue<TInteger>(HomeDevice.Fields.JOINED_AT_EPOCH_MS).get(),
)

internal fun stateRow(e: Entity<out DeviceState>) = StateRow(
    deviceId = e.getFieldValue<TString>(DeviceState.Fields.DEVICE_ID).get(),
    online = e.getFieldValue<TBoolean>(DeviceState.Fields.ONLINE).get(),
    power = e.getFieldValue<TInteger>(DeviceState.Fields.POWER).get(),
    levelPercent = e.getFieldValue<TInteger>(DeviceState.Fields.LEVEL_PERCENT).get(),
    temperatureDeciC = e.getFieldValue<TInteger>(DeviceState.Fields.TEMPERATURE_DECI_C).get(),
    humidityPercent = e.getFieldValue<TInteger>(DeviceState.Fields.HUMIDITY_PERCENT).get(),
    batteryPercent = e.getFieldValue<TInteger>(DeviceState.Fields.BATTERY_PERCENT).get(),
    colorTempMireds = e.getFieldValue<TInteger>(DeviceState.Fields.COLOR_TEMP_MIREDS).get(),
    powerDrawDeciW = e.getFieldValue<TInteger>(DeviceState.Fields.POWER_DRAW_DECI_W).get(),
    lastCommandId = e.getFieldValue<TString>(DeviceState.Fields.LAST_COMMAND_ID).get(),
    lastCommandOutcome = e.getFieldValue<TString>(DeviceState.Fields.LAST_COMMAND_OUTCOME).get(),
    freshAsOfMs = e.getFieldValue<TInteger>(DeviceState.Fields.FRESH_AS_OF).get(),
)

/**
 * `ClientDeviceSource.devices()`'s join: every inventory row in relation order, with its state row
 * when one has synced. ⚠ A device whose state has not synced yet is listed with `state: null` — the
 * native `stateFresh = false` — never with plausible defaults.
 */
internal fun devicesResult(devices: HomeRead<List<DeviceRow>>, states: HomeRead<List<StateRow>>): dynamic {
    if (devices is HomeRead.Failed) return devices.toJs()
    if (states is HomeRead.Failed) return states.toJs()
    val stateById = (states as HomeRead.Ok).value.associateBy { it.deviceId }
    val rows = (devices as HomeRead.Ok).value
    return okResult {
        it.devices = rows.map { d -> deviceJs(d, stateById[d.deviceId]) }.toTypedArray()
        // `SyncedDeviceAwareHomeDevicesRepository.rooms()`: the distinct non-blank room strings, in
        // device order (D5 — a room is a string on the device, not an entity).
        it.rooms = rows.map { d -> d.room }.filter { r -> r.isNotBlank() }.distinct().toTypedArray()
    }
}

internal fun deviceJs(d: DeviceRow, s: StateRow?) = HomeDeviceJs(
    deviceId = d.deviceId,
    bridgeId = d.bridgeId,
    kind = d.kind,
    manufacturer = d.manufacturer,
    model = d.model,
    // `HomeDevice.capabilities()`: the space-separated ids, split.
    capabilities = d.capabilityIds.split(' ').filter { it.isNotBlank() }.toTypedArray(),
    name = d.name,
    room = d.room,
    joinedAtMs = d.joinedAtMs.toDouble(),
    state = s?.let(::deviceStateJs),
)

/** The sentinels `ClientDeviceSource` maps back to null — "this device does not report that". */
internal fun deviceStateJs(s: StateRow) = HomeDeviceStateJs(
    online = s.online,
    power = when (s.power) {
        DeviceState.POWER_ON -> "on"
        DeviceState.POWER_OFF -> "off"
        else -> null // POWER_NOT_APPLICABLE, or a value a newer hub writes
    },
    levelPercent = s.levelPercent.takeIf { it != DeviceState.NO_PERCENT }?.toInt(),
    temperatureDeciC = s.temperatureDeciC.takeIf { it != DeviceState.NO_TEMPERATURE_DECI_C }?.toInt(),
    humidityPercent = s.humidityPercent.takeIf { it != DeviceState.NO_PERCENT }?.toInt(),
    batteryPercent = s.batteryPercent.takeIf { it != DeviceState.NO_PERCENT }?.toInt(),
    colorTempMireds = s.colorTempMireds.takeIf { it != DeviceState.NO_VALUE }?.toInt(),
    powerDrawDeciW = s.powerDrawDeciW.takeIf { it != DeviceState.NO_VALUE }?.toInt(),
    lastCommandId = s.lastCommandId,
    lastCommandOutcome = s.lastCommandOutcome,
    freshAsOfMs = s.freshAsOfMs.toDouble(),
)

// ── the hub storage tile (natively `ClientHubStatusSource` + `HubAwareHomeDevicesRepository`) ─────

internal val HUB_STATUS_FIELDS = arrayOf(
    HubStatus.Fields.DEVICE_ID, HubStatus.Fields.NAME, HubStatus.Fields.VERSION,
    HubStatus.Fields.REPLICA_FILE_COUNT, HubStatus.Fields.REPLICA_BYTE_COUNT, HubStatus.Fields.DISK_FREE_BYTES,
    HubStatus.Fields.DISK_TOTAL_BYTES, HubStatus.Fields.LAST_BACKUP_PASS_AT_EPOCH_MS, HubStatus.Fields.FRESH_AS_OF,
)

internal class HubStatusRow(
    val deviceId: String,
    val name: String,
    val version: String,
    val replicaFileCount: Long,
    val replicaByteCount: Long,
    val diskFreeBytes: Long,
    val diskTotalBytes: Long,
    val lastBackupPassAtMs: Long,
    val freshAsOfMs: Long,
)

internal fun hubStatusRow(e: Entity<out HubStatus>) = HubStatusRow(
    deviceId = e.getFieldValue<TString>(HubStatus.Fields.DEVICE_ID).get(),
    name = e.getFieldValue<TString>(HubStatus.Fields.NAME).get(),
    version = e.getFieldValue<TString>(HubStatus.Fields.VERSION).get(),
    replicaFileCount = e.getFieldValue<TInteger>(HubStatus.Fields.REPLICA_FILE_COUNT).get(),
    replicaByteCount = e.getFieldValue<TInteger>(HubStatus.Fields.REPLICA_BYTE_COUNT).get(),
    diskFreeBytes = e.getFieldValue<TInteger>(HubStatus.Fields.DISK_FREE_BYTES).get(),
    diskTotalBytes = e.getFieldValue<TInteger>(HubStatus.Fields.DISK_TOTAL_BYTES).get(),
    lastBackupPassAtMs = e.getFieldValue<TInteger>(HubStatus.Fields.LAST_BACKUP_PASS_AT_EPOCH_MS).get(),
    freshAsOfMs = e.getFieldValue<TInteger>(HubStatus.Fields.FRESH_AS_OF).get(),
)

/**
 * `HubAwareHomeDevicesRepository.liveStatus()`: the freshest snapshot **with disk figures**
 * (`diskTotalBytes > 0` — "never fake numbers as real"), stale when older than [HUB_STALE_AFTER_MS]
 * at [nowMs]. ⚠ `hub: null` when no hub reports usable figures: no tile, never a default one.
 *
 * ⚠ Native re-derives `stale` on a 1-minute ticker. A tab runs no timer for it: the push re-emits on
 * a new row, and the row carries `freshAsOfMs` + `staleAfterMs` so the app can re-derive on its own
 * render clock.
 */
internal fun hubStorageResult(statuses: HomeRead<List<HubStatusRow>>, nowMs: Long): dynamic {
    if (statuses is HomeRead.Failed) return statuses.toJs()
    val freshest = (statuses as HomeRead.Ok).value
        .filter { it.diskTotalBytes > 0 }
        .maxByOrNull { it.freshAsOfMs }
    return okResult { it.hub = freshest?.let { s -> hubStorageJs(s, nowMs) } }
}

internal fun hubStorageJs(s: HubStatusRow, nowMs: Long) = HomeHubStorageJs(
    deviceId = s.deviceId,
    name = s.name,
    version = s.version,
    diskFreeBytes = s.diskFreeBytes.toDouble(),
    diskTotalBytes = s.diskTotalBytes.toDouble(),
    usedBytes = (s.diskTotalBytes - s.diskFreeBytes).coerceAtLeast(0).toDouble(),
    replicaFileCount = s.replicaFileCount.toDouble(),
    replicaByteCount = s.replicaByteCount.toDouble(),
    lastBackupPassAtMs = s.lastBackupPassAtMs.takeIf { it > 0 }?.toDouble(),
    freshAsOfMs = s.freshAsOfMs.toDouble(),
    stale = nowMs - s.freshAsOfMs > HUB_STALE_AFTER_MS,
    staleAfterMs = HUB_STALE_AFTER_MS.toDouble(),
)

// ── automations (natively `ClientRoutineSource.routines()` + `SyncedAutomationsRepository`) ──────

internal val ROUTINE_FIELDS = arrayOf(
    Routine.Fields.ROUTINE_ID, Routine.Fields.NAME, Routine.Fields.ENABLED, Routine.Fields.STATE,
    Routine.Fields.SUSPENDED_REASON, Routine.Fields.TIME_ZONE_ID, Routine.Fields.PROGRAM_JSON,
    Routine.Fields.PROGRAM_VERSION,
)

/** How many of the newest runs the observe forms watch (`Home.runsHead`): a cheap head, not the relation. */
internal const val RUN_SIGNAL_WINDOW = 32

/** What a run read for a signal or a `lastRunAtMs` needs — no outcome detail. */
internal val RUN_HEAD_FIELDS = arrayOf(
    RoutineRun.Fields.FIRING_ID, RoutineRun.Fields.ROUTINE_ID, RoutineRun.Fields.CLAIMED_AT_EPOCH_MS,
    RoutineRun.Fields.STATE, RoutineRun.Fields.COALESCED_COUNT,
)

internal val ROUTINE_RUN_FIELDS = arrayOf(
    RoutineRun.Fields.FIRING_ID, RoutineRun.Fields.ROUTINE_ID, RoutineRun.Fields.CLAIMED_AT_EPOCH_MS,
    RoutineRun.Fields.FINISHED_AT_EPOCH_MS, RoutineRun.Fields.STATE, RoutineRun.Fields.COALESCED_COUNT,
)

internal class RoutineRow(
    val routineId: String,
    val name: String,
    val enabled: Boolean,
    val state: String,
    val suspendedReason: String,
    val timeZoneId: String,
    val programJson: String,
    val programVersion: Long,
)

internal class RunRow(
    val firingId: String,
    val routineId: String,
    val claimedAtMs: Long,
    val finishedAtMs: Long,
    val state: String,
    val coalescedCount: Long,
)

internal fun routineRow(e: Entity<out Routine>) = RoutineRow(
    routineId = e.getFieldValue<TString>(Routine.Fields.ROUTINE_ID).get(),
    name = e.getFieldValue<TString>(Routine.Fields.NAME).get(),
    enabled = e.getFieldValue<TBoolean>(Routine.Fields.ENABLED).get(),
    state = e.getFieldValue<TString>(Routine.Fields.STATE).get(),
    suspendedReason = e.getFieldValue<TString>(Routine.Fields.SUSPENDED_REASON).get(),
    timeZoneId = e.getFieldValue<TString>(Routine.Fields.TIME_ZONE_ID).get(),
    programJson = e.getFieldValue<TString>(Routine.Fields.PROGRAM_JSON).get(),
    programVersion = e.getFieldValue<TInteger>(Routine.Fields.PROGRAM_VERSION).get(),
)

/** `(routineId, name)` — the feed's name join reads these two fields only. */
internal fun routineName(e: Entity<out Routine>): Pair<String, String> =
    e.getFieldValue<TString>(Routine.Fields.ROUTINE_ID).get() to e.getFieldValue<TString>(Routine.Fields.NAME).get()

/**
 * One of the newest runs, as a signal reads it. A data class: two heads are the same signal exactly
 * when their rows are equal, so `distinctUntilChanged` drops a re-run that changed nothing it shows.
 */
internal data class RunHead(
    val firingId: String,
    val routineId: String,
    val claimedAtMs: Long,
    val state: String,
    val coalescedCount: Long,
)

internal fun runHead(e: Entity<out RoutineRun>) = RunHead(
    firingId = e.getFieldValue<TString>(RoutineRun.Fields.FIRING_ID).get(),
    routineId = e.getFieldValue<TString>(RoutineRun.Fields.ROUTINE_ID).get(),
    claimedAtMs = e.getFieldValue<TInteger>(RoutineRun.Fields.CLAIMED_AT_EPOCH_MS).get(),
    state = e.getFieldValue<TString>(RoutineRun.Fields.STATE).get(),
    coalescedCount = e.getFieldValue<TInteger>(RoutineRun.Fields.COALESCED_COUNT).get(),
)

internal fun runRow(e: Entity<out RoutineRun>) = RunRow(
    firingId = e.getFieldValue<TString>(RoutineRun.Fields.FIRING_ID).get(),
    routineId = e.getFieldValue<TString>(RoutineRun.Fields.ROUTINE_ID).get(),
    claimedAtMs = e.getFieldValue<TInteger>(RoutineRun.Fields.CLAIMED_AT_EPOCH_MS).get(),
    finishedAtMs = e.getFieldValue<TInteger>(RoutineRun.Fields.FINISHED_AT_EPOCH_MS).get(),
    state = e.getFieldValue<TString>(RoutineRun.Fields.STATE).get(),
    coalescedCount = e.getFieldValue<TInteger>(RoutineRun.Fields.COALESCED_COUNT).get(),
)

private val programCodec = JsonRoutineProgramCodec()

/**
 * `ClientRoutineSource.routines()`: every routine in relation order, `lastRunAtMs` its newest run of ANY
 * state (RE-D10 keeps it off the routine row; native takes the max over all runs). [newestRun] maps a
 * routine id to that run's claim time — read one bounded row per routine (`Home.lastRunsOf`), never
 * derived from every run; a routine absent from it has never run.
 */
internal fun automationsResult(routines: HomeRead<List<RoutineRow>>, newestRun: HomeRead<Map<String, Long>>): dynamic {
    if (routines is HomeRead.Failed) return routines.toJs()
    if (newestRun is HomeRead.Failed) return newestRun.toJs()
    val newest = (newestRun as HomeRead.Ok).value
    return okResult {
        it.automations = (routines as HomeRead.Ok).value
            .map { r -> automationJs(r, newest[r.routineId]) }
            .toTypedArray()
    }
}

internal fun automationJs(r: RoutineRow, lastRunAtMs: Long?): HomeAutomationJs {
    val parse = programCodec.parse(r.programJson, r.programVersion)
    return HomeAutomationJs(
        routineId = r.routineId,
        name = r.name,
        enabled = r.enabled,
        state = r.state,
        suspendedReason = r.suspendedReason,
        timeZoneId = r.timeZoneId,
        lastRunAtMs = lastRunAtMs?.toDouble(),
        programStatus = when (parse) {
            is ProgramParse.Parsed -> "parsed"
            is ProgramParse.UnsupportedVersion -> "unsupportedVersion"
            is ProgramParse.Malformed -> "malformed"
        },
        program = (parse as? ProgramParse.Parsed)?.program?.let(::programJs),
    )
}

/**
 * The program as plain JS, one `type` tag per variant — the codec's own `@SerialName`s, so the tags
 * are the words already on the wire. Enum values are lower-case tokens.
 */
internal fun programJs(p: RoutineProgram): dynamic {
    val o: dynamic = js("({})")
    o.trigger = triggerJs(p.trigger)
    o.conditions = p.conditions.map(::conditionJs).toTypedArray()
    o.actions = p.actions.map(::actionJs).toTypedArray()
    return o
}

private fun tagged(type: String, build: (dynamic) -> Unit): dynamic {
    val o: dynamic = js("({})")
    o.type = type
    build(o)
    return o
}

private fun matchJs(m: StateMatch): dynamic {
    val o: dynamic = js("({})")
    o.field = m.field
    o.op = m.op.name.lowercase()
    o.value = m.value
    return o
}

private fun triggerJs(t: Trigger): dynamic = when (t) {
    is Trigger.TimeOfDay -> tagged("timeOfDay") {
        it.minuteOfDay = t.minuteOfDay
        // ISO days 1 (Mon) .. 7 (Sun), ascending; empty = every day.
        it.days = t.days.sorted().toTypedArray()
    }
    is Trigger.Sun -> tagged("sun") {
        it.event = t.event.name.lowercase()
        it.offsetMinutes = t.offsetMinutes
    }
    is Trigger.DeviceEvent -> tagged("deviceEvent") {
        it.deviceId = t.deviceId
        it.match = matchJs(t.match)
    }
    is Trigger.Geofence -> tagged("geofence") {
        it.placeId = t.placeId
        it.direction = t.direction.name.lowercase()
    }
    is Trigger.VoicePhrase -> tagged("voicePhrase") { it.phrase = t.phrase }
}

private fun conditionJs(c: Condition): dynamic = when (c) {
    is Condition.TimeWindow -> tagged("timeWindow") {
        it.fromMinuteOfDay = c.fromMinuteOfDay
        it.toMinuteOfDay = c.toMinuteOfDay
    }
    is Condition.DeviceStateIs -> tagged("deviceStateIs") {
        it.deviceId = c.deviceId
        it.match = matchJs(c.match)
    }
    is Condition.Presence -> tagged("presence") {
        it.expect = if (c.expect == Occupancy.NOBODY_HOME) "nobodyHome" else "somebodyHome"
    }
}

private fun actionJs(a: Action): dynamic = when (a) {
    is Action.SetPower -> tagged("setPower") {
        it.deviceId = a.deviceId
        it.on = a.on
    }
    is Action.SetLevel -> tagged("setLevel") {
        it.deviceId = a.deviceId
        it.percent = a.percent
    }
    is Action.Notify -> tagged("notify") {
        it.title = a.title
        it.body = a.body
    }
    is Action.RunRoutine -> tagged("runRoutine") { it.routineId = a.routineId }
}

// ── the activity feed (natively `ClientRoutineSource.runs()` + `SyncedHomeActivityRepository`) ───

/**
 * The run states the feed shows — `SyncedHomeActivityRepository.toActivityEvent`'s set. ⚠ A `claimed`
 * row is a firing that has started and not reported (it becomes `ran` / `failed` moments later), and
 * a state a newer engine writes has no description: native drops both, and so does the web.
 */
internal val SHOWN_RUN_STATES = setOf(
    RoutineRun.STATE_RAN, RoutineRun.STATE_FAILED, RoutineRun.STATE_SKIPPED,
    RoutineRun.STATE_MISSED, RoutineRun.STATE_THROTTLED,
)

/**
 * The keyset cursor, opaque to TypeScript (`a1.<beforeMs>.<afterId>`): the position just after the run
 * `(beforeMs, afterId)` in the total order `(claimedAt desc, firingId asc)` — Fit's `w1.` cursor
 * (`FitBook.kt` rule 1) over runs, with the same canonical-decimal guard.
 *
 * [afterId] `null` (encoded empty) means "every run AT [beforeMs] is still to come" — the head, and a
 * caller-built window top. A firing id is never empty (it keys the row), and may contain `.`, so
 * everything after the second separator is the id.
 */
internal data class ActivityCursor(val beforeMs: Long, val afterId: String?) {
    fun encode(): String = "$CURSOR_PREFIX$beforeMs.${afterId.orEmpty()}"

    companion object {
        private const val CURSOR_PREFIX = "a1."
        val HEAD = ActivityCursor(Long.MAX_VALUE, null)

        /** `null`/`""` is the head; a malformed token is `null` here and a typed failure upstream. */
        fun decode(token: String?): ActivityCursor? {
            if (token.isNullOrEmpty()) return HEAD
            if (!token.startsWith(CURSOR_PREFIX)) return null
            val rest = token.removePrefix(CURSOR_PREFIX)
            val dot = rest.indexOf('.')
            if (dot < 0) return null
            val raw = rest.substring(0, dot)
            val before = raw.toLongOrNull() ?: return null
            // ⚠ As for Fit's cursor: a negative position wraps `beforeMs - 1`, and a non-canonical one
            // is no position any read minted.
            if (before < 0 || before.toString() != raw) return null
            return ActivityCursor(before, rest.substring(dot + 1).ifEmpty { null })
        }
    }
}

/** Is [this] strictly after [cursor] in the feed's order? */
internal fun RunRow.afterCursor(cursor: ActivityCursor): Boolean = when {
    claimedAtMs < cursor.beforeMs -> true
    claimedAtMs > cursor.beforeMs -> false
    else -> cursor.afterId == null || firingId > cursor.afterId
}

private fun exclusiveEnd(beforeMs: Long): Long = if (beforeMs == Long.MAX_VALUE) beforeMs else beforeMs + 1

/**
 * Every run WITH AN OUTCOME ([SHOWN_RUN_STATES]) claimed in `[fromMs, beforeMs]`, in the feed's order.
 * The feed starts at the epoch: a negative claim time is no real firing.
 *
 * ⚠⚠ Shaped to fuse once a `take` follows (`core-ordered-window-fusion.md` §9, F4): `filter` — not
 * `searchAll`, a different op the gate does not recognise — with an `inRange` and a `oneOf` on stored
 * fields against constants, then a `thenBy` sort run. Fused, a page is one thin pass into a bounded
 * heap: no row is materialised whole, the relation is never fully sorted, and no `claimed` row reaches
 * the page to be skipped. A predicate outside §9.2's allowlist would silently fall back to
 * materialising every run — `HomeWebContractTest.everyRunRead_fusesAsTopN` dispatch-counts this.
 */
internal fun TList<RoutineRun>.runWindow(beforeMs: Long, fromMs: Long = 0): TList<RoutineRun> = this
    .filter<RoutineRun> { claimedAtEpochMs.inRange(fromMs, exclusiveEnd(beforeMs)) }
    .filter<RoutineRun> { state oneOf SHOWN_RUN_STATES.toList() }
    .sortByDescending { claimedAtEpochMs }
    .thenBy { firingId }

/** The newest [n] runs of any state, `(claimedAt desc, firingId asc)` — F1's bare top-N. */
internal fun TList<RoutineRun>.newestRuns(n: Int): TList<RoutineRun> = this
    .sortByDescending { claimedAtEpochMs }
    .thenBy { firingId }
    .take(n)

/** Routine [id]'s newest run of any state, or none — F4's filtered top-1. */
internal fun TList<RoutineRun>.newestRunOf(id: String): TList<RoutineRun> = this
    .filter<RoutineRun> { routineId equalTo id }
    .sortByDescending { claimedAtEpochMs }
    .thenBy { firingId }
    .take(1)

/** One activity row — `ActivityEvent`, with the state as its token rather than English copy. */
internal fun activityJs(r: RunRow, nameById: Map<String, String>) = HomeActivityJs(
    firingId = r.firingId,
    kind = "automation",
    routineId = r.routineId,
    // ⚠ The name the routine has NOW, `""` when it has since been deleted: a firing that happened is a
    // fact about the home, so the row stays (`ClientRoutineSource.runs()`).
    routineName = nameById[r.routineId].orEmpty(),
    state = r.state,
    claimedAtMs = r.claimedAtMs.toDouble(),
    finishedAtMs = r.finishedAtMs.takeIf { it != RoutineRun.NOT_FINISHED }?.toDouble(),
    coalescedCount = r.coalescedCount.toInt(),
)

// ── JS shapes ────────────────────────────────────────────────────────────────────────────────────

/**
 * One bridged device. `kind` is the stored `HomeDevice.KIND_*` token — unknown kinds pass through
 * as stored (native renders them as a generic sensor row). `room` `""` = unassigned.
 */
@JsExport
class HomeDeviceJs internal constructor(
    val deviceId: String,
    val bridgeId: String,
    val kind: String,
    val manufacturer: String,
    val model: String,
    val capabilities: Array<String>,
    val name: String,
    val room: String,
    val joinedAtMs: Double,
    /** `null` until the hub's state row for this device has synced. */
    val state: HomeDeviceStateJs?,
)

/** A device's state row as stored; each `null` means "this device does not report that". */
@JsExport
class HomeDeviceStateJs internal constructor(
    val online: Boolean,
    /** `"on"` / `"off"`; `null` for a device with no power dimension. */
    val power: String?,
    val levelPercent: Int?,
    /** Tenths of a degree Celsius. */
    val temperatureDeciC: Int?,
    val humidityPercent: Int?,
    val batteryPercent: Int?,
    val colorTempMireds: Int?,
    /** Tenths of a watt. */
    val powerDrawDeciW: Int?,
    /** The last command the hub consumed for this device (`""` = none), and its `ok|failed|unsupported`. */
    val lastCommandId: String,
    val lastCommandOutcome: String,
    /** Hub clock, epoch ms. */
    val freshAsOfMs: Double,
)

/** The hub storage tile: the freshest hub snapshot that carries disk figures. */
@JsExport
class HomeHubStorageJs internal constructor(
    val deviceId: String,
    val name: String,
    val version: String,
    val diskFreeBytes: Double,
    val diskTotalBytes: Double,
    val usedBytes: Double,
    val replicaFileCount: Double,
    val replicaByteCount: Double,
    /** `null` = the hub has never completed a backup pass. */
    val lastBackupPassAtMs: Double?,
    /** Hub clock, epoch ms. */
    val freshAsOfMs: Double,
    /** At read time: older than [staleAfterMs] — the figures were real, the hub is not reporting. */
    val stale: Boolean,
    val staleAfterMs: Double,
)

/** One automation (a synced `Routine`). `program` is `null` exactly when `programStatus` is not `parsed`. */
@JsExport
class HomeAutomationJs internal constructor(
    val routineId: String,
    val name: String,
    val enabled: Boolean,
    /** `active` | `suspended` (the engine stood it down; see [suspendedReason]). */
    val state: String,
    val suspendedReason: String,
    val timeZoneId: String,
    val lastRunAtMs: Double?,
    val programStatus: String,
    val program: dynamic,
)

/** One activity-feed row: a routine firing with an outcome. */
@JsExport
class HomeActivityJs internal constructor(
    val firingId: String,
    /** Always `automation`: no device-event or voice history is stored anywhere. */
    val kind: String,
    val routineId: String,
    val routineName: String,
    /** `ran` | `failed` | `skipped` | `missed` | `throttled`. */
    val state: String,
    val claimedAtMs: Double,
    val finishedAtMs: Double?,
    /** Firings a `throttled` row absorbed. */
    val coalescedCount: Int,
)
