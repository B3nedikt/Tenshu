package org.tenshu.mia.plan

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * The committed question set (`mia-query-layer.md` §23.5): real questions with the answer
 * each one **means**, checked against the planner by [QuestionSetTest].
 *
 * ⚠⚠ **An expectation states what the user meant, never what the grammar happens to do.** A
 * case whose expectation was edited to match the planner's output measures nothing. When the
 * planner disagrees, either the expectation was wrong (fix it, and say why in [Case.note]) or the
 * planner is (mark it [Status.GAP]).
 *
 * ⚠ **Planner-neutral on purpose.** [Expected] names a source, a time, a place, a content phrase,
 * a name and an answer shape, which is the shape of the proposed `Query` (MQ-D11) and not of
 * today's `QueryPlan`. When `Query` lands, only [QuestionSetTest]'s matcher changes, and this
 * file carries over untouched.
 *
 * ⚠ **Provenance is recorded per case** because §21's limitation was a corpus written by the
 * grammar's own author. [Provenance.DRAFT] cases were written by the same hand as the grammar and
 * flatter it; the [Provenance.MAINTAINER] cases are the ones whose pass rate means something.
 *
 * Every time below resolves against [CLOCK]: Monday 2026-11-16, 12:00 UTC.
 *
 * ⚠ **A command's right answer is sometimes a question back.** Where the utterance leaves a param
 * of the write op open ("add dentist tomorrow"), the expectation is [Action.ask], not a guess
 * (`mia-query-layer.md` §25).
 */
internal object QuestionSet {

    /** Monday 2026-11-16, 12:00 UTC — the §23.1 probe's clock, so the two can be compared. */
    val CLOCK: LocalDate = LocalDate(2026, 11, 16)

    // ⚠ Declared before [cases]: an object's properties initialise in order.

    private val today = span(2026, 11, 16, 2026, 11, 17)
    private val yesterday = span(2026, 11, 15, 2026, 11, 16)
    private val tomorrow = span(2026, 11, 17, 2026, 11, 18)
    private val thisWeek = span(2026, 11, 16, 2026, 11, 23)
    private val lastWeek = span(2026, 11, 9, 2026, 11, 16)
    private val thisMonth = span(2026, 11, 1, 2026, 12, 1)
    private val summer2026 = span(2026, 6, 1, 2026, 9, 1)
    private val christmas2025 = span(2025, 12, 24, 2025, 12, 27)

    val cases: List<Case> = english() + englishGaps() + commands() + offTopic() + german() + germanCommands()

    // ---- English: what the grammar is meant to answer ---------------------------

    private fun english() = listOf(
        // photos by time
        Case("photos from last summer", Expected(Src.PHOTOS, time = summer2026), note = "in November, the one just ended"),
        Case("show me pictures from march", Expected(Src.PHOTOS, time = span(2026, 3, 1, 2026, 4, 1))),
        Case("photos from last week", Expected(Src.PHOTOS, time = lastWeek)),
        Case("my photos from yesterday", Expected(Src.PHOTOS, time = yesterday)),
        Case("pictures I took in 2024", Expected(Src.PHOTOS, time = span(2024, 1, 1, 2025, 1, 1))),
        Case("show me my photos", Expected(Src.PHOTOS)),
        // photos by place, label, likeness
        Case("photos from paris", Expected(Src.PHOTOS, place = "paris")),
        Case("pictures I took in lisbon", Expected(Src.PHOTOS, place = "lisbon")),
        Case("photos of a dog", Expected(Src.PHOTOS, content = "dog")),
        Case("pictures of cats", Expected(Src.PHOTOS, content = "cat"), note = "a label is singular"),
        Case(
            "photos that look like a sunset at the beach",
            Expected(Src.PHOTOS, content = "sunset at the beach", contentKind = ContentKind.LIKE),
        ),
        // files by name
        Case("find the invoice", Expected(Src.FILES, name = "invoice")),
        Case("where is my tax return", Expected(Src.FILES, name = "tax return")),
        Case("open the lease agreement", Expected(Src.FILES, name = "lease agreement")),
        Case("search for budget", Expected(Src.FILES, name = "budget")),
        Case("which files are named invoice", Expected(Src.FILES, name = "invoice")),
        // timeline
        Case("what did I do last saturday", Expected(Src.TIMELINE, time = span(2026, 11, 14, 2026, 11, 15))),
        Case("what did I do yesterday", Expected(Src.TIMELINE, time = yesterday)),
        Case("when was I last in paris", Expected(Src.TIMELINE, place = "paris", shape = Shape.LAST)),
        Case("when did I last go to the gym", Expected(Src.TIMELINE, place = "gym", shape = Shape.LAST)),
        // places
        Case("places called blue bottle", Expected(Src.PLACES, place = "blue bottle")),
        // workouts
        Case("show my workouts from last week", Expected(Src.WORKOUTS, time = lastWeek)),
        Case("what workouts did I do yesterday", Expected(Src.WORKOUTS, time = yesterday)),
        // calendar
        Case("what is on my calendar tomorrow", Expected(Src.CALENDAR, time = tomorrow)),
        Case("what's on next week", Expected(Src.CALENDAR, time = span(2026, 11, 23, 2026, 11, 30))),
        Case("do I have anything today", Expected(Src.CALENDAR, time = today)),
        // devices
        Case("is the kitchen light on", Expected(Src.DEVICES, name = "kitchen light")),
        Case("show the living room lamp", Expected(Src.DEVICES, name = "living room lamp")),
        // ⭐ An honest refusal is the right answer when no source holds the data.
        Case("who was I with in paris", Expected(null), note = "no source records company"),
    )

