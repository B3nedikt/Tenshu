package org.tenshu.mia.plan

import org.tenshu.mia.MiaSource

/**
 * What Mia understood, before anything runs (`mia-query-layer.md` MQ-D3).
 *
 * A plan is an **intent and its resolved slots** — deliberately *not* a query language.
 * It compiles down to the chain DSL every app already reads through, and the chain is
 * what executes. The earlier framing of this layer had the plan naming operations
 * directly, which amounted to inventing a second, weaker query language beside one that
 * already exists, and would have forfeited the read-path investment (ordered-window
 * fusion, deferred properties, projection pushdown) a *real* chain gets for free.
 *
 * ⭐ Keeping this layer above the chain is what preserves the explanation. A callstack
 * is inspectable but not *legible*; rendering an arbitrary chain back into a sentence is
 * real work that can itself be wrong. So: **the intent is what the user is shown, the
 * chain is what runs.**
 *
 * ⭐ It is also exactly where a language model slots in later — as the *planner*
 * (utterance → plan), touching neither execution, permissions nor rendering. A model
 * that can only emit a [QueryPlan] cannot invent data and cannot exceed its grants,
 * which is far better containment than prompting a model that holds the data.
 *
 * ⚠ Plain Kotlin, not a schema composite: nothing carries a plan across the wire (the
 * planner and the lowering both run proxy-side, and the client receives a
 * [org.tenshu.mia.MiaAnswer]), so giving it a wire shape would add positional composite
 * surface with no consumer.
 */
sealed interface QueryPlan {

    /** One sentence the user can check, naming what will actually be searched. */
    val explanation: String

    /**
     * The source this plan reads, or `null` when it reads nothing ([NoMatch]).
     *
     * ⚠ Carried on the plan rather than derived at lowering time so the **grant check has one
     * home** (MQ-D6): a revoked source has to drop out *during planning*, because the honest
     * answer is "you turned Timeline off", and by lowering time the only thing left to say is
     * a generic refusal.
     */
    val source: MiaSource?

    /**
     * Every image in the realm captured inside [window], best first, capped at [limit].
     *
     * Lowers to the shipped `photosCapturedBetween` op — an ordinary callstack step, and
     * the one read in the file plane that can express a *time window* at all (the chain
     * DSL has only `equalTo` and `contains`, so a range predicate is not expressible
     * there; see that op's KDoc).
     */
    data class FindPhotos(
        val window: TimeWindow,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.FILES
    }

    /**
     * Files whose **name** contains [text], capped at [limit].
     *
     * Lowers to `root.files.traverse().filter { name.contains(text) }.take(limit)` — the
     * same chain Drive's own search runs, so the match happens where the rows are.
     */
    data class FindFilesNamed(
        val text: String,
        val limit: Int,
        override val explanation: String,
        /**
         * Whether this was the **last-resort** arm rather than a positive signal (MQ-5, §16.3).
         *
         * ⚠⚠ `true` means "nothing in the sentence said *files*; there was simply text left over".
         * That arm is the catch-all §14.4 and §14.5 both traced their wrong answers to — "my runs
         * last week" and "what is on my calendar tomorrow" each ended here — so it is the one plan
         * a model is allowed to second-guess. A quoted phrase or an explicit "file"/"document"
         * leaves this `false`, and a plan the grammar reached on purpose is never overridden.
         */
        val viaCatchAll: Boolean = false,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.FILES
    }

    /**
     * Places the user has been, whose label contains [text] (`where was that café`).
     *
     * Lowers to `places.filter { label contains text }` — a name predicate, the same shape
     * as [FindFilesNamed], because a place's label is the only thing a person can describe
     * it by.
     */
    data class FindPlaces(
        val text: String,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.PLACES
    }

    /**
     * What happened inside [window] (`what did I do last Saturday`).
     *
     * ⭐ Lowers to `moments.filter { timestamp.inRange(…) }` — the first intent that is a
     * *pure* time window, and it exists only because `inRange` does
     * (`core-operation-dispatch.md` §5). Before that predicate this had to be refused.
     */
    data class RecallMoments(
        val window: TimeWindow,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.TIMELINE
    }

    /**
     * What the user did **at a place** — "what did I do in Paris", "when was I last at the gym"
     * (`takeout-import-and-recall.md` TB-D5).
     *
     * ⭐ The same two-stage join as [FindPhotosAtPlace], and for the same reason: the name is
     * resolved against `Place` rows, then moments are read by the resolved id. Neither service
     * learns about the other — the join is Mia's (MQ-D2).
     *
     * ⚠ [latestOnly] is the difference between "what did I do there" (a list) and "when was I
     * last there" (one row, the most recent). It is a *question about time*, so answering it with
     * an unordered handful would be answering something else.
     *
     * ⚠ The source is [MiaSource.TIMELINE], not `PLACES`: the citations are moments, and the
     * place row is machinery. A grant check that named "places" would let a user who revoked
     * their timeline read it anyway through this door.
     */
    data class RecallAtPlace(
        val text: String,
        val limit: Int,
        val latestOnly: Boolean,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.TIMELINE
    }

