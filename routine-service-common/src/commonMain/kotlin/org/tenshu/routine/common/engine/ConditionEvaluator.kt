package org.tenshu.routine.common.engine

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.tenshu.routine.common.program.Condition
import org.tenshu.routine.common.program.MatchOp
import org.tenshu.routine.common.program.Occupancy
import org.tenshu.routine.common.program.StateMatch

/** One field of one device's state, as the snapshot saw it. */
class ObservedState(
    val deviceId: String,
    val fields: Map<String, String>,
    val freshAsOfEpochMs: Long,
)

/**
 * A single reading, taken **once** immediately before the first action
 * (`routines-engine.md` RE-D7).
 *
 * ⚠ Trigger-time evaluation would be wrong ("only after sunset" must hold when
 * the light actually goes on), and per-action evaluation would be worse —
 * action 3 would see a world that action 1 changed, which is the seed of the
 * feedback loops RE-D9 exists to prevent.
 */
interface ConditionSnapshot {
    val takenAtEpochMs: Long
    fun deviceState(deviceId: String): ObservedState?

    /** ⚠ `null` means unknown, which fails closed. RE-4 supplies a real one. */
    fun presence(): Occupancy?
}

/** What a condition read, and how stale it was (RE-D7). */
class Citation(
    val what: String,
    val read: String,
    val freshAsOfEpochMs: Long,
)

/**
 * ⭐ **[Unknown] is a third verdict, deliberately distinct from [NotMet].** This
 * is RE-D7's fail-closed rule made structural: a two-valued verdict is exactly
 * how "arm the alarm when nobody's home" fires because someone's phone battery
 * died — the worst possible failure mode of this entire engine.
 *
 * ⚠ Citations ride on *every* verdict, including [Unknown]. An unexplained
 * non-run is the support ticket RE-D7 exists to avoid.
 */
sealed interface ConditionVerdict {
    val citations: List<Citation>

    class Met(override val citations: List<Citation>) : ConditionVerdict
    class NotMet(override val citations: List<Citation>) : ConditionVerdict
    class Unknown(
        override val citations: List<Citation>,
        val reason: String,
    ) : ConditionVerdict
}

fun interface ConditionEvaluator {
    fun evaluate(
        conditions: List<Condition>,
        snapshot: ConditionSnapshot,
        locale: RoutineLocale,
    ): ConditionVerdict
}

class DefaultConditionEvaluator : ConditionEvaluator {

    override fun evaluate(
        conditions: List<Condition>,
        snapshot: ConditionSnapshot,
        locale: RoutineLocale,
    ): ConditionVerdict {
        val citations = mutableListOf<Citation>()
        if (conditions.isEmpty()) return ConditionVerdict.Met(citations)

        var allMet = true
        for (condition in conditions) {
            when (val verdict = evaluateOne(condition, snapshot, locale, citations)) {
                // ⚠ Fail closed on the FIRST unknown: a later condition being
                // false cannot rescue an unknown one, and reporting the unknown
                // is more useful than reporting the false.
                is ConditionVerdict.Unknown -> return verdict
                is ConditionVerdict.NotMet -> allMet = false
                is ConditionVerdict.Met -> Unit
            }
        }
        return if (allMet) ConditionVerdict.Met(citations) else ConditionVerdict.NotMet(citations)
    }

    private fun evaluateOne(
        condition: Condition,
        snapshot: ConditionSnapshot,
        locale: RoutineLocale,
        citations: MutableList<Citation>,
    ): ConditionVerdict = when (condition) {

        is Condition.TimeWindow -> {
            val zone = runCatching { TimeZone.of(locale.timeZoneId) }.getOrNull()
            if (zone == null) {
                ConditionVerdict.Unknown(citations.toList(), "unresolvable timezone ${locale.timeZoneId}")
            } else {
                val local = Instant.fromEpochMilliseconds(snapshot.takenAtEpochMs)
                    .toLocalDateTime(zone)
                val minuteOfDay = local.hour * 60 + local.minute
                citations += Citation(
                    what = "timeWindow",
                    read = "local minuteOfDay=$minuteOfDay",
                    freshAsOfEpochMs = snapshot.takenAtEpochMs,
                )
                val inWindow = if (condition.fromMinuteOfDay <= condition.toMinuteOfDay) {
                    minuteOfDay >= condition.fromMinuteOfDay && minuteOfDay < condition.toMinuteOfDay
                } else {
                    // Wraps midnight — "after sunset until morning" is the common case.
                    minuteOfDay >= condition.fromMinuteOfDay || minuteOfDay < condition.toMinuteOfDay
                }
                if (inWindow) ConditionVerdict.Met(citations.toList())
                else ConditionVerdict.NotMet(citations.toList())
            }
        }

        is Condition.DeviceStateIs -> {
            val state = snapshot.deviceState(condition.deviceId)
            if (state == null) {
                ConditionVerdict.Unknown(
                    citations.toList(),
                    "no state row for device ${condition.deviceId}",
                )
            } else {
                val actual = state.fields[condition.match.field]
                citations += Citation(
                    what = "${condition.deviceId}.${condition.match.field}",
                    read = actual ?: "<absent>",
                    freshAsOfEpochMs = state.freshAsOfEpochMs,
                )
                if (actual == null) {
                    ConditionVerdict.Unknown(
                        citations.toList(),
                        "device ${condition.deviceId} does not report ${condition.match.field}",
                    )
                } else if (matches(actual, condition.match)) {
                    ConditionVerdict.Met(citations.toList())
                } else {
                    ConditionVerdict.NotMet(citations.toList())
                }
            }
        }

        is Condition.Presence -> {
            val presence = snapshot.presence()
            citations += Citation(
                what = "presence",
                read = presence?.name ?: "<unknown>",
                freshAsOfEpochMs = snapshot.takenAtEpochMs,
            )
            when {
                // ⚠⚠ RE-1 has no presence notion at all, so this is the path a
                // routine takes if the editor gate is ever bypassed. Failing
                // closed here is not defensive coding, it is the decision.
                presence == null -> ConditionVerdict.Unknown(
                    citations.toList(),
                    "presence is unknown (needs RE-4); failing closed",
                )

                presence == condition.expect -> ConditionVerdict.Met(citations.toList())
                else -> ConditionVerdict.NotMet(citations.toList())
            }
        }
    }

    private fun matches(actual: String, match: StateMatch): Boolean = when (match.op) {
        MatchOp.EQ -> actual == match.value
        MatchOp.NEQ -> actual != match.value
        MatchOp.LT -> compareNumeric(actual, match.value) { a, b -> a < b }
        MatchOp.GT -> compareNumeric(actual, match.value) { a, b -> a > b }
    }

    private fun compareNumeric(
        actual: String,
        expected: String,
        compare: (Double, Double) -> Boolean,
    ): Boolean {
        val a = actual.toDoubleOrNull() ?: return false
        val b = expected.toDoubleOrNull() ?: return false
        return compare(a, b)
    }
}