    // ---- English: known gaps (§23.1), the target each one should reach ------------

    private fun englishGaps() = listOf(
        Case("which pictures show a dog", Expected(Src.PHOTOS, content = "dog"), Status.GAP, note = "slot dropped: all photos"),
        Case("photos from paris last summer", Expected(Src.PHOTOS, time = summer2026, place = "paris"), Status.GAP, note = "one slot per plan"),
        Case("the invoice from march", Expected(Src.FILES, time = span(2026, 3, 1, 2026, 4, 1), name = "invoice"), Status.GAP, note = "one slot per plan"),
        Case("show me photos from christmas", Expected(Src.PHOTOS, time = christmas2025), Status.GAP, note = "holiday read as a place"),
        Case(
            "which restaurants did I go to in lisbon",
            Expected(Src.PLACES, place = "lisbon", content = "restaurant"),
            Status.GAP,
            note = "a city read as a place name; no category filter",
        ),
        Case(
            "when was the last time I took a picture of a dog",
            Expected(Src.PHOTOS, content = "dog", shape = Shape.LAST),
            Status.GAP,
            note = "no LAST shape for photos",
        ),
        Case(
            "how often was I at the gym this month",
            Expected(Src.TIMELINE, time = thisMonth, place = "gym", shape = Shape.COUNT),
            Status.GAP,
            note = "no COUNT shape",
        ),
        Case("how long did I stay in paris", Expected(Src.TIMELINE, place = "paris", shape = Shape.DURATION), Status.GAP, note = "no DURATION shape"),
        Case("how far did I run this week", Expected(Src.WORKOUTS, time = thisWeek, shape = Shape.TOTAL), Status.GAP, note = "lists workouts, no TOTAL shape"),
        Case("what's my fastest 5k", Expected(Src.WORKOUTS, shape = Shape.BEST), Status.GAP, note = "no BEST shape"),
    )

    // ---- commands: each runs only after the MQ-D7 confirmation -------------------