    /**
     * Photos a model said contain something — "photos of a cat" (IN-4, IN-D12).
     *
     * ⭐ **Label search costs Mia nothing structural.** It is an ordinary MQ-2 source adapter: the
     * plan lowers to `filter { label contains … }` over `MediaLabel` rows. The *semantic* half —
     * nearest-neighbour over embeddings — is the one IN-D12 says needs an **op**, not a predicate,
     * and it is not this.
     *
     * ⚠ The citations are the **files**, not the label rows: the user asked about photographs. The
     * label row is machinery, and showing it would be showing our working.
     */
    data class FindPhotosOfLabel(
        val text: String,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.LABELS
    }

    /**
     * Photos that **look like** [text], best first (`photos like a sunset over water`) — MQ-4, and
     * the intent IN-D12 said could never be a chain predicate.
     *
     * Lowers to the IN-4b `photosLike` op. ⚠⚠ Unlike every other read here, the op **can refuse**:
     * semantic search needs consent, a model and an indexed library, and any of the three may be
     * missing on a device where everything else Mia does works fine. That refusal has to survive
     * all the way to the user — see `AskMiaImplementation.semanticUnavailable`.
     *
     * ⚠ Distinct from [FindPhotosOfLabel], not a fallback for it. A label search is exact and
     * cheap and answers "of a cat"; this ranks the whole library against a sentence. Running the
     * second when the first finds nothing would be defensible UX and was **deliberately not
     * built**: the plan is what the user is shown (MQ-D3), so a silent second search would make
     * the explanation describe something other than what ran — and it would cross a grant boundary
     * without saying so.
     */
    data class FindPhotosLike(
        val text: String,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.VISUAL_SEARCH
    }

    /** Workouts started inside [window] (`how far did I run this week`). */
    data class FindWorkouts(
        val window: TimeWindow,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.FITNESS
    }

    /**
     * Smart-home devices whose name contains [text] (`is the kitchen light on`).
     *
     * ⚠ A **read**, and only a read: it reports state, it does not command. Turning the
     * light off is `DeviceAction`, which is MQ-3's business because it needs MQ-D7's
     * confirmation cell — and the validator's op allowlist is what keeps the two apart.
     */
    data class FindDevices(
        val text: String,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.DEVICES
    }

    /**
     * Change a device: *"turn off the kitchen light"* (MQ-D7).
     *
     * ⚠⚠ **This plan does not lower to the write.** MQ-D10's op allowlist is reads-only, and
     * that is enforced rather than inherent — the same callstack shape carries every write in
     * the platform. So what lowers is the ordinary **device search** (validated as a read,
     * exactly like [FindDevices]); resolving it to one device produces a `MiaAction` the user
     * must tap, and only `ConfirmMiaAction` mutates anything.
     *
     * ⚠ [on] is an **absolute** intent, never a toggle. `home-device-service` made the same
     * call for its UI controls and the reason applies twice over here: a toggle is relative to
     * a reading that may already be stale, and an utterance is a statement of what the user
     * *wants* — "turn it off" means off, not "flip whatever it is".
     */
    data class ControlDevice(
        val text: String,
        val on: Boolean,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.DEVICES
    }

    /**
     * Fire one routine now (`routines-engine.md` RE-5) — "run my good morning routine".
     *
     * ⚠⚠ **A write, so it never lowers.** Like [CreateEvent] it is settled before the chain path
     * (MQ-D4a): the routine rows are read directly to resolve [text] to exactly one routine, and
     * what the user gets is MQ-D7's confirmation cell naming the *resolved* routine. Only
     * `ConfirmMiaAction` fires it, through `RunRoutineNow`.
     *
     * ⚠⚠ **Never guesses between candidates**, for the same reason [ControlDevice] does not: two
     * routines matching "evening" is exactly where a keyword router picks one and is confidently
     * wrong, and the cost here is a house that changes. One match confirms; several ask which.
     *
     * ⚠ [text] is the user's fragment, not a routine id. The resolution happens proxy-side where
     * the rows are, and the id never appears in a plan — the same rule `ControlDevice` follows by
     * unwrapping a `DataId` at exactly one line.
     */
    data class RunRoutine(
        val text: String,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.ROUTINES
    }

