package org.tenshu.mia.proxy.impl

import kotlinx.datetime.TimeZone
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.core.dataFactory
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.get
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.isTrashedPath
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.core.number.TInteger
import org.tenshu.mia.AskMia
import org.tenshu.mia.proxy.MiaPhotoSearchHome
import kotlinx.datetime.LocalDateTime
import org.tenshu.calendar.ResolvedEventOccurrence
import org.tenshu.mia.MiaAction
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.mia.MiaSource
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineService
import org.tenshu.mia.MiaAnswer
import org.tenshu.core.health.Workout
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.common.recurrence.OccurrenceId
import org.tenshu.core.place.Place
import org.tenshu.inference.ClassifyIntent
import org.tenshu.inference.IntentCandidate
import org.tenshu.inference.IntentChoice
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.inference.SimilarPhoto
import org.tenshu.inference.INDEX_MARKER_LABEL
import org.tenshu.core.timeline.Moment
import org.tenshu.home.device.HomeDevice
import org.tenshu.mia.MiaHit
import org.tenshu.mia.plan.MiaActionOffer
import org.tenshu.mia.plan.MiaIntent
import org.tenshu.mia.plan.QueryPlan
import org.tenshu.mia.plan.QueryPlanner
import org.tenshu.mia.proxy.MiaActionRegistry
import org.tenshu.mia.proxy.MiaChainValidator
import org.tenshu.mia.proxy.CalendarSlots
import org.tenshu.mia.proxy.PendingMiaAction
import org.tenshu.mia.proxy.canWrite
import org.tenshu.mia.proxy.dispatchOperationForResult
import org.tenshu.mia.proxy.QueryPlanLowering
import org.tenshu.mia.proxy.readMiaGrants

/**
 * The whole turn, in the order the pipeline reads (`mia-query-layer.md` §1):
 *
 * ```
 * utterance --planner--> QueryPlan --lowering--> callstack --validator--> execute --> MiaAnswer
 * ```
 *
 * Each stage is separately testable and separately replaceable — which is the point of
 * the shape rather than a side effect of it. A model arrives at stage one (MQ-5) and
 * meets stages three onward unchanged.
 *
 * ⚠ **Every failure below produces a typed answer, never a bare error.** A refusal that
 * arrives as `error/internal` is indistinguishable from a broken proxy, and the chat
 * surface has no way to say anything useful about it. The three verdicts on [MiaAnswer]
 * exist so "I did not understand", "I understood and found nothing" and "I could not
 * look" stay three different sentences.
 */