    private fun commands() = listOf(
        // ⭐ CL-D6: a bare hour's card offers the other half of the day as one tap.
        Case("add dentist tomorrow at 9", event("dentist", at(17, 9), alternative = at(17, 21))),
        Case("schedule a call with tom on friday at 3", event("call with tom", at(20, 15), alternative = at(20, 3))),
        Case("put gym on thursday at 18:30", event("gym", at(19, 18, 30))),
        Case("add lunch with anna tomorrow at 1pm", event("lunch with anna", at(17, 13))),
        Case("turn off the kitchen light", device("kitchen light", on = false)),
        Case("turn on the living room lamp", device("living room lamp", on = true)),
        Case("run my good morning routine", Expected(Src.ROUTINES, action = Action(Act.RUN_ROUTINE, "good morning"))),
        Case("add an event dentist tomorrow at 9 in the morning", event("dentist", at(17, 9))),
        Case("add dinner with anna tomorrow at 8 in the evening", event("dinner with anna", at(17, 20))),
        Case("add lunch with anna on friday at one", event("lunch with anna", at(20, 13), alternative = at(20, 1))),
        Case("add one on one with tom tomorrow at 3", event("one on one with tom", at(17, 15), alternative = at(17, 3))),
        Case("add lunch at five guys tomorrow at 8pm", event("lunch at five guys", at(17, 20))),
        // ⭐ Asking back (§25): the right answer is a question for one param of SaveEvent.
        Case("add dentist tomorrow", event("dentist", ask = Ask(START, ClarifyReason.MISSING))),
        Case(
            "add dentist tomorrow morning",
            event("dentist", ask = Ask(START, ClarifyReason.MISSING)),
            note = "a part of day narrows the time but does not fix it",
        ),
        Case("remind me to call mum tomorrow", event("call mum", ask = Ask(START, ClarifyReason.MISSING))),
        Case(
            "add a meeting at 10",
            event("meeting", at = at(16, 10), ask = Ask(START, ClarifyReason.INVALID)),
            note = "10:00 today has already passed at the clock's noon",
        ),
        Case("add tomorrow at 1pm", event("", at = at(17, 13), ask = Ask(TITLE, ClarifyReason.MISSING))),
        Case(
            "add dinner with anna tomorrow at 8",
            event("dinner with anna", at = at(17, 8), alternative = at(17, 20)),
            note = "CL-D6: the convention's 08:00 on the card, with 20:00 offered",
        ),
        // ⚠ §25.14/§25.15: a name is the user's, so the command's shape names the source.
        Case("turn on the bulb", device("bulb", on = true)),
        Case("run evening lights", Expected(Src.ROUTINES, action = Action(Act.RUN_ROUTINE, "evening lights"))),
        Case("please start lights out", Expected(Src.ROUTINES, action = Action(Act.RUN_ROUTINE, "lights out"))),
        // gaps
        Case(
            "dim the bedroom light to 30 percent",
            Expected(Src.DEVICES, action = Action(Act.CONTROL_DEVICE, "bedroom light", on = true, level = 30)),
        ),
    )

    private fun germanCommands() = listOf(
        Case("trag zahnarzt morgen um 9 ein", event("zahnarzt", at(17, 9))),
        Case("schalte das küchenlicht aus", device("küchenlicht", on = false)),
        Case("starte die routine guten morgen", Expected(Src.ROUTINES, action = Action(Act.RUN_ROUTINE, "guten morgen"))),
    ).map { it.copy(lang = Lang.DE, status = Status.GAP, note = "English-only grammar: a file search") }

    private fun event(title: String, at: LocalDateTime? = null, ask: Ask? = null, alternative: LocalDateTime? = null) =
        Expected(Src.CALENDAR, action = Action(Act.CREATE_EVENT, title, at = at, ask = ask, alternative = alternative))

    private const val TITLE = org.tenshu.calendar.SaveEvent.Params.TITLE
    private const val START = org.tenshu.calendar.SaveEvent.Params.START_LOCAL

    private fun device(name: String, on: Boolean) =
        Expected(Src.DEVICES, action = Action(Act.CONTROL_DEVICE, name, on = on))

    /** A November 2026 date-time on the clock's calendar. */
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime(2026, 11, day, hour, minute)

    // ---- off-topic: the right answer is NoMatch, never a search ------------------

    private fun offTopic() = listOf(
        "what's the weather tomorrow",
        "tell me a joke",
        "how do I make pancakes",
        "what is the capital of france",
        "translate hello to german",
        "thanks",
        "hi mia",
        "who are you",
        "what time is it",
        "play some music",
        "how are you",
    ).map { Case(it, Expected(null)) } + listOf(
        "wie wird das wetter morgen",
        "erzähl mir einen witz",
        "danke",
        "hallo mia",
        "wie spät ist es",
    ).map { Case(it, Expected(null), lang = Lang.DE) }

    // ---- German: every intent above, said in German -------------------------------
    //
    // ⚠ The grammar is English-only (§23.1), so these are gaps; they define what a German
    // grammar or a span tagger has to reach. Content and names stay in the user's language:
    // matching "hund" to an English label is a separate problem from understanding the question.

