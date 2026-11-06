package app.tenshu.calendar.model

import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.Frequency
import org.tenshu.calendar.common.recurrence.RecurrenceRule
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence
import org.tenshu.calendar.common.recurrence.WeekDayNum
import org.tenshu.calendar.common.recurrence.format

/**
 * The recurrence choices an editor offers — the small set every stock calendar
 * shows, mapped to and from RFC 5545.
 *
 * ⚠⚠ **[CUSTOM] is the load-bearing member.** An imported rule can be anything,
 * and the editor must be able to *show* an event whose recurrence it cannot
 * offer as a choice without either lying about it or rewriting it. `CUSTOM`
 * means "there is a rule here that is not one of ours" and the UI's job is to
 * say so and leave it alone — `calendar-app.md` §3 D2's "refuse to edit rather
 * than silently rewrite".
 */
enum class RecurrencePreset {
    NEVER,
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    /** A rule we can display but not offer as a choice. */
    CUSTOM;

    companion object {

        /**
         * The presets an editor may *offer*, in picker order.
         *
         * ⚠⚠ [CUSTOM] is absent, and its absence is the whole point: it is a state an event can
         * already be in, never a choice a user can make. Offering it would let someone turn a rule
         * we understand into one we deliberately refuse to touch.
         */
        val OFFERABLE: List<RecurrencePreset> = listOf(NEVER, DAILY, WEEKLY, MONTHLY, YEARLY)

        /**
         * Classifies [occurrence]'s rule against the **series'** anchor — what the editor and
         * every read-only screen must agree on.
         *
         * ⚠⚠ Not the occurrence's own date. A weekly rule read on a moved occurrence would
         * otherwise classify as CUSTOM (its BYDAY names the original weekday): the editor would
         * refuse to edit its own rule, and a detail screen would show the raw `RRULE` while the
         * editor said "Weekly".
         */
        fun of(occurrence: ResolvedOccurrence): RecurrencePreset =
            classify(occurrence.recurrenceRule, seriesAnchorOf(occurrence))

        /**
         * The date the rule anchored [occurrence] on, before any override moved it.
         *
         * ⚠ `recurrenceId` is by definition the *original* start, which is why it is the right
         * thing to classify a rule against. Falls back to the effective start for a one-off,
         * where the two are the same anyway.
         */
        private fun seriesAnchorOf(occurrence: ResolvedOccurrence): LocalDate =
            runCatching { LocalDate.parse(occurrence.recurrenceId.substringBefore('T')) }
                .getOrNull() ?: occurrence.startLocal.date

        /**
         * Classifies a stored `RRULE`.
         *
         * ⚠ Only the *exact* shapes this editor emits map back to a named
         * preset. `FREQ=WEEKLY;INTERVAL=2` is weekly-ish but is not what
         * [toRrule] produces for [WEEKLY], so it classifies as [CUSTOM] — being
         * conservative here is what stops the editor quietly dropping the
         * interval when the user saves an unrelated field.
         */
        fun classify(rrule: String, anchor: LocalDate?): RecurrencePreset {
            val rule = RecurrenceRule.parse(rrule) ?: return NEVER
            if (!rule.isFullyUnderstood) return CUSTOM
            if (rule.interval != 1 || rule.count != null || rule.until != null) return CUSTOM
            if (rule.byMonth.isNotEmpty()) return CUSTOM

            return when (rule.frequency) {
                Frequency.DAILY ->
                    if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) DAILY else CUSTOM

                Frequency.WEEKLY -> {
                    val expected = anchor?.let { listOf(WeekDayNum(0, it.dayOfWeek)) }
                    if (rule.byMonthDay.isEmpty() && (rule.byDay.isEmpty() || rule.byDay == expected)) {
                        WEEKLY
                    } else {
                        CUSTOM
                    }
                }

                Frequency.MONTHLY ->
                    if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) MONTHLY else CUSTOM

                Frequency.YEARLY ->
                    if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) YEARLY else CUSTOM
            }
        }
    }
}

/**
 * What a picker calls this preset.
 *
 * ⚠ Shared rather than per-renderer: the Compose editor had this as a private `when`, and the
 * SwiftUI one would have had a second copy — two lists of user-visible words that can disagree,
 * which is how "Yearly" becomes "Annually" on one platform.
 *
 * ⚠ Not localised, the same known gap `ReminderPresets` records: this really is copy, so it
 * belongs in the ADR 0016 catalogue rather than a date formatter. Every string in Calendar is in
 * the same position.
 */
fun RecurrencePreset.label(): String = when (this) {
    RecurrencePreset.NEVER -> "Never"
    RecurrencePreset.DAILY -> "Daily"
    RecurrencePreset.WEEKLY -> "Weekly"
    RecurrencePreset.MONTHLY -> "Monthly"
    RecurrencePreset.YEARLY -> "Yearly"
    RecurrencePreset.CUSTOM -> "Custom"
}

/**
 * The `RRULE` a preset stores, given the event's start date.
 *
 * ⚠ [RecurrencePreset.CUSTOM] returns `null` — it is not a choice a user can
 * *make*, only one they can already be in, so an editor showing it must keep
 * the event's existing rule rather than asking this for one.
 *
 * ⭐ `WEEKLY` writes an explicit `BYDAY` from the anchor rather than relying on
 * the implicit "take it from DTSTART" rule. Both expand identically today, but
 * the explicit form survives the user later moving the event to another weekday
 * — the implicit one would silently follow the move, which is not what "every
 * Tuesday" meant.
 */
fun RecurrencePreset.toRrule(anchor: LocalDate): String? = when (this) {
    RecurrencePreset.NEVER -> ""
    RecurrencePreset.CUSTOM -> null
    RecurrencePreset.DAILY -> RecurrenceRule(Frequency.DAILY).format()
    RecurrencePreset.WEEKLY -> RecurrenceRule(
        frequency = Frequency.WEEKLY,
        byDay = listOf(WeekDayNum(0, anchor.dayOfWeek)),
    ).format()
    RecurrencePreset.MONTHLY -> RecurrenceRule(Frequency.MONTHLY).format()
    RecurrencePreset.YEARLY -> RecurrenceRule(Frequency.YEARLY).format()
}