internal class AskMiaImplementation(
    private val actions: MiaActionRegistry,
    private val actionOffer: MiaActionOffer,
    /** Which app the photo refusals name (§1.8). */
    private val photoSearch: MiaPhotoSearchHome,
    private val newToken: () -> String,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val context = executionContext.context
        val utterance = (params?.get(AskMia.Params.UTTERANCE) as? TString)?.get().orEmpty()
        val nowMs = (params?.get(AskMia.Params.NOW_EPOCH_MS) as? TInteger)?.get() ?: 0L
        val zoneId = (params?.get(AskMia.Params.TIME_ZONE_ID) as? TString)?.get().orEmpty()

        val granted = executionContext.readMiaGrants()
        val plan = planFor(
            executionContext = executionContext,
            implementationFactory = implementationFactory,
            utterance = utterance,
            nowMs = nowMs,
            zone = zoneOf(zoneId),
            granted = granted,
        )

        // ⚠⚠ NOT `ANSWERED`: nothing was searched, and an empty `ANSWERED` renders as "… —
        // nothing matched." (§14.1). `NO_MATCH` is the verdict the neighbouring refusals use for
        // "understood, and not going to" — the write grant, a suspended routine.
        if (plan is QueryPlan.ActionWithheld) {
            return answer(executionContext, MiaAnswer.VERDICT_NO_MATCH, plan.explanation) as T
        }

        // MQ-D4a: a write never lowers, so it is settled before the chain path.
        if (plan is QueryPlan.CreateEvent) {
            return eventConfirmation(executionContext, plan, zoneOf(zoneId)) as T
        }
        // RE-5, same rule: the name resolution is a direct row read, not a chain.
        if (plan is QueryPlan.RunRoutine) {
            return routineConfirmation(executionContext, plan, granted) as T
        }

        val callstack = QueryPlanLowering(context).lower(plan)
            ?: return answer(executionContext, MiaAnswer.VERDICT_NO_MATCH, plan.explanation) as T

        when (val verdict = MiaChainValidator.validate(callstack, QueryPlanLowering.policyFor(granted))) {
            is MiaChainValidator.Verdict.Refused -> {
                // ⚠ Named, because in v1 this line means the *planner* emitted something
                // the policy forbids — a bug on our side, not a user error. It should
                // never appear; if it does, it is the most interesting line in the log.
                println("Mia: chain refused by the validator — ${verdict.reason}")
                return answer(executionContext, MiaAnswer.VERDICT_UNAVAILABLE, UNAVAILABLE_TEXT) as T
            }

            MiaChainValidator.Verdict.Allowed -> Unit
        }

        val task = context.dataFactory().createList<Data>(callstack)
        val result = LocalTaskExecutor(executionContext).executeAsync(task)

        // ⭐ Track A stage two: the chain above resolved WHERE, not what. It needs the raw
        // `Place` rows (their coordinates), which `MiaHit` deliberately does not carry.
        if (plan is QueryPlan.FindPhotosAtPlace && result is TResult.Success<*>) {
            return photosAtPlace(executionContext, plan, granted, result.value as? Data) as T
        }

        // ⭐ Track B: the same join, reading moments instead of photos.
        if (plan is QueryPlan.RecallAtPlace && result is TResult.Success<*>) {
            return recallAtPlace(executionContext, plan, granted, result.value as? Data) as T
        }

        // ⭐ The calendar read: one op call, and the occurrences come back resolved (§15.1).
        if (plan is QueryPlan.QueryCalendar && result is TResult.Success<*>) {
            return queryCalendar(executionContext, plan, result.value as? Data) as T
        }

        val hits = when (result) {
            // ⚠⚠ P0g: a label or visual-search row names a path, and nothing deletes it when the
            // file goes — so those two plans are cited only once the file plane says the path is
            // live. `null` is "could not look", never "nothing matched" (§18).
            is TResult.Success<*> if plan is QueryPlan.FindPhotosOfLabel || plan is QueryPlan.FindPhotosLike ->
                liveCitations(executionContext, plan, granted, result.value as? Data)
                    ?: return answer(executionContext, MiaAnswer.VERDICT_UNAVAILABLE, UNAVAILABLE_TEXT) as T

            is TResult.Success<*> -> hitsOf(executionContext, result.value as? Data)
            else -> {
                val error = (result as? TResult.Failure<*>)?.error
                // ⚠⚠ MQ-4: the ONE failure a user can act on. IN-4b made `photosLike` throw rather
                // than return an empty list precisely so "semantic search is switched off" would
                // not arrive looking like "you have no photos of that — and flattening it into the
                // generic line here would have thrown that away one layer above where it was
                // earned. The reasons are separated because they need different responses.
                (error as? SemanticSearchUnavailable)?.let {
                    return answer(
                        executionContext,
                        MiaAnswer.VERDICT_UNAVAILABLE,
                        semanticUnavailableText(it.reason.get(), photoSearch),
                    ) as T
                }
                println("Mia: chain execution failed for plan ${plan::class.simpleName} — $error")
                return answer(executionContext, MiaAnswer.VERDICT_UNAVAILABLE, UNAVAILABLE_TEXT) as T
            }
        }

        // MQ-D7: a mutating plan stops here. The chain above RESOLVED which device the user
        // meant — a read — and what comes back is something to tap, not something done.
        if (plan is QueryPlan.ControlDevice) {
            return confirmation(executionContext, plan, hits) as T
        }

        return answer(executionContext, MiaAnswer.VERDICT_ANSWERED, plan.explanation, hits) as T
    }

    /**
     * Stage two of the place join: photos near the places stage one found.
     *
     * ⚠⚠ **Searches around EVERY matching place, not just the best one.** "Munich" legitimately
     * matches several rows — a venue, a station, a district — and picking one would silently
     * answer about a corner of the city. Results are merged and de-duplicated by path.
     *
     * ⚠ The radius is deliberately generous ([PLACE_RADIUS_M]) and the same for every place:
     * `Place.accuracyM` describes how precisely the *dwell* was located, not how large the venue
     * is, so scaling by it would make a poor indoor fix search a wider area than a clean one —
     * exactly backwards.
     */
    private suspend fun photosAtPlace(
        executionContext: ExecutionContext,
        plan: QueryPlan.FindPhotosAtPlace,
        granted: Set<MiaSource>,
        placeRows: Data?,
    ): MiaAnswer = placeJoin(
        executionContext = executionContext,
        granted = granted,
        placeRows = placeRows,
        limit = plan.limit,
        unknownPlace = "I don't know a place called \u201C${plan.text}\u201D, so I can't tell " +
            "which photos were taken there.",
        // ⚠ Names the places it searched AROUND rather than implying it swept a city — the
        // honest description of what this actually did.
        explanation = { places -> "${plan.explanation} (near ${places.joinToString(", ")})" },
        stageTwo = { lowering, place ->
            lowering.lowerPhotosNear(
                latE7 = place.latE7.get(),
                lngE7 = place.lngE7.get(),
                radiusM = PLACE_RADIUS_M,
                limit = plan.limit,
            )
        },
    )

    /**
     * "What did I do in Paris" / "when was I last at the gym" (TB-D5).
     *
     * ⚠⚠ **Recency is re-established across places.** Each place's chain returns its own moments
     * newest-first, but "when was I *last* there" has one true answer across all of them — so the
     * merged list is re-sorted by time before the limit bites. Taking the first row of a
     * concatenation would answer about whichever place happened to resolve first.
     */
    private suspend fun recallAtPlace(
        executionContext: ExecutionContext,
        plan: QueryPlan.RecallAtPlace,
        granted: Set<MiaSource>,
        placeRows: Data?,
    ): MiaAnswer = placeJoin(
        executionContext = executionContext,
        granted = granted,
        placeRows = placeRows,
        limit = plan.limit,
        unknownPlace = "I don't know a place called \u201C${plan.text}\u201D, so I can't tell " +
            "you when you were there.",
        explanation = { places -> "${plan.explanation} (${places.joinToString(", ")})" },
        newestFirst = true,
        stageTwo = { lowering, place ->
            lowering.lowerMomentsAtPlace(place.placeId.get(), plan.limit)
        },
    )

    /**
     * The **place join**, shared by both of its users: stage one's rows in, stage two's citations
     * out (`mia-query-layer.md` §12.1).
     *
     * ⚠⚠ **Searches around EVERY matching place, not just the best one.** One name legitimately
     * matches several rows — a venue, a station, a district — and picking one would silently
     * answer about a corner of the city. Results merge and de-duplicate by reference.
     *
     * ⚠ Stage two is validated too. A second chain is not a second trust boundary.
     *
     * ⚠ An unknown name is ANSWERED, not refused, and says so: "I found nothing" and "I don't
     * know that place" are different answers, and conflating them is what makes an assistant feel
     * like it is lying.
     */
    private suspend fun placeJoin(
        executionContext: ExecutionContext,
        granted: Set<MiaSource>,
        placeRows: Data?,
        limit: Int,
        unknownPlace: String,
        explanation: (List<String>) -> String,
        newestFirst: Boolean = false,
        stageTwo: (QueryPlanLowering, Place) -> List<Data>,
    ): MiaAnswer {
        val places = (placeRows as? TList<*>)?.toList().orEmpty().filterIsInstance<Place>()
        if (places.isEmpty()) {
            // ⚠⚠ **NOT `ANSWERED`, which is what shipped and what §14.1 caught on a device.**
            // Stage one resolved no place, so stage two never ran and nothing was searched — this
            // is "I could not look", and an `ANSWERED` with no hits means "I looked and there is
            // nothing". The UI renders the difference: `TenshuConversationRepository` appends
            // " — nothing matched." to an empty `ANSWERED`, which turned this refusal into
            // *"I don't know a place called “paris” … — nothing matched."* — the exact conflation
            // §12.2 rejects, assembled from two layers that were each individually right.
            //
            // ⚠ `NO_MATCH` rather than `UNAVAILABLE` because this is a *resolution* refusal, the
            // same shape as "I couldn't find a device called X" and "I don't know which calendar
            // to use". `UNAVAILABLE` stays what it is elsewhere in this file: the validator said
            // no, or execution failed.
            return answer(executionContext, MiaAnswer.VERDICT_NO_MATCH, unknownPlace)
        }

        val lowering = QueryPlanLowering(executionContext.context)
        val executor = LocalTaskExecutor(executionContext)
        val policy = QueryPlanLowering.policyFor(granted)
        val byReference = linkedMapOf<String, MiaHit>()

        for (place in places) {
            val callstack = stageTwo(lowering, place)
            if (MiaChainValidator.validate(callstack, policy) !is MiaChainValidator.Verdict.Allowed) {
                continue
            }
            val task = executionContext.context.dataFactory().createList<Data>(callstack)
            val result = executor.executeAsync(task)
            if (result !is TResult.Success<*>) continue
            hitsOf(executionContext, result.value as? Data).forEach { hit ->
                // ⚠ `getOrPut`, not `putIfAbsent`: the latter is a JVM-only extension over
                // `java.util.Map` and does not resolve on Kotlin/JS, which is a compile error in a
                // `commonMain` file. Same first-wins semantics — the value is non-null, so the two
                // differ only in what they return, and neither return is read.
                byReference.getOrPut(hit.dedupeKey()) { hit }
            }
            // ⚠ Only safe to stop early when the order is per-place. A recency question has to
            // see every place's answer before it knows which one is the latest.
            if (!newestFirst && byReference.size >= limit) break
        }

        val found = byReference.values.toList()
            .let { hits -> if (newestFirst) hits.sortedByDescending { it.at.get() } else hits }
            .take(limit)

        return answer(
            executionContext,
            MiaAnswer.VERDICT_ANSWERED,
            explanation(places.take(3).map { it.label.get() }),
            found,
        )
    }

    /**
     * The plan for this turn: **the grammar, unless a model has an opinion** (MQ-5, §16).
     *
     * ⭐⭐ **The deterministic planner runs either way, and that is the design rather than a
     * safeguard bolted on.** MQ-D9 requires every intent to keep working with the model off, so the
     * grammar cannot become a fallback that is only exercised when something breaks — it is the
     * answer, and the model's contribution is to *override* it when it recognises a question the
     * vocabulary missed. A model that is absent, unconsented, silent, or wrong about what the
     * sentence contains changes nothing the user sees.
     *
     * ⚠⚠ **Only consulted for the two plans that mean "I could not place this"** — see
     * [isSecondGuessable]. Asking on every turn would let a model's opinion beat a working parse
     * ("photos from last summer" is *already* answered correctly) and would spend an inference run
     * per question to change nothing.
     *
     * ⚠ No `println` on the model path and no marker in the answer: what the user is shown is the
     * plan's own explanation, which already says what was searched (MQ-D3). A sentence about which
     * planner ran would be about us, not about their question.
     */
    private suspend fun planFor(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        utterance: String,
        nowMs: Long,
        zone: TimeZone,
        granted: Set<MiaSource>,
    ): QueryPlan {
        val deterministic = QueryPlanner.plan(utterance, nowMs, zone, actionOffer, granted)
        if (!isSecondGuessable(deterministic)) return deterministic

        val chosen = classifyIntent(executionContext, implementationFactory, utterance)
            ?: return deterministic

        // ⚠ `planForIntent` returns null when the utterance cannot fill the intent the model
        // chose — a wrong guess — and the grammar's own answer stands.
        return QueryPlanner.planForIntent(utterance, nowMs, zone, chosen, granted) ?: deterministic
    }

    /**
     * The two plans a model is allowed to overrule, and nothing else (§16.3).
     *
     * ⭐⭐ **Both mean the same thing — "the vocabulary did not place this sentence" — and this
     * track has measured what that costs.** [QueryPlan.NoMatch] says so honestly. The catch-all
     * file-name arm says nothing at all: it is where "my runs last week" (§14.4) and "what is on my
     * calendar tomorrow" (§14.5) each ended up, answered confidently and wrongly, because any
     * leftover text becomes a filename search. Those are exactly the sentences worth a second
     * opinion, and every *other* plan was reached through a positive signal — a photo word, a
     * device noun, an anchored place, a time window — which a model has no business overturning.
     *
     * ⚠ A quoted phrase or an explicit "file"/"document" is a positive signal, so `viaCatchAll` is
     * false there and those searches stand.
     */
    private fun isSecondGuessable(plan: QueryPlan): Boolean = when (plan) {
        is QueryPlan.NoMatch -> true
        is QueryPlan.FindFilesNamed -> plan.viaCatchAll
        else -> false
    }

    /**
     * Ask `:inference-service` which intent this is, or `null` for "no opinion".
     *
     * ⚠⚠ **A direct dispatch, because there is no plan yet to lower.** Every other cross-service
     * read on this track rides a validated chain — but a chain is what a *plan* compiles to, and
     * this call is what produces the plan, so the chain path is not available by construction. It
     * is the same shape as MQ-3a/3b's direct calls, through the same helper, for the same reason:
     * a direct call has to know the implementation's kind and guessing has cost this track twice.
     *
     * ⚠ Failure of any sort — absent op, thrown error, empty result, an id we do not recognise —
     * is `null`. There is nothing here a user could act on, so there is nothing to report.
     */
    private suspend fun classifyIntent(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        utterance: String,
    ): MiaIntent? {
        val context = executionContext.context
        val candidates = MiaIntent.selectable.map {
            IntentCandidate.create(context = context, intentId = it.id, description = it.description)
        }

        val result = runCatchingCancellable {
            dispatchOperationForResult(
                operationId = ClassifyIntent.ID,
                executionContext = executionContext,
                implementationFactory = implementationFactory,
                params = mapOf(
                    ClassifyIntent.Params.UTTERANCE to utterance.toTString(context),
                    ClassifyIntent.Params.CANDIDATES to
                        context.dataFactory().createList<Data>(candidates),
                ),
            )
        }.getOrNull()

        val chosenId = (result as? TList<*>)?.toList().orEmpty()
            .filterIsInstance<IntentChoice>()
            .firstOrNull()
            ?.intentId?.get()
            ?: return null

        // ⚠⚠ Checked again on this side — and **no test can tell this line from a no-op**, which is
        // worth stating rather than leaving for someone to discover. Both checks read the same
        // candidate list, so an id the op would accept is always one `byId` resolves; deleting this
        // line keeps every test green (measured). It stays because the two checks stop being
        // redundant the moment anything else calls that op with a different list, and a boundary
        // that depends on a *caller elsewhere* being careful is not one.
        return MiaIntent.byId(chosenId)
    }

    /**
     * The calendar read (§15) — **occurrences in, occurrences out**.
     *
     * ⭐⭐ **The expansion is no longer here.** It used to be: two unfiltered chains (series, then
     * overrides) and an `OccurrenceResolver` fold in this method, capped at 200 *series* by the
     * validator's row ceiling — with no safe order to cap by, because a series stores its **first**
     * occurrence, so any "keep the newest" drops the old weekly meeting that still happens
     * tomorrow (§15.1). `queryOccurrences` moved the scan behind the calendar's own op, where it
     * needs no chain bound, and this method reads the answer.
     *
     * ⚠ The *resolver* did not move — it is still `:calendar-service-common`'s, still the same code
     * the Calendar app and the reminder scheduler fold with, so an agenda in Mia and an agenda in
     * Calendar cannot disagree about what recurs. What went away is Mia's copy of the plumbing
     * around it, including the override read whose failure this method used to have to survive.
     */
    private fun queryCalendar(
        executionContext: ExecutionContext,
        plan: QueryPlan.QueryCalendar,
        occurrenceRows: Data?,
    ): MiaAnswer {
        val occurrences = (occurrenceRows as? TList<*>)?.toList().orEmpty()
            .filterIsInstance<ResolvedEventOccurrence>()

        // ⚠⚠ The chain asked for ONE more than this, so a full page here is a FACT that more
        // exist rather than a guess. The old code compared `size >= cap` and therefore claimed
        // "there may be more" at exactly the cap, when there were not.
        val saturated = occurrences.size > plan.limit

        val hits = occurrences.take(plan.limit).map { occurrence ->
            MiaHit.create(
                context = executionContext.context,
                // ⭐ The OCCURRENCE, not the series — §15.6's open item, closed. A
                // `CompositeEntityDataId` carries `(eventId, recurrenceId)` as two parts, so the
                // tap lands on the instance the user was shown rather than on the series' first
                // occurrence years ago. This is the case CO-D5 deleted the `locator` parameter for.
                target = OccurrenceId.create(
                    context = executionContext.context,
                    eventId = occurrence.eventId.get(),
                    recurrenceId = occurrence.recurrenceId.get(),
                ),
                title = occurrence.title.get(),
                subtitle = occurrenceSubtitle(occurrence),
                atMs = 0L,
            )
        }

        return answer(
            executionContext,
            MiaAnswer.VERDICT_ANSWERED,
            // ⚠⚠ Saturation is SAID, not hidden — a long agenda served in the confident voice of
            // a complete one is the failure §15.1 named. ⚠ It is now a genuinely rare case: the
            // cap is on occurrences *in the window*, not on series in the realm.
            if (saturated) {
                "${plan.explanation} (the first ${plan.limit} — there are more)"
            } else {
                plan.explanation
            },
            hits,
        )
    }

    /**
     * "09:00–09:30" — or "All day", which has no times to show.
     *
     * ⚠ Unlocalised, like MQ-3b's confirmation cell: the same ADR-0016 debt, in the same place,
     * and worth fixing in one pass rather than half of one.
     */
    private fun occurrenceSubtitle(occurrence: ResolvedEventOccurrence): String {
        if (occurrence.allDay.get()) return "All day"
        // ⚠ The wire carries ISO local date-times as strings, so the two fields are parsed here
        // rather than arriving typed. An unparseable value degrades to no time shown rather than
        // failing the whole agenda — a row with a missing time is legible; a missing agenda is not.
        val start = parseLocalDateTimeOrNull(occurrence.startLocal.get()) ?: return ""
        val end = parseLocalDateTimeOrNull(occurrence.endLocal.get()) ?: return ""
        return "${twoDigits(start.hour)}:${twoDigits(start.minute)}–" +
            "${twoDigits(end.hour)}:${twoDigits(end.minute)}"
    }

    private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

    private fun parseLocalDateTimeOrNull(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value) }.getOrNull()

    /**
     * Resolves an event down to the row that will exist, and asks (MQ-D4a + MQ-D7).
     *
     * ⚠⚠ **The write grant is checked here, not in the planner.** The planner's grant check
     * covers *reading* a source; writing is a second question with its own switch, and the
     * refusal has to say which one is missing — "I can read your calendar but I'm not allowed
     * to add to it" is actionable where a generic no is not.
     */
    private fun eventConfirmation(
        executionContext: ExecutionContext,
        plan: QueryPlan.CreateEvent,
        zone: TimeZone,
    ): MiaAnswer {
        if (!executionContext.canWrite(MiaSource.CALENDAR)) {
            return answer(
                executionContext,
                MiaAnswer.VERDICT_NO_MATCH,
                "I'm not allowed to add things to your calendar — you can turn that on in " +
                    "Mia's settings.",
            )
        }

        // ⚠ Refuse BEFORE showing a confirmation. `SaveEvent` throws when no default calendar
        // is set, and a cell whose button is guaranteed to fail is worse than an honest no.
        val calendar = CalendarSlots.defaultCalendar(executionContext)
            ?: return answer(
                executionContext,
                MiaAnswer.VERDICT_NO_MATCH,
                "I don't know which calendar to use — set a default one in Calendar first.",
            )

        val end = CalendarSlots.impliedEnd(
            start = plan.startLocal,
            minutes = CalendarSlots.defaultDurationMinutes(executionContext),
            zone = zone,
        )
        val token = newToken()
        actions.put(
            token = token,
            action = PendingMiaAction.Event(
                title = plan.title,
                startLocal = plan.startLocal.toString(),
                endLocal = end.toString(),
                calendarId = calendar.first,
                calendarName = calendar.second,
                timeZoneId = zone.id,
            ),
        )

        return answer(
            executionContext = executionContext,
            verdict = MiaAnswer.VERDICT_CONFIRM,
            explanation = plan.explanation,
            action = MiaAction.create(
                context = executionContext.context,
                token = token,
                title = plan.title,
                // ⚠⚠ The RESOLVED row: when it starts, when it ends, and which calendar —
                // never the partial utterance. Letting the defaults land unseen is the failure
                // MQ-D7's write clause exists to prevent. ⚠ No reminder is named because
                // `SaveEvent` attaches none.
                detail = "${describe(plan.startLocal)} – ${describe(end)} · ${calendar.second}",
            ),
        )
    }

    /**
     * Turns "run my good morning routine" into something to tap — or into an honest refusal
     * (`routines-engine.md` RE-5).
     *
     * ⚠⚠ **Never guesses between candidates**, the rule `confirmation` follows for devices and for
     * the same reason: two routines matching "evening" is exactly where a keyword router picks one
     * and is confidently wrong, and the cost is a house that changes. Several matches name them and
     * ask; one match confirms.
     *
     * ⚠⚠ **Refuses BEFORE showing a cell when the routine is suspended.** `RunRoutineNow` honours
     * `STATE_SUSPENDED` and would refuse, so offering a button guaranteed to fail is worse than an
     * honest no — the rule `eventConfirmation` established for a missing default calendar.
     *
     * ⚠ A *disabled* routine still runs, deliberately: `RunRoutineNow` ignores `enabled` because
     * running one on purpose is how you test it while authoring. The cell says so rather than
     * hiding it, since "off" on the Automations tab and "ran anyway" would otherwise look like a
     * contradiction.
     */
    private fun routineConfirmation(
        executionContext: ExecutionContext,
        plan: QueryPlan.RunRoutine,
        granted: Set<MiaSource>,
    ): MiaAnswer {
        if (MiaSource.ROUTINES !in granted) {
            return answer(
                executionContext,
                MiaAnswer.VERDICT_NO_MATCH,
                "I'm not allowed to see your routines — you can turn that back on in Mia's settings.",
            )
        }

        val needle = plan.text.trim()
        val matches = routines(executionContext)
            .filter { it.name.contains(needle, ignoreCase = true) }

        return when (matches.size) {
            0 -> answer(
                executionContext,
                MiaAnswer.VERDICT_NO_MATCH,
                "I couldn't find a routine called \u201C$needle\u201D.",
            )

            1 -> {
                val (routineId, name, suspended, enabled) = matches.single()
                if (suspended) {
                    return answer(
                        executionContext,
                        MiaAnswer.VERDICT_NO_MATCH,
                        "\u201C$name\u201D is suspended \u2014 the engine stopped it after it " +
                            "misbehaved. You can look at it in Home's Automations tab.",
                    )
                }
                val token = newToken()
                actions.put(token = token, action = PendingMiaAction.Routine(routineId, name))
                answer(
                    executionContext = executionContext,
                    verdict = MiaAnswer.VERDICT_CONFIRM,
                    explanation = plan.explanation,
                    action = MiaAction.create(
                        context = executionContext.context,
                        token = token,
                        // ⚠ The RESOLVED routine's own name, never the fragment typed.
                        title = "Run $name",
                        detail = if (enabled) "Runs now on this device" else "Currently off \u2014 runs now anyway",
                    ),
                )
            }

            else -> answer(
                executionContext,
                MiaAnswer.VERDICT_NO_MATCH,
                "I found more than one routine matching \u201C$needle\u201D: " +
                    matches.joinToString(", ") { it.name } + ". Which one?",
            )
        }
    }

    /** `(routineId, name, suspended, enabled)` for every routine in the realm. */
    private fun routines(executionContext: ExecutionContext): List<RoutineRow> =
        (executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = executionContext.context,
                    type = TList.ID,
                    relationId = RoutineService.Relations.ROUTINES,
                ),
                isEntity = false,
                elementSchemaId = Routine.ID,
            )
        ) ?: emptySequence()).mapNotNull { data ->
            val routine = data as? Routine ?: return@mapNotNull null
            runCatching {
                RoutineRow(
                    routineId = routine.routineId.get(),
                    name = routine.name.get(),
                    suspended = routine.state.get() == Routine.STATE_SUSPENDED,
                    enabled = routine.enabled.get(),
                )
            }.getOrNull()
        }.toList()

    private data class RoutineRow(
        val routineId: String,
        val name: String,
        val suspended: Boolean,
        val enabled: Boolean,
    )

    /** `2026-07-16T13:00` → `2026-07-16 13:00`; deliberately unlocalised for now. */
    private fun describe(value: LocalDateTime): String =
        "${value.date} ${value.hour.toString().padStart(2, '0')}:" +
            value.minute.toString().padStart(2, '0')

    /**
     * Turns a resolved device search into something to tap — or into an honest refusal.
     *
     * ⚠⚠ **Never guesses between candidates.** Two lamps matching "lamp" is exactly where a
     * keyword router would pick one and be confidently wrong, and the cost here is physical.
     * MQ-D4's disambiguation rule with actuation behind it.
     */
    private fun confirmation(
        executionContext: ExecutionContext,
        plan: QueryPlan.ControlDevice,
        hits: List<MiaHit>,
    ): MiaAnswer = when (hits.size) {
        0 -> answer(
            executionContext,
            MiaAnswer.VERDICT_NO_MATCH,
            "I couldn't find a device called “${plan.text}”.",
        )

        1 -> {
            val hit = hits.single()
            val token = newToken()
            actions.put(
                token = token,
                action = PendingMiaAction.Device(
                    // ⚠ The key out of the row's own id. `IssueDeviceCommand` takes a bare
                    // `deviceId`, so the citation's `DataId` is unwrapped exactly here and
                    // nowhere else — the id stays the address everywhere above this line.
                    deviceId = hit.deviceKey(),
                    deviceName = hit.title.get(),
                    on = plan.on,
                ),
            )
            answer(
                executionContext = executionContext,
                verdict = MiaAnswer.VERDICT_CONFIRM,
                explanation = plan.explanation,
                action = MiaAction.create(
                    context = executionContext.context,
                    token = token,
                    // ⚠ The RESOLVED device's own name, not the fragment the user typed —
                    // the point of the cell is to show what will actually happen.
                    title = if (plan.on) "Turn on ${hit.title.get()}" else "Turn off ${hit.title.get()}",
                    detail = hit.subtitle.get(),
                ),
            )
        }

        else -> answer(
            executionContext,
            MiaAnswer.VERDICT_NO_MATCH,
            "I found more than one device matching “${plan.text}” — " +
                hits.joinToString(", ") { it.title.get() } +
                ". Which one did you mean?",
        )
    }

    /**
     * ⚠ An unknown zone resolves to UTC rather than failing the turn: a wrong-by-hours
     * window is a visibly odd answer the user can correct, where a failed turn tells them
     * nothing at all. The resolved range is quoted back in the explanation either way.
     */
    private fun zoneOf(id: String): TimeZone =
        runCatching { TimeZone.of(id) }.getOrElse { TimeZone.UTC }

    /**
     * Every lowering returns a list; only the element type differs. Mapping them all to one
     * [MiaHit] here is what keeps the *answer* shape independent of which plan ran — and
     * what makes a new source cost a `when` branch rather than a wire change.
     *
     * ⚠ Each branch reads the row's **own** identifier into [MiaHit.reference] — a path, a
     * `placeId`, a `momentId` — because that is what a tap-through has to resolve in the
     * owning app. Mia never mints an id of its own.
     */

    /**
     * A row's identity in its OWNING service's vocabulary (`control-cross-app-open.md` CO-D1).
     *
     * ⚠⚠ **The type must be the owning schema's own id**, never one Mia invents: it is what Control
     * routes on, and what the chat cell picks a glyph from. A near-miss here produces a citation
     * that renders perfectly and opens nothing.
     */
    private fun rowId(executionContext: ExecutionContext, type: String, key: String): DataId =
        DataId.StringDataId.create(
            context = executionContext.context,
            type = type,
            keyValue = key,
        )

    /**
     * A stable string for de-duplicating hits across several chains (the place-join's stage two).
     *
     * ⚠ Type **and** key. Two services may legitimately key rows the same way — a `placeId` and a
     * `momentId` are both opaque strings — and folding them together would silently drop a real
     * result whenever the two happened to collide.
     */
    private fun MiaHit.dedupeKey(): String {
        val id = target
        val key = (id as? DataId.StringDataId)?.keyValue?.get() ?: id.toString()
        return id.type.get() + "|" + key
    }

    /** ⚠ Empty when the citation is not a simple keyed row, which no device hit ever is. */
    private fun MiaHit.deviceKey(): String =
        (target as? DataId.StringDataId)?.keyValue?.get().orEmpty()

    /**
     * The citations for a label or visual-search answer, **with every path that no longer names a
     * live photograph dropped** (P0g, `mia-query-layer.md` §18) — or `null` when that could not be
     * checked.
     *
     * ⚠⚠ **A filter on the read, never a delete.** A `MediaLabel` outlives its file today (nothing
     * removes it on delete), and a trashed photo has *moved* to `/Trash/…`, so both leave a row whose
     * path has no `File`. Dropping the hit here and keeping the row is what lets a restore bring the
     * citation back with no re-index — and what keeps Mia read-only (MQ-D10). Deleting the rows is
     * P0f-b / P10's, elsewhere.
     *
     * ⚠⚠ **A stale row must not cost a live one its place.** The chain's `take` counted rows, so a
     * deleted photo ranked above a live one used to fill a slot that is now empty — the same cost the
     * `__indexed` marker always had (§14.3). So when the verified answer comes back short of [limit]
     * *and* the chain was full (more rows may exist), the chain re-runs with a wider `take` —
     * ×[WIDEN_FACTOR], capped at [QueryPlanLowering.MAX_ROWS] — and the first [limit] live hits are
     * kept. A ranked `photosLike` keeps its order: a wider top-k starts with the narrower one.
     *
     * ⚠⚠ **Any failed read is `null`, which the caller answers as UNAVAILABLE** — the existence
     * read, a refused chain or a widening round alike. Citing unverified paths would put back the
     * dead targets this exists to remove, and an empty ANSWERED renders as "nothing matched"
     * (§14.1). Returning the partial set on a failed widening round was rejected for the same reason
     * the main path does not: a short list served in the confident voice of a complete one.
     */
    private suspend fun liveCitations(
        executionContext: ExecutionContext,
        plan: QueryPlan,
        granted: Set<MiaSource>,
        firstRows: Data?,
    ): List<MiaHit>? {
        val lowering = QueryPlanLowering(executionContext.context)
        val (limit, widen) = when (plan) {
            is QueryPlan.FindPhotosOfLabel -> plan.limit to { take: Int -> lowering.lowerLabelSearch(plan.text, take) }
            is QueryPlan.FindPhotosLike -> plan.limit to { take: Int -> lowering.lowerPhotosLike(plan.text, take) }
            else -> error("not a cited-path plan: ${plan::class.simpleName}")
        }
        val executor = LocalTaskExecutor(executionContext)
        val policy = QueryPlanLowering.policyFor(granted)
        // ⚠ Across rounds, so a wider round only asks about paths it has not already settled.
        val live = mutableMapOf<String, Boolean>()

        var requested = limit
        var rows = (firstRows as? TList<*>)?.toList().orEmpty()
        while (true) {
            val unsettled = rows.mapNotNull(::citedPath).distinct().filterNot { it in live }
            val found = livePaths(executionContext, lowering, executor, unsettled) ?: return null
            unsettled.forEach { live[it] = it in found }

            val hits = hitsOf(executionContext, rows.filter { row -> citedPath(row)?.let { live[it] } != false })
            val exhausted = rows.size < requested
            if (hits.size >= limit || exhausted || requested >= QueryPlanLowering.MAX_ROWS) {
                return hits.take(limit)
            }

            requested = minOf(requested * WIDEN_FACTOR, QueryPlanLowering.MAX_ROWS)
            val callstack = widen(requested)
            if (MiaChainValidator.validate(callstack, policy) !is MiaChainValidator.Verdict.Allowed) {
                println("Mia: widened ${plan::class.simpleName} chain refused at take=$requested")
                return null
            }
            val result = executor.executeAsync(executionContext.context.dataFactory().createList<Data>(callstack))
            if (result !is TResult.Success<*>) {
                println("Mia: widened ${plan::class.simpleName} chain failed — ${(result as? TResult.Failure<*>)?.error}")
                return null
            }
            rows = (result.value as? TList<*>)?.toList().orEmpty()
        }
    }

    /** The path a derived row cites, or `null` for a row that is not one (the marker included). */
    private fun citedPath(row: Any?): String? = when (row) {
        is MediaLabel if row.label.get() == INDEX_MARKER_LABEL -> null
        is MediaLabel -> row.filePath.get()
        is SimilarPhoto -> row.filePath.get()
        else -> null
    }

    /**
     * Which of [paths] name a live, untrashed `File` row — `null` when the file plane could not say.
     *
     * ⚠ A trashed path is settled without a read: `/Trash/…` *has* a `File` row, but a photo in the
     * trash is not one to cite. ⚠ One chain per [QueryPlanLowering.EXISTENCE_CHUNK] paths, never one
     * per hit, and each reply is bounded by its chunk rather than by the realm.
     */
    private suspend fun livePaths(
        executionContext: ExecutionContext,
        lowering: QueryPlanLowering,
        executor: LocalTaskExecutor,
        paths: List<String>,
    ): Set<String>? {
        val found = mutableSetOf<String>()
        for (chunk in paths.filterNot(::isTrashedPath).chunked(QueryPlanLowering.EXISTENCE_CHUNK)) {
            val callstack = lowering.lowerLiveFiles(chunk)
            val verdict = MiaChainValidator.validate(callstack, QueryPlanLowering.EXISTENCE_POLICY)
            if (verdict is MiaChainValidator.Verdict.Refused) {
                // ⚠ A bug on our side, like the main path's refusal — never the user's.
                println("Mia: existence read refused by the validator — ${verdict.reason}")
                return null
            }
            val result = executor.executeAsync(executionContext.context.dataFactory().createList<Data>(callstack))
            if (result !is TResult.Success<*>) {
                println("Mia: existence read failed — ${(result as? TResult.Failure<*>)?.error}")
                return null
            }
            (result.value as? TList<*>)?.toList().orEmpty().forEach { row ->
                val path = (row as? Entity<*>)
                    ?.let { runCatching { it.optionalFieldValue<TString>(File.Field.PATH)?.get() }.getOrNull() }
                if (path != null && path in chunk) found += path
            }
        }
        return found
    }

    private fun hitsOf(executionContext: ExecutionContext, value: Data?): List<MiaHit> =
        hitsOf(executionContext, (value as? TList<*>)?.toList().orEmpty())

    private fun hitsOf(executionContext: ExecutionContext, rows: List<Any?>): List<MiaHit> {
        val context = executionContext.context
        return rows.mapNotNull { row ->
            when (row) {
                is CapturedPhoto -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, File.ID, row.path.get()),
                    title = row.path.get().substringAfterLast('/'),
                    subtitle = row.path.get().substringBeforeLast('/', "/"),
                    atMs = row.capturedAt.get(),
                )

                // ⚠⚠ IN-3a's `__indexed` marker is MACHINERY and must never be citable. Found by a
                // test: "photos of indexed" matched it, because the search is a `contains` and the
                // sentinel contains that word. ⚠ Dropped here rather than in the chain because
                // excluding it there needs a `notEqualTo` predicate the DSL does not have — so a
                // file whose only row is the marker still consumes one slot of the chain's `take`,
                // which is a mild ranking cost recorded rather than hidden (§14.3).
                is MediaLabel if row.label.get() == INDEX_MARKER_LABEL -> null

                // ⚠⚠ A label row is cited as the FILE it is about, never as itself: the user asked
                // about photographs, and a "MediaLabel" in the results would be showing our working.
                // ⚠ `atMs = 0` is MiaHit's documented "unknown", not 1970 — the label row carries no
                // capture time and re-reading the file for one field is not worth a second chain.
                is MediaLabel -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, File.ID, row.filePath.get()),
                    title = row.filePath.get().substringAfterLast('/'),
                    subtitle = row.label.get(),
                    atMs = 0,
                )

                // MQ-4. ⚠ The score is deliberately NOT shown. "82% similar" reads as a measured
                // confidence, and it is a raw cosine against one model's space — a number whose
                // scale is meaningless to a user (`models.md` §3.7.2: nothing in a real library
                // even reaches p = 0.5). Ranking is what the score is for; the ORDER carries it.
                is SimilarPhoto -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, File.ID, row.filePath.get()),
                    title = row.filePath.get().substringAfterLast('/'),
                    subtitle = row.filePath.get().substringBeforeLast('/', "/"),
                    // ⚠ MiaHit's documented "unknown", as for a label row: the op returns a path
                    // and a score, and re-reading each file for its capture time is a second chain
                    // per result.
                    atMs = 0,
                )

                is Place -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, Place.ID, row.placeId.get()),
                    title = row.label.get(),
                    subtitle = row.city.get(),
                    atMs = row.capturedAt.get(),
                )

                is Moment -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, Moment.ID, row.momentId.get()),
                    title = row.title.get(),
                    // The moment's own category ("Visit", "Activity", "Movement") is the
                    // most useful second line, and it is already a plain string.
                    subtitle = row.kind.get(),
                    atMs = row.timestamp.get(),
                )

                is Workout -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, Workout.ID, row.workoutId.get()),
                    title = row.title.get().ifEmpty { row.activityType.get() },
                    subtitle = row.activityType.get(),
                    atMs = row.start.get(),
                )

                is HomeDevice -> MiaHit.create(
                    context = context,
                    target = rowId(executionContext, HomeDevice.ID, row.deviceId.get()),
                    title = row.name.get(),
                    subtitle = row.room.get(),
                )

                // ⚠ Must stay LAST: `Folder` is a `File`, and a projected file row arrives
                // as a generic `Entity`, so an earlier branch would swallow the typed rows
                // above.
                is Entity<*> -> {
                    val path = runCatching { row.optionalFieldValue<TString>(File.Field.PATH)?.get() }
                        .getOrNull()
                        ?: return@mapNotNull null
                    MiaHit.create(
                        context = context,
                        target = rowId(executionContext, File.ID, path),
                        title = path.substringAfterLast('/'),
                        subtitle = path.substringBeforeLast('/', "/"),
                        atMs = row.get(File.CreatedAt)?.get() ?: MiaHit.UNKNOWN_AT_MS,
                    )
                }

                else -> null
            }
        }
    }

    private fun answer(
        executionContext: ExecutionContext,
        verdict: String,
        explanation: String,
        hits: List<MiaHit> = emptyList(),
        action: MiaAction? = null,
    ): MiaAnswer = MiaAnswer.create(
        context = executionContext.context,
        verdict = verdict,
        explanation = explanation,
        hits = hits,
        action = action,
    )

    internal companion object {
        /**
         * ⚠ Deliberately generous, and deliberately the SAME for every place.
         * `Place.accuracyM` describes how precisely the *dwell* was located, not how large the
         * venue is — scaling by it would make a poor indoor fix search a wider area than a clean
         * one, which is exactly backwards. 500 m is "this neighbourhood", which is the honest
         * granularity of "photos taken at X".
         */
        const val PLACE_RADIUS_M = 500L

        /**
         * How much wider each re-run of a label / visual-search chain asks, when stale rows left
         * the verified answer short (P0g). ⚠ 20 → 80 → 200 ([QueryPlanLowering.MAX_ROWS]): at most
         * two extra rounds, and none at all in the common case where the first chain came back
         * short of its `take` and so already held every match.
         */
        const val WIDEN_FACTOR = 4

        /**
         * ⚠ Says the *shape* of the problem without naming internals. "I could not look"
         * is the honest sentence; "nothing found" would be a lie in the one direction
         * that matters.
         */
        const val UNAVAILABLE_TEXT = "I could not search your files just now."
    }
}