    private fun german() = listOf(
        Case("fotos vom letzten sommer", Expected(Src.PHOTOS, time = summer2026)),
        Case("zeig mir bilder aus dem märz", Expected(Src.PHOTOS, time = span(2026, 3, 1, 2026, 4, 1))),
        Case("fotos aus berlin", Expected(Src.PHOTOS, place = "berlin")),
        Case("bilder von einem hund", Expected(Src.PHOTOS, content = "hund")),
        Case("finde die rechnung", Expected(Src.FILES, name = "rechnung")),
        Case("wo ist meine steuererklärung", Expected(Src.FILES, name = "steuererklärung")),
        Case("wann war ich zuletzt in paris", Expected(Src.TIMELINE, place = "paris", shape = Shape.LAST)),
        Case("was habe ich letzten samstag gemacht", Expected(Src.TIMELINE, time = span(2026, 11, 14, 2026, 11, 15))),
        Case("meine trainings letzte woche", Expected(Src.WORKOUTS, time = lastWeek)),
        Case("was steht morgen im kalender", Expected(Src.CALENDAR, time = tomorrow)),
        Case("habe ich heute termine", Expected(Src.CALENDAR, time = today)),
        Case("ist das küchenlicht an", Expected(Src.DEVICES, name = "küchenlicht")),
    ).map { it.copy(lang = Lang.DE, status = Status.GAP, note = it.note.ifEmpty { "English-only grammar" }) }

    private fun span(y1: Int, m1: Int, d1: Int, y2: Int, m2: Int, d2: Int) =
        Span(LocalDate(y1, m1, d1), LocalDate(y2, m2, d2))
}

internal enum class Lang { EN, DE }

internal enum class Provenance {
    /** Written alongside the grammar, by its author. Flatters it (§21). */
    DRAFT,

    /** The maintainer's own questions. The pass rate that means something. */
    MAINTAINER,
}

internal enum class Status {
    /** The planner must answer this as [Case.expected]; failing is a regression. */
    SUPPORTED,

    /** A known gap: the planner must NOT match yet. Matching is a promotion owed. */
    GAP,
}

/** Which data the question reads. `null` in [Expected.source] means the honest answer is NoMatch. */
internal enum class Src { PHOTOS, FILES, PLACES, TIMELINE, WORKOUTS, CALENDAR, DEVICES, ROUTINES }

internal enum class Act { CREATE_EVENT, CONTROL_DEVICE, RUN_ROUTINE }

/**
 * A question back for one param of the command's write op, and why (`mia-query-layer.md` §25,
 * CL-D1). The param is the op schema's own id, so this stays planner-neutral.
 */
internal data class Ask(val param: String, val reason: ClarifyReason)

/**
 * A command, as the confirmation card would show it. [target] is the event title, the device
 * or the routine name. [ask] set means the right answer is a question back for that param;
 * [at] and [target] are then what was heard so far. [alternative] is the one-tap other reading
 * a card offers (CL-D6).
 */
internal data class Action(
    val act: Act,
    val target: String,
    val at: LocalDateTime? = null,
    val on: Boolean? = null,
    val level: Int? = null,
    val ask: Ask? = null,
    val alternative: LocalDateTime? = null,
)

/** What kind of answer the question asks for (the proposed `AnswerShape`). */
internal enum class Shape { LIST, LAST, COUNT, TOTAL, DURATION, BEST }

/** A tagged label ("photos of a dog") or a visual likeness ("photos that look like …"). */
internal enum class ContentKind { LABEL, LIKE }

/** Whole days, half-open: `[from, until)`. */
internal data class Span(val from: LocalDate, val until: LocalDate)

internal data class Expected(
    val source: Src?,
    val time: Span? = null,
    val place: String? = null,
    val content: String? = null,
    val contentKind: ContentKind = ContentKind.LABEL,
    val name: String? = null,
    val shape: Shape = Shape.LIST,
    val action: Action? = null,
)

internal data class Case(
    val utterance: String,
    val expected: Expected,
    val status: Status = Status.SUPPORTED,
    val note: String = "",
    val lang: Lang = Lang.EN,
    val provenance: Provenance = Provenance.DRAFT,
)
