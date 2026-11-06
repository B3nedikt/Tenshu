package org.tenshu.routine.proxy

import kotlin.time.Instant
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.routine.PresenceState
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.common.engine.Admission
import org.tenshu.routine.common.engine.CapabilityTier
import org.tenshu.routine.common.engine.Citation
import org.tenshu.routine.common.engine.ConditionEvaluator
import org.tenshu.routine.common.engine.ConditionSnapshot
import org.tenshu.routine.common.engine.ConditionVerdict
import org.tenshu.routine.common.engine.DefaultConditionEvaluator
import org.tenshu.routine.common.engine.DefaultFiringCalculator
import org.tenshu.routine.common.engine.DefaultSafetyGate
import org.tenshu.routine.common.engine.DefaultTriggerObservability
import org.tenshu.routine.common.engine.DevicePresence
import org.tenshu.routine.common.engine.PresenceResolver
import org.tenshu.routine.common.engine.ExecutorClaim
import org.tenshu.routine.common.engine.Firing
import org.tenshu.routine.common.engine.FiringCalculator
import org.tenshu.routine.common.engine.FiringIds
import org.tenshu.routine.common.engine.ObservedState
import org.tenshu.routine.common.engine.RosterDevice
import org.tenshu.routine.common.engine.RoutineLocale
import org.tenshu.routine.common.engine.SafetyGate
import org.tenshu.routine.common.engine.TierThenIdExecutorClaim
import org.tenshu.routine.common.engine.TriggerObservability
import org.tenshu.routine.common.program.Action
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.ProgramParse
import org.tenshu.routine.common.program.RoutineProgram
import org.tenshu.routine.common.program.RoutineProgramCodec
import org.tenshu.routine.common.program.StateMatch
import org.tenshu.routine.common.program.Trigger as ProgramTrigger

/** What the engine decided about one candidate firing — the §4.9 verdict, for tests and logs. */
sealed interface FiringResult {
    data object Ran : FiringResult
    class Failed(val reason: String) : FiringResult
    class Skipped(val reason: String) : FiringResult
    data object Missed : FiringResult
    class Throttled(val coalescedInto: String?) : FiringResult
    /** Nothing to do: not eligible, not the executor, unknown program version, disabled. */
    class StoodDown(val reason: String) : FiringResult
}

/**
 * The engine loop (`routines-engine.md` §4.9).
 *
 * ⚠⚠ **The orderings here are the guards.** A journal write after the first
 * action is not an at-most-once journal; a condition snapshot taken at trigger
 * time is not RE-D7's snapshot; a catch-up check after the journal leaves a
 * marker to replay for a firing that never ran. Read §4.9 before reordering
 * anything in [runFiring].
 *
 * ⚠ Time/sun, manual and **device-event** (RE-2's [onDeviceEvent]) triggers all fire from here,
 * and RE-4's presence *condition* resolves below. ⚠⚠ **Geofence still has no entry point**: such
 * a routine validates, computes no next firing, and nothing ever wakes it.
 *
 * ⚠ It does not even reach the claim. [prepare] refuses it one step earlier, at OBSERVABILITY:
 * `canObserve(Trigger.Geofence)` is [hasLocationSource], and no production caller sets it —
 * `RoutineEngineFactory.create` passes `hostsDeviceBridge` and leaves the location flag at its
 * `false` default, so every assembled engine answers no. Only the `commonTest` constructions pass
 * it. The distinction matters when reading a stand-down: geofence stops at step 2, not step 3.
 */