    /**
     * Record an event (`mia-query-layer.md` MQ-D4a) — the first intent that **authors user
     * data**.
     *
     * ⚠⚠ Everything MQ-D1's citation property rested on stops here. A misheard slot in a read
     * produces a wrong answer the user ignores; in a write it produces a wrong *row*, which
     * persists, syncs to every device and fires a reminder long after the utterance is
     * forgotten. MQ-D7's confirmation is therefore not a courtesy — it is the whole control.
     *
     * ⭐ Mitigating, and why this is a good *first* write: an event is **reversible**. A wrong
     * one is annoying, not destructive — unlike deleting a file or unlocking a door.
     *
     * ⚠ Carries only what was heard. The calendar, the end time and every other field come
     * from defaults the *service* owns; Mia resolves them for display and passes them back
     * explicitly, so the row that exists is exactly the one shown.
     */
    data class CreateEvent(
        val title: String,
        val startLocal: kotlinx.datetime.LocalDateTime,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.CALENDAR
    }

    /**
     * Photos taken *at a place*: "show me pictures taken in Munich".
     *
     * ⭐⭐ **Two reads, and the join is Mia's.** The place query resolves against `Place` rows —
     * the places the user has actually been, which Timeline already maintains — and their
     * coordinates then drive `photosNear` on the file plane. Neither service learns about the
     * other: this is precisely the cross-service read MQ-D2 says only the proxy can do, and it
     * needs **no model, no geocoding and no egress at all**.
     *
     * ⚠ The honest limit: it finds photos near places you have *been*. A photo taken in a city
     * you never opened Timeline in has a coordinate but no `Place` to match, so it is not found —
     * and Mia says which place it searched around rather than implying it swept the city.
     */
    data class FindPhotosAtPlace(
        val text: String,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        // ⚠ PLACES, not FILES: the grant that gates this is the one whose data does the
        // narrowing. Revoking Places must stop it even though the photos come from the file
        // plane, because it is the place rows that turn "Munich" into coordinates.
        override val source: MiaSource get() = MiaSource.PLACES
    }

    /**
     * "What is on my calendar tomorrow" — MQ-D4a's **read** half, the last named intent of the
     * original design (§15).
     *
     * ⚠⚠ **A window of days, not a predicate.** `Event.startLocal` is a string, so `inRange` over
     * it answers "nothing on tomorrow" silently (§8.4) — and no predicate could do the job anyway,
     * because a weekly event created two years ago has **no stored row for tomorrow at all**. The
     * occurrence is computed, not stored. So this plan lowers to an unfiltered, bounded read of the
     * series and their overrides, and the window is applied by `OccurrenceResolver` in the proxy —
     * the same division as Track A's place join (§12.1): each service returns its own rows, the
     * proxy does the work only it can do.
     */
    data class QueryCalendar(
        val window: AgendaWindow,
        val limit: Int,
        override val explanation: String,
    ) : QueryPlan {
        override val source: MiaSource get() = MiaSource.CALENDAR
    }

    /**
     * The utterance asked for an action on a runtime that offers none
     * ([MiaActionOffer.WITHHELD]). [explanation] is the refusal the user is shown.
     *
     * ⚠⚠ **Its own plan rather than a [NoMatch], because a `NoMatch` may be overruled.** A model
     * is allowed a second opinion on "the vocabulary did not place this sentence" (§16.3), and
     * "turn off the kitchen light" answered with the light's *state* is the confidently-different
     * answer this layer exists to prevent. The grammar placed this sentence exactly; what is
     * missing is the ability to act on it.
     *
     * ⚠ Planned at the intent, before any slot is resolved: nothing is read, so the refusal
     * cannot depend on whether a device or routine of that name exists.
     */
    data class ActionWithheld(override val explanation: String) : QueryPlan {
        override val source: MiaSource? get() = null
    }

    /**
     * ⚠⚠ **No intent matched, and this is a first-class outcome.** The characteristic
     * failure of a keyword router is answering a *different* question confidently, so
     * the planner is required to be able to say it understood nothing. [explanation]
     * carries what Mia *can* be asked instead.
     */
    data class NoMatch(override val explanation: String) : QueryPlan {
        override val source: MiaSource? get() = null
    }
}

/**
 * A resolved half-open instant range `[startMs, endMsExclusive)` plus the human label
 * the answer quotes back.
 *
 * ⚠ **Half-open, like every other range in this codebase** (`WC-2`'s filing periods made
 * the same call): adjacent windows share a boundary instant, so a closed range
 * double-counts the row that lands exactly on midnight.
 *
 * ⚠ [label] is not decoration. It is how a hemisphere- or calendar-dependent phrase
 * stays *correctable*: "summer" resolved to a named date range is something a user in
 * Sydney can see is wrong, where a silently-northern window is not.
 */
data class TimeWindow(
    val startMs: Long,
    val endMsExclusive: Long,
    val label: String,
) {
    companion object {
        /** Everything Mia can see, for an utterance that named no time at all. */
        fun everything(nowMs: Long): TimeWindow =
            TimeWindow(startMs = 0L, endMsExclusive = nowMs + 1, label = "all time")
    }
}