/**
 * What to tell the user when visual search cannot run (MQ-4, over IN-4b's four reasons).
 *
 * ⚠⚠ Each line names something the user can *do*, which is the entire reason IN-4b refused to
 * return an empty list. ⚠ Never the reason token itself: `notIndexed` is our word, and the E2g
 * fence banner already established that showing someone an internal identifier is worse than
 * saying nothing.
 */
internal fun semanticUnavailableText(reason: String, home: MiaPhotoSearchHome): String = when (home) {
    MiaPhotoSearchHome.THIS_DEVICE -> onThisDevice(reason)
    MiaPhotoSearchHome.PHOTOS_APP -> inPhotos(reason)
}

/**
 * ⚠⚠ iOS (§1.8): Mia's process binds no backend, so [SemanticSearchUnavailable.Reasons.NO_BACKEND]
 * is what it always meets there, and "this device" was wrong — Photos may hold a model and a
 * full index. Each line names Photos, and the place in it that changes the answer.
 */
private fun inPhotos(reason: String): String = when (reason) {
    SemanticSearchUnavailable.Reasons.NO_CONSENT ->
        "Letting a model look at your photos is switched off — that is a separate permission from " +
            "Mia's own. Turn on \"Search photos by description\" in Photos, under Settings → Models."
    SemanticSearchUnavailable.Reasons.NO_BACKEND ->
        "I can't search your photos by what is in them — Photos does that. Search in Photos, after " +
            "downloading its model under Settings → Models."
    SemanticSearchUnavailable.Reasons.NOT_INDEXED ->
        "Photos hasn't looked through your photos yet — open Photos and choose Index now, under Settings → Models."
    else -> onThisDevice(reason)
}

