package org.tenshu.routine.common.program

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parsed form of `Routine.programJson` (`routines-engine.md` §4.1).
 *
 * ⚠⚠ **These are code-side types, NOT schema types.** They are deliberately
 * *not* registered with the schema DSL: positional composites make every future
 * trigger or action kind a peer-breaking change, and the vocabulary is
 * guaranteed to grow (`home-and-mia-apps.md` D3 requires it to, so post-Rei
 * Skills can contribute actions). RE-D10 is the decision; this file is its
 * consequence.
 *
 * The `@SerialName` discriminators **are** the wire vocabulary. Adding a member
 * is a [PROGRAM_VERSION] bump, never a schema change — which is the whole point.
 */
@Serializable
class RoutineProgram(
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>,
)

/** Current vocabulary version this engine authors and understands. */
const val PROGRAM_VERSION: Long = 1

/**
 * ⭐ Three of the four trigger classes have exactly one eligible observer *by
 * construction*, which is what confines the multi-device problem to time and
 * sun (RE-D1). The engine on a device simply does not arm a routine whose
 * trigger it cannot observe.
 */
@Serializable
sealed interface Trigger {

    /**
     * @param minuteOfDay 0..1439, in the routine's own timezone (RE-D3).
     * @param days ISO day numbers (1 = Monday); empty means every day.
     */
    @Serializable
    @SerialName("timeOfDay")
    class TimeOfDay(
        val minuteOfDay: Int,
        val days: Set<Int> = emptySet(),
    ) : Trigger

    /** Sunrise/sunset ± [offsetMinutes], at the routine's own coordinates (RE-D3). */
    @Serializable
    @SerialName("sun")
    class Sun(
        val event: SunEvent,
        val offsetMinutes: Int = 0,
    ) : Trigger

    /** ⚠ Observable only in the process hosting the bridge — RE-2, editor-gated in RE-1. */
    @Serializable
    @SerialName("deviceEvent")
    class DeviceEvent(
        val deviceId: String,
        val match: StateMatch,
    ) : Trigger

    /** ⚠ RE-4. Only a device with a location capture source can observe it. */
    @Serializable
    @SerialName("geofence")
    class Geofence(
        val placeId: String,
        val direction: GeofenceDirection,
    ) : Trigger

    /** ⚠ RE-5. Arrives as an explicit invocation, not a background evaluation. */
    @Serializable
    @SerialName("voicePhrase")
    class VoicePhrase(val phrase: String) : Trigger
}

@Serializable
enum class SunEvent { SUNRISE, SUNSET }

@Serializable
enum class GeofenceDirection { ARRIVE, LEAVE }

@Serializable
enum class Occupancy { NOBODY_HOME, SOMEBODY_HOME }

/** A predicate over one field of a device's state row. */
@Serializable
class StateMatch(
    val field: String,
    val op: MatchOp = MatchOp.EQ,
    val value: String,
)

@Serializable
enum class MatchOp { EQ, NEQ, LT, GT }

@Serializable
sealed interface Condition {

    /**
     * @param fromMinuteOfDay inclusive, @param toMinuteOfDay exclusive, in the
     *   routine's timezone. A window that wraps midnight (`from > to`) is legal
     *   and means "either side of midnight".
     */
    @Serializable
    @SerialName("timeWindow")
    class TimeWindow(
        val fromMinuteOfDay: Int,
        val toMinuteOfDay: Int,
    ) : Condition

    @Serializable
    @SerialName("deviceStateIs")
    class DeviceStateIs(
        val deviceId: String,
        val match: StateMatch,
    ) : Condition

    /**
     * ⚠⚠ RE-4, and **fail-closed** when unknown (RE-D7). There is no presence
     * notion in the codebase yet, so RE-1 gates this in the editor; an engine
     * that meets one anyway returns `Unknown` and does not run — "arm the alarm
     * when nobody's home" firing because a phone battery died is the worst
     * failure mode of this entire engine.
     */
    @Serializable
    @SerialName("presence")
    class Presence(val expect: Occupancy) : Condition
}

/**
 * ⚠⚠ **The action vocabulary is closed and entirely local. There is no webhook,
 * no HTTP action, and there never will be one without a separate egress
 * decision** (RE-D8).
 *
 * ⭐ That is what makes "routines execute locally on the proxy — no cloud
 * round-trip" a *structural* property rather than an aspiration: IFTTT is
 * webhooks; this engine cannot reach the network even if a routine asked it to.
 * It also matches the posture the apps already take at the OS boundary — Home,
 * Maps and Timeline ship with `INTERNET` stripped; all egress is Control's.
 */
@Serializable
sealed interface Action {

    /**
     * ⚖️ **Absolute, and `toggle` is deliberately absent** — it expresses a flip
     * relative to a reading that may already be stale, and the two disagree
     * exactly when it matters. For an engine that may fire twice under RE-D2's
     * race, absoluteness is not a nicety, it is the containment (HD-3's
     * `setPower`-not-`toggle` reasoning, inherited unchanged).
     */
    @Serializable
    @SerialName("setPower")
    class SetPower(
        val deviceId: String,
        val on: Boolean,
    ) : Action

    /** @param percent 0..100. */
    @Serializable
    @SerialName("setLevel")
    class SetLevel(
        val deviceId: String,
        val percent: Int,
    ) : Action

    /** Dedupe key is derived from the `firingId`, so a duplicated firing collapses to one. */
    @Serializable
    @SerialName("notify")
    class Notify(
        val title: String,
        val body: String,
    ) : Action

    /** Chained through the same engine, under the RE-D8 depth and cycle guards. */
    @Serializable
    @SerialName("runRoutine")
    class RunRoutine(val routineId: String) : Action
}