internal class RoutineEngine(
    private val executionContext: ExecutionContext,
    private val implementationFactory: OperationImplementationFactory,
    private val nowEpochMs: () -> Long,
    private val thisDeviceId: () -> String?,
    private val roster: () -> List<RosterDevice>,
    private val hostsDeviceBridge: Boolean = false,
    private val hasLocationSource: Boolean = false,
    private val codec: RoutineProgramCodec = JsonRoutineProgramCodec(),
    private val calculator: FiringCalculator = DefaultFiringCalculator(),
    private val conditions: ConditionEvaluator = DefaultConditionEvaluator(),
    private val claim: ExecutorClaim = TierThenIdExecutorClaim(),
    private val safetyGate: SafetyGate = DefaultSafetyGate(),
    private val observability: TriggerObservability =
        DefaultTriggerObservability(hostsDeviceBridge, hasLocationSource),
) {

    private val journal = RoutineFiringJournal(executionContext)

    // ---------------------------------------------------------------- entry points

    /**
     * `EvaluateDueRoutines`: fire everything due, then re-arm.
     *
     * ⚠ Re-arming is unconditional — it must happen even when nothing fired, or
     * a wake that finds no work is the last wake this device ever schedules.
     */
    fun evaluateDue(): List<FiringResult> {
        // ⭐ Reconcile first. Deliberately here rather than at service init: the
        // engine needs the operation factory to arm, and that is only assembled
        // *after* init in the platform wirings — a chicken-and-egg an extra
        // init hook would have to break. Reconciliation only corrects display
        // state (a stuck CLAIMED row), so doing it on the first wake rather than
        // at process start costs nothing, and it is idempotent.
        reconcileOnStart()

        val results = mutableListOf<FiringResult>()
        val now = Instant.fromEpochMilliseconds(nowEpochMs())

        for (routine in loadRoutines()) {
            val prepared = prepare(routine) ?: continue
            val due = prepared.dueFiring(now) ?: continue
            results += runFiring(routine, prepared, due.instant, due.wasFallback)
        }

        reArm()
        return results
    }

    /**
     * `RunRoutineNow`: the manual gesture.
     *
     * ⭐ Deliberately **bypasses the executor claim** — the user is here, on this
     * device, asking for it now; deferring their tap to whichever device holds
     * the claim would be both slower and inexplicable. Everything else in the
     * sequence still applies.
     *
     * ⚠ Ignores [Routine.enabled] (running a disabled routine on purpose is how
     * you test one while authoring it) but honours `SUSPENDED`, because that is
     * the engine's own verdict that the routine is misbehaving.
     */
    fun runNow(routineId: String): FiringResult {
        val routine = loadRoutines().firstOrNull { it.routineId.get() == routineId }
            ?: return FiringResult.StoodDown("unknown routine $routineId")
        if (routine.state.get() == Routine.STATE_SUSPENDED) {
            return FiringResult.StoodDown("routine is suspended: ${routine.suspendedReason.get()}")
        }
        val prepared = prepare(routine, ignoreEnabled = true)
            ?: return FiringResult.StoodDown("routine cannot run on this device")

        val now = nowEpochMs()
        return runFiring(
            routine = routine,
            prepared = prepared,
            triggerInstantEpochMs = now,
            wasFallback = false,
            firingId = FiringIds.manual(routineId, now),
            catchUpApplies = false,
        )
    }

    /**
     * Startup reconciliation (§4.9). ⚠ **Not recovery** — an interrupted run is
     * *not* re-run (RE-D4 at-most-once). The `CLAIMED` row stays visible as
     * "interrupted" and the marker is cleared so it does not re-reconcile
     * forever.
     */
    fun reconcileOnStart(): Int {
        val markers = journal.incomplete()
        markers.forEach { marker ->
            val firingId = marker.firingId.get()
            patchRun(firingId) { existing ->
                existing.copyWith(
                    state = RoutineRun.STATE_FAILED,
                    finishedAtEpochMs = nowEpochMs(),
                    actionOutcomes = "interrupted: the engine stopped mid-run",
                )
            }
            journal.complete(firingId)
        }
        reArm()
        return markers.size
    }

    /**
     * The device-event fast path (RE-2, §4.9 step 0).
     *
     * ⭐ This is the shape that makes the product claim literal: when the
     * executor is also the bridge host, the `DeviceCommand` row this writes goes
     * to the *local* store, the local observer fires immediately, and the bridge
     * acts — **the relay is never in the path**. The row syncs afterwards purely
     * as the audit trail.
     *
     * ⚠ The claim is deliberately not consulted. RE-D1 already guarantees
     * exactly one eligible observer for this trigger class, so there is nothing
     * to contend over — asking the roster would only invent a second answer.
     */
    fun onDeviceEvent(change: ObservedStateChange): List<FiringResult> {
        val results = mutableListOf<FiringResult>()

        for (routine in loadRoutines()) {
            val prepared = prepare(routine) ?: continue
            val trigger = prepared.program.trigger as? ProgramTrigger.DeviceEvent ?: continue
            if (trigger.deviceId != change.deviceId) continue

            // 0. SELF-TRIGGER SUPPRESSION — ⭐⭐ free, and with no schema change.
            //    `DeviceCommand.commandId` is caller-minted and the hub echoes it
            //    back on the resulting state row, so a change this routine caused
            //    is recognisable by name. The alternative — adding an
            //    `issuedForFiringId` field to the shipped DeviceCommand composite
            //    — would be a peer-breaking positional change for information the
            //    id already carries.
            if (change.lastCommandId.startsWith(commandIdPrefixFor(routine.routineId.get()))) {
                results += FiringResult.StoodDown("self-triggered by ${change.lastCommandId}")
                continue
            }

            if (!matches(trigger.match, change)) continue

            results += runFiring(
                routine = routine,
                prepared = prepared,
                triggerInstantEpochMs = change.freshAsOfEpochMs,
                wasFallback = false,
                firingId = FiringIds.deviceEvent(
                    routineId = routine.routineId.get(),
                    deviceId = change.deviceId,
                    freshAsOfEpochMs = change.freshAsOfEpochMs,
                ),
                // ⚠ An event IS its own occurrence — there is no "late" to
                // catch up on, and applying the window would drop a routine
                // whose sensor reported a slightly stale timestamp.
                catchUpApplies = false,
            )
        }
        return results
    }

    private fun matches(match: StateMatch, change: ObservedStateChange): Boolean {
        val actual = change.fields[match.field] ?: return false
        return when (match.op) {
            MatchOp.EQ -> actual == match.value
            MatchOp.NEQ -> actual != match.value
            MatchOp.LT -> numeric(actual, match.value) { a, b -> a < b }
            MatchOp.GT -> numeric(actual, match.value) { a, b -> a > b }
        }
    }

    private fun numeric(a: String, b: String, compare: (Double, Double) -> Boolean): Boolean {
        val left = a.toDoubleOrNull() ?: return false
        val right = b.toDoubleOrNull() ?: return false
        return compare(left, right)
    }

    /** Every command this routine issues starts with this — see [onDeviceEvent]. */
    private fun commandIdPrefixFor(routineId: String): String = "firing:$routineId"

    // ---------------------------------------------------------------- the loop

    private class Prepared(
        val program: RoutineProgram,
        val locale: RoutineLocale,
        val isExecutor: Boolean,
    )

    private class Due(val instant: Long, val wasFallback: Boolean)

    /** Steps 1–3: parse, observability, claim. `null` = this device stands down. */
    private fun prepare(routine: Routine, ignoreEnabled: Boolean = false): Prepared? {
        if (!ignoreEnabled && !routine.enabled.get()) return null
        if (routine.state.get() == Routine.STATE_SUSPENDED) return null

        // 1. PARSE — ⚠⚠ an unsupported version must NOT claim either, so the
        //    routine falls through to a device whose engine understands it.
        val program = when (val parse = codec.parse(routine.programJson.get(), routine.programVersion.get())) {
            is ProgramParse.Parsed -> parse.program
            is ProgramParse.UnsupportedVersion -> return null
            is ProgramParse.Malformed -> return null
        }

        // 2. OBSERVABILITY — do not arm what this process cannot see.
        if (!observability.canObserve(program.trigger)) return null

        // 3. CLAIM.
        val me = thisDeviceId()
        val winner = claim.executorFor(routine.routineId.get(), roster())
        return Prepared(
            program = program,
            locale = RoutineLocale(
                timeZoneId = routine.timeZoneId.get(),
                homeLatE5 = routine.homeLatE5.get(),
                homeLonE5 = routine.homeLonE5.get(),
            ),
            isExecutor = me != null && me == winner,
        )
    }

    /**
     * Is a firing due for this device right now?
     *
     * ⭐ A non-executor still has a due time — `triggerInstant + GRACE` — which is
     * what makes RE-D2's second half real rather than decorative. It stands down
     * if a receipt has arrived in the meantime.
     */
    private fun Prepared.dueFiring(now: Instant): Due? {
        // The most recent firing at or before `now`: walk back from a horizon so
        // a wake that is slightly late still sees the firing it woke for.
        val horizon = now - HORIZON
        val candidate = calculator.nextFiring(program.trigger, locale, horizon) ?: return null
        val candidateMs = candidate.toEpochMilliseconds()

        val offset = if (isExecutor) 0 else DefaultSafetyGate.GRACE_MS
        if (now.toEpochMilliseconds() < candidateMs + offset) return null
        return Due(instant = candidateMs, wasFallback = !isExecutor)
    }

    private fun runFiring(
        routine: Routine,
        prepared: Prepared,
        triggerInstantEpochMs: Long,
        wasFallback: Boolean,
        firingId: String = FiringIds.scheduled(routine.routineId.get(), triggerInstantEpochMs),
        catchUpApplies: Boolean = true,
        chain: List<String> = listOf(routine.routineId.get()),
        depth: Int = 0,
        chainRootFiringId: String = firingId,
    ): FiringResult {
        val routineId = routine.routineId.get()
        val now = nowEpochMs()

        // Already handled — by this device (journal) or by a peer (receipt).
        if (journal.contains(firingId)) return FiringResult.StoodDown("already in flight")
        if (loadRun(firingId) != null) return FiringResult.StoodDown("receipt already exists")

        // 5. CATCH-UP — ⚠ before the journal, so a missed firing leaves nothing
        //    to replay. `DeviceCommand.STALE_WINDOW_MS` does NOT cover this: a
        //    late routine issues a perfectly fresh command.
        if (catchUpApplies && now - triggerInstantEpochMs > DefaultSafetyGate.CATCHUP_WINDOW_MS) {
            writeRun(
                firingId = firingId, routineId = routineId,
                triggerInstantEpochMs = triggerInstantEpochMs,
                claimedAt = now, finishedAt = now,
                state = RoutineRun.STATE_MISSED, wasFallback = wasFallback,
                chainRootFiringId = chainRootFiringId, depth = depth,
                actionOutcomes = "woke ${(now - triggerInstantEpochMs) / 60_000} min late",
            )
            return FiringResult.Missed
        }

        // 6. SAFETY GATE — also before the journal.
        val firing = Firing(
            id = firingId,
            routineId = routineId,
            triggerInstantEpochMs = triggerInstantEpochMs,
            chainRootFiringId = chainRootFiringId,
            depth = depth,
            chain = chain,
            wasFallback = wasFallback,
        )
        when (val admission = safetyGate.admit(firing, now)) {
            is Admission.Admit -> Unit
            is Admission.Throttled -> return recordRefusal(firing, now, "throttled")
            is Admission.Suspended -> {
                suspendRoutine(routine, admission.reason)
                return recordRefusal(firing, now, "suspended: ${admission.reason}")
            }

            is Admission.CycleRefused -> {
                writeRun(
                    firingId, routineId, triggerInstantEpochMs, now, now,
                    RoutineRun.STATE_FAILED, wasFallback, chainRootFiringId, depth,
                    actionOutcomes = "cycle refused: ${admission.chain.joinToString(" → ")}",
                )
                return FiringResult.Failed("cycle")
            }

            is Admission.DepthExceeded -> {
                writeRun(
                    firingId, routineId, triggerInstantEpochMs, now, now,
                    RoutineRun.STATE_FAILED, wasFallback, chainRootFiringId, depth,
                    actionOutcomes = "chain depth ${admission.depth} exceeds the guard",
                )
                return FiringResult.Failed("depth")
            }
        }

        // 7. JOURNAL, then 8. RECEIPT. ⚠⚠ This order is load-bearing (RE-D4).
        journal.begin(firingId, routineId, now)
        writeRun(
            firingId, routineId, triggerInstantEpochMs, now, RoutineRun.NOT_FINISHED,
            RoutineRun.STATE_CLAIMED, wasFallback, chainRootFiringId, depth,
        )

        // 9. SNAPSHOT — once, at action time (RE-D7). 10. CONDITIONS.
        val snapshot = takeSnapshot()
        val verdict = conditions.evaluate(prepared.program.conditions, snapshot, prepared.locale)
        val citations = verdict.citations.render()
        if (verdict !is ConditionVerdict.Met) {
            val reason = (verdict as? ConditionVerdict.Unknown)?.reason ?: "conditions not met"
            writeRun(
                firingId, routineId, triggerInstantEpochMs, now, nowEpochMs(),
                RoutineRun.STATE_SKIPPED, wasFallback, chainRootFiringId, depth,
                conditionCitations = citations, actionOutcomes = reason,
            )
            journal.complete(firingId)
            return FiringResult.Skipped(reason)
        }

        // 11. ACTIONS — sequential, author order, continue on failure (RE-D8).
        val outcomes = mutableListOf<String>()
        var anyFailed = false
        prepared.program.actions.forEachIndexed { index, action ->
            val outcome = runCatching { execute(action, firingId, index, chain, depth, chainRootFiringId) }
                .getOrElse { "error: ${it.message}" }
            if (outcome.startsWith("failed") || outcome.startsWith("error")) anyFailed = true
            outcomes += outcome
        }

        // 12. RESULT, then 13. JOURNAL clear.
        writeRun(
            firingId, routineId, triggerInstantEpochMs, now, nowEpochMs(),
            if (anyFailed) RoutineRun.STATE_FAILED else RoutineRun.STATE_RAN,
            wasFallback, chainRootFiringId, depth,
            conditionCitations = citations,
            actionOutcomes = outcomes.joinToString("; ").take(RoutineRun.MAX_DETAIL_BYTES),
        )
        journal.complete(firingId)

        return if (anyFailed) FiringResult.Failed("an action failed") else FiringResult.Ran
    }

    // ---------------------------------------------------------------- actions

    private fun execute(
        action: Action,
        firingId: String,
        index: Int,
        chain: List<String>,
        depth: Int,
        chainRootFiringId: String,
    ): String = when (action) {
        is Action.SetPower -> issueCommand(
            firingId, index, action.deviceId, DeviceCommand.VERB_SET_POWER,
            if (action.on) DeviceCommand.ARG_ON else DeviceCommand.ARG_OFF,
        )

        is Action.SetLevel -> issueCommand(
            firingId, index, action.deviceId, DeviceCommand.VERB_SET_LEVEL,
            action.percent.coerceIn(0, 100).toString(),
        )

        is Action.Notify -> notify(action, firingId)

        is Action.RunRoutine -> {
            val target = loadRoutines().firstOrNull { it.routineId.get() == action.routineId }
            if (target == null) {
                "failed: unknown routine ${action.routineId}"
            } else {
                val prepared = prepare(target, ignoreEnabled = true)
                if (prepared == null) {
                    "failed: ${action.routineId} cannot run on this device"
                } else {
                    val childId = FiringIds.chained(chainRootFiringId, index)
                    val result = runFiring(
                        routine = target,
                        prepared = prepared,
                        triggerInstantEpochMs = nowEpochMs(),
                        wasFallback = false,
                        firingId = childId,
                        catchUpApplies = false,
                        chain = chain + action.routineId,
                        depth = depth + 1,
                        chainRootFiringId = chainRootFiringId,
                    )
                    "runRoutine(${action.routineId})=${result::class.simpleName}"
                }
            }
        }
    }

    /**
     * ⭐⭐ `commandId` is derived from the firing, which is what makes RE-D9's
     * self-trigger suppression free: the hub echoes it back as
     * `DeviceState.lastCommandId`, so a device-event trigger can recognise its
     * own routine's handiwork with **no schema change**. Adding an
     * `issuedForFiringId` field to the shipped `DeviceCommand` composite would
     * have been peer-breaking, for information the id already carries.
     */
    private fun issueCommand(
        firingId: String,
        index: Int,
        deviceId: String,
        verb: String,
        argValue: String,
    ): String {
        val command = DeviceCommand.create(
            context = executionContext.context,
            commandId = "firing:$firingId:$index",
            targetDeviceId = deviceId,
            verb = verb,
            argValue = argValue,
            issuedAtEpochMs = nowEpochMs(),
            issuedByDeviceId = thisDeviceId() ?: "",
        )
        executionContext.saveData(command)
        // ⚠ Pending, not Ok: the row is written, the physical outcome arrives
        // later on the DeviceState row (HD-3's vocabulary, inherited).
        return "$verb($deviceId)=Pending"
    }

    /**
     * ⚠⚠ **Deferred, deliberately, and it fails loudly rather than silently.**
     *
     * `:notification-service` has no general-purpose title/body notification
     * type — `AppNotification` is an app-lifecycle shape (`registered` /
     * `removed`), and `Notification` itself carries only `uuid` + `timestamp`.
     * A routine notification therefore needs a **new, proxy-authoritative**
     * notification type, which is a schema decision of its own and interacts
     * with the rule that the proxy must not render client-supplied UI (here the
     * text is the user's own realm data, which is arguably fine — but that is an
     * argument to make in a doc, not to assume in an engine).
     *
     * Until then the outcome is an honest failure. ⚠ The Home editor must gate
     * the Notify action, on the HD-2 precedent: *never offer a flow you cannot
     * honour*.
     */
    private fun notify(action: Action.Notify, firingId: String): String =
        "failed: notify is not supported yet (needs a proxy-authoritative " +
            "notification type); title=${action.title.take(40)}"

    // ---------------------------------------------------------------- arming

    /**
     * Re-arms the single next-wake item (RE-D5).
     *
     * ⭐ One `ONE_SHOT` item for the **earliest** upcoming firing across
     * everything this device is eligible for — including **fallback** instants,
     * or the grace fallback never fires.
     *
     * ⚠⚠ Must also be called on **roster change**, not only after a fire or an
     * edit: the armed set is a function of the roster, so a revoked hub silently
     * makes this device the executor for routines nothing re-armed it for
     * (§4.9). That wiring is the caller's job.
     */
    fun reArm() {
        val now = Instant.fromEpochMilliseconds(nowEpochMs())
        var earliest: Long? = null

        for (routine in loadRoutines()) {
            val prepared = prepare(routine) ?: continue
            val next = calculator.nextFiring(prepared.program.trigger, prepared.locale, now)
                ?: continue
            val at = next.toEpochMilliseconds() +
                if (prepared.isExecutor) 0 else DefaultSafetyGate.GRACE_MS
            if (earliest == null || at < earliest) earliest = at
        }

        armer?.invoke(earliest)
    }

    /** Set by the wiring; `null` in tests that only assert firing behaviour. */
    var armer: ((nextWakeEpochMs: Long?) -> Unit)? = null

    // ---------------------------------------------------------------- store access

    private fun routinesRef() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = RoutineService.Relations.ROUTINES,
        ),
        isEntity = false,
        elementSchemaId = Routine.ID,
    )

    private fun runsRef() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = RoutineService.Relations.ROUTINE_RUNS,
        ),
        isEntity = false,
        elementSchemaId = RoutineRun.ID,
    )

    private fun loadRoutines(): List<Routine> =
        executionContext.database.getArrayData(routinesRef())
            ?.toList()
            .orEmpty()
            .filterIsInstance<Routine>()

    private fun presenceRows(): List<DevicePresence> =
        executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = executionContext.context,
                    type = TList.ID,
                    relationId = RoutineService.Relations.PRESENCE_STATES,
                ),
                isEntity = false,
                elementSchemaId = PresenceState.ID,
            )
        )?.toList().orEmpty()
            .filterIsInstance<PresenceState>()
            .map {
                DevicePresence(
                    deviceId = it.deviceId.get(),
                    state = it.state.get(),
                    freshAsOfEpochMs = it.freshAsOfEpochMs.get(),
                )
            }

    private fun loadRun(firingId: String): RoutineRun? =
        executionContext.database.getData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = RoutineRun.ID,
                keyValue = firingId,
            )
        ) as? RoutineRun

    private fun takeSnapshot(): ConditionSnapshot {
        val states = executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = executionContext.context,
                    type = TList.ID,
                    relationId = "tenshu/home/device/service/deviceStates",
                ),
                isEntity = false,
                elementSchemaId = DeviceState.ID,
            )
        )?.toList().orEmpty().filterIsInstance<DeviceState>()

        val byId = states.associate { state ->
            state.deviceId.get() to ObservedState(
                deviceId = state.deviceId.get(),
                fields = mapOf(
                    "power" to when (state.power.get()) {
                        DeviceState.POWER_ON -> "on"
                        DeviceState.POWER_OFF -> "off"
                        else -> "n/a"
                    },
                    "online" to state.online.get().toString(),
                    "levelPercent" to state.levelPercent.get().toString(),
                    "temperatureDeciC" to state.temperatureDeciC.get().toString(),
                    "humidityPercent" to state.humidityPercent.get().toString(),
                    "batteryPercent" to state.batteryPercent.get().toString(),
                ),
                freshAsOfEpochMs = state.freshAsOf.get(),
            )
        }

        val takenAt = nowEpochMs()
        // RE-4: the household verdict, resolved from the per-device rows.
        // ⚠⚠ Still `null` whenever it cannot be established — a stale or unknown
        // contributor, or no contributors at all — and the evaluator then fails
        // the condition CLOSED. That is the decision, not a fallback.
        val occupancy = PresenceResolver(PresenceState.STALE_WINDOW_MS).resolve(
            contributors = presenceRows(),
            // A hub is definitionally always home, and a revoked device's last
            // known position must not keep deciding the house.
            excludedDeviceIds = roster()
                .filter { it.revoked || it.tier == CapabilityTier.ALWAYS_ON }
                .map { it.deviceId }
                .toSet(),
            nowEpochMs = takenAt,
        )
        return object : ConditionSnapshot {
            override val takenAtEpochMs = takenAt
            override fun deviceState(deviceId: String) = byId[deviceId]
            override fun presence(): Occupancy? = occupancy
        }
    }

    // ---------------------------------------------------------------- run rows

    @Suppress("LongParameterList")
    private fun writeRun(
        firingId: String,
        routineId: String,
        triggerInstantEpochMs: Long,
        claimedAt: Long,
        finishedAt: Long,
        state: String,
        wasFallback: Boolean,
        chainRootFiringId: String,
        depth: Int,
        conditionCitations: String = "",
        actionOutcomes: String = "",
        coalescedCount: Long = 0,
    ) {
        executionContext.saveData(
            RoutineRun.create(
                context = executionContext.context,
                firingId = firingId,
                routineId = routineId,
                triggerInstantEpochMs = triggerInstantEpochMs,
                executorDeviceId = thisDeviceId() ?: "",
                claimedAtEpochMs = claimedAt,
                finishedAtEpochMs = finishedAt,
                state = state,
                wasFallback = wasFallback,
                chainRootFiringId = chainRootFiringId,
                depth = depth.toLong(),
                conditionCitations = conditionCitations.take(RoutineRun.MAX_DETAIL_BYTES),
                actionOutcomes = actionOutcomes.take(RoutineRun.MAX_DETAIL_BYTES),
                coalescedCount = coalescedCount,
            )
        )
    }

    /**
     * ⚠⚠ Refusals **coalesce** (§4.9). RE-D9 calls the token bucket a *sync*
     * brake, and if every refused firing wrote its own row the brake would only
     * change a state field — a flapping sensor would flood the realm with
     * `THROTTLED` rows instead of `RAN` rows. The first refusal in a window
     * writes one row; the rest bump its counter.
     */
    private fun recordRefusal(firing: Firing, now: Long, reason: String): FiringResult {
        val open = safetyGate.openRefusalRowFor(firing.routineId)
        if (open == null) {
            writeRun(
                firing.id, firing.routineId, firing.triggerInstantEpochMs, now, now,
                RoutineRun.STATE_THROTTLED, firing.wasFallback, firing.chainRootFiringId,
                firing.depth, actionOutcomes = reason, coalescedCount = 0,
            )
            safetyGate.recordRefusalRow(firing.routineId, firing.id)
            return FiringResult.Throttled(firing.id)
        }
        patchRun(open) { it.copyWith(coalescedCount = it.coalescedCount.get() + 1) }
        return FiringResult.Throttled(open)
    }

    private fun patchRun(firingId: String, patch: (RoutineRun) -> RoutineRun) {
        val existing = loadRun(firingId) ?: return
        executionContext.saveData(patch(existing))
    }

    private fun RoutineRun.copyWith(
        state: String = this.state.get(),
        finishedAtEpochMs: Long = this.finishedAtEpochMs.get(),
        actionOutcomes: String = this.actionOutcomes.get(),
        coalescedCount: Long = this.coalescedCount.get(),
    ): RoutineRun = RoutineRun.create(
        context = executionContext.context,
        firingId = firingId.get(),
        routineId = routineId.get(),
        triggerInstantEpochMs = triggerInstantEpochMs.get(),
        executorDeviceId = executorDeviceId.get(),
        claimedAtEpochMs = claimedAtEpochMs.get(),
        finishedAtEpochMs = finishedAtEpochMs,
        state = state,
        wasFallback = wasFallback.get(),
        chainRootFiringId = chainRootFiringId.get(),
        depth = depth.get(),
        conditionCitations = conditionCitations.get(),
        actionOutcomes = actionOutcomes,
        coalescedCount = coalescedCount,
        extras = extras,
    )

    /**
     * ⚠⚠ Suspension writes [Routine.STATE_SUSPENDED], **never**
     * `enabled = false` — that would be indistinguishable from the user's own
     * toggle, would silently destroy their intent, and would leave them
     * re-enabling with no idea why it stopped (RE-D9).
     */
    private fun suspendRoutine(routine: Routine, reason: String) {
        if (routine.state.get() == Routine.STATE_SUSPENDED) return
        executionContext.saveData(
            Routine.create(
                context = executionContext.context,
                routineId = routine.routineId.get(),
                name = routine.name.get(),
                enabled = routine.enabled.get(),
                state = Routine.STATE_SUSPENDED,
                suspendedReason = reason,
                timeZoneId = routine.timeZoneId.get(),
                homeLatE5 = routine.homeLatE5.get(),
                homeLonE5 = routine.homeLonE5.get(),
                authorDeviceId = routine.authorDeviceId.get(),
                createdAtEpochMs = routine.createdAtEpochMs.get(),
                updatedAtEpochMs = nowEpochMs(),
                programJson = routine.programJson.get(),
                programVersion = routine.programVersion.get(),
                extras = routine.extras,
            )
        )
    }

    private fun List<Citation>.render(): String =
        joinToString("; ") { "${it.what}=${it.read}@${it.freshAsOfEpochMs}" }
            .take(RoutineRun.MAX_DETAIL_BYTES)

    private companion object {
        /** How far back a wake looks for the firing it woke for. */
        val HORIZON = kotlin.time.Duration.parse("2h")
    }
}
