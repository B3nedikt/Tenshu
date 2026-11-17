package org.tenshu.mia.plan

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.test.fail

/**
 * The question set as a ratchet, in both directions (`mia-query-layer.md` §23.5).
 *
 * - A [Status.SUPPORTED] case the planner no longer answers as expected is a **regression**.
 * - A [Status.GAP] case the planner now answers as expected is a **promotion owed**: flip it to
 *   SUPPORTED, so it can never silently regress again.
 *
 * ⚠ The matcher ([observe]) is the only part tied to today's `QueryPlan`. When `Query` lands
 * (MQ-D11), it is rewritten and [QuestionSet] is not.
 */
class QuestionSetTest {

    private val zone = TimeZone.UTC
    private val nowMs = QuestionSet.CLOCK.atTime(LocalTime(12, 0)).toInstant(zone).toEpochMilliseconds()

    @Test
    fun theQuestionSetHoldsItsRatchet() {
        val results = QuestionSet.cases.map { case ->
            val observed = observe(QueryPlanner.plan(case.utterance, nowMs, zone, MiaActionOffer.OFFERED))
            Triple(case, observed, observed == case.expected)
        }

        val regressions = results.filter { (case, _, ok) -> case.status == Status.SUPPORTED && !ok }
        val promotions = results.filter { (case, _, ok) -> case.status == Status.GAP && ok }
        if (regressions.isEmpty() && promotions.isEmpty()) return

        fail(
            buildString {
                appendLine(summary(results.map { it.first to it.third }))
                regressions.forEach { (case, observed, _) ->
                    appendLine("REGRESSION \"${case.utterance}\"\n  expected ${case.expected}\n  observed $observed")
                }
                promotions.forEach { (case, _, _) ->
                    appendLine("PROMOTE \"${case.utterance}\": now answered as expected; mark it SUPPORTED")
                }
            },
        )
    }

    private fun summary(results: List<Pair<Case, Boolean>>): String =
        results.groupBy { (case, _) -> "${case.lang}/${case.provenance}" }
            .entries.joinToString(", ") { (group, rows) -> "$group ${rows.count { it.second }}/${rows.size}" }

    /** Today's `QueryPlan` in the set's planner-neutral terms. */
    private fun observe(plan: QueryPlan): Expected = when (plan) {
        is QueryPlan.FindPhotos ->
            Expected(Src.PHOTOS, time = if (plan.window.startMs == 0L) null else span(plan.window))
        is QueryPlan.FindPhotosAtPlace -> Expected(Src.PHOTOS, place = plan.text)
        is QueryPlan.FindPhotosOfLabel -> Expected(Src.PHOTOS, content = plan.text)
        is QueryPlan.FindPhotosLike -> Expected(Src.PHOTOS, content = plan.text, contentKind = ContentKind.LIKE)
        is QueryPlan.FindFilesNamed -> Expected(Src.FILES, name = plan.text)
        is QueryPlan.FindPlaces -> Expected(Src.PLACES, place = plan.text)
        is QueryPlan.RecallMoments -> Expected(Src.TIMELINE, time = span(plan.window))
        is QueryPlan.RecallAtPlace ->
            Expected(Src.TIMELINE, place = plan.text, shape = if (plan.latestOnly) Shape.LAST else Shape.LIST)
        is QueryPlan.FindWorkouts -> Expected(Src.WORKOUTS, time = span(plan.window))
        is QueryPlan.QueryCalendar ->
            Expected(Src.CALENDAR, time = Span(plan.window.from, plan.window.to.plus(DatePeriod(days = 1))))
        is QueryPlan.FindDevices -> Expected(Src.DEVICES, name = plan.text)
        is QueryPlan.CreateEvent ->
            Expected(Src.CALENDAR, action = Action(Act.CREATE_EVENT, plan.title, at = plan.startLocal))
        is QueryPlan.ControlDevice ->
            Expected(Src.DEVICES, action = Action(Act.CONTROL_DEVICE, plan.text, on = plan.on))
        is QueryPlan.RunRoutine -> Expected(Src.ROUTINES, action = Action(Act.RUN_ROUTINE, plan.text))
        is QueryPlan.NoMatch -> Expected(null)
        // ⚠ The set runs OFFERED, so a withheld action means the planner misread the question.
        is QueryPlan.ActionWithheld -> Expected(null, name = "<ActionWithheld>")
    }

    private fun span(window: TimeWindow) = Span(
        Instant.fromEpochMilliseconds(window.startMs).toLocalDateTime(zone).date,
        Instant.fromEpochMilliseconds(window.endMsExclusive).toLocalDateTime(zone).date,
    )
}