private fun onThisDevice(reason: String): String = when (reason) {
    // ⚠⚠ **Does NOT say "turn it on in Settings", and §14.2 is why.** This reason is IN-D3's
    // *inference* consent — two switches, realm and device, both defaulting to no — and it is
    // NOT `MiaSource.VISUAL_SEARCH`, which is granted by default and sits in Mia's own Settings
    // looking exactly like the switch this sentence used to describe. A user who followed the
    // old wording found "Photo search by appearance" already on and had nowhere left to go.
    //
    // ⚠⚠ So it names the switch that IS off, in Control's own words (`inference-service.md`
    // §32) — §13.3's rule that each line names something the user can do. ⚠ It cannot send a
    // device with no runtime to a switch that changes nothing: `photosLike` reports the missing
    // model before it reports consent.
    SemanticSearchUnavailable.Reasons.NO_CONSENT ->
        "Letting a model look at your photos is switched off for this device — that is a " +
            "separate permission from Mia's own. Turn on \"Search photos by description\" in " +
            "Control, under Settings → Models."
    SemanticSearchUnavailable.Reasons.NO_BACKEND ->
        "I don't have a model on this device that can look at your photos yet."
    SemanticSearchUnavailable.Reasons.NOT_INDEXED ->
        "I haven't looked through your photos on this device yet — ask me again once that has run."
    SemanticSearchUnavailable.Reasons.NO_TEXT_EMBEDDING ->
        "The model on this device can tag photos but can't search them by description."
    // ⚠ A reason this build does not know about — a newer proxy, or a reason added without
    // updating this list. The generic line is the honest answer; inventing a specific one
    // would be a confident guess about a failure we do not understand (MQ-D4).
    else -> AskMiaImplementation.UNAVAILABLE_TEXT
}
