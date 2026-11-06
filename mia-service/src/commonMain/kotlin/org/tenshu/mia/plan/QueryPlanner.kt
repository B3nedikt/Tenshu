package org.tenshu.mia.plan

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.tenshu.mia.MiaSource

/**
 * Utterance → [QueryPlan]. The v1 planner (`mia-query-layer.md` MQ-D4).
 *
 * ⚖️ **Target and mapper are orthogonal.** *What* the planner emits is settled by MQ-D3:
 * a chain, always. *What maps an utterance onto it* is a separate choice, and v1's
 * answer is a deterministic grammar **because v1 has no model** — not because a closed
 * intent set is desirable. Fixing the output format is what lets the mapper be replaced
 * later without disturbing execution, permissions or rendering.
 *
 * ⚠⚠ **Never guess.** The characteristic failure of a keyword router is answering a
 * *different* question confidently, so an utterance that matches no intent returns
 * [QueryPlan.NoMatch] with a list of what Mia can actually be asked. The mock's keyword
 * routing was a demo; a real planner needs an explicit no-match verdict from the start.
 *
 * ⚠ **Two intents, and the boundary is not arbitrary.** MQ-1's scope is "Drive files
 * only — name, type, date", but only two of those three are expressible against the
 * shipped file plane: a **name** fragment pushes down as `contains`, and an image
 * **time window** is exactly what `photosCapturedBetween` answers. A *date* filter over
 * arbitrary files, or a category filter beyond images, needs a comparison predicate the
 * chain DSL does not have (`equalTo` and `contains` are the only two) — that is a
 * `:core` change, and widening the grammar ahead of it would only produce plans that
 * cannot be lowered. Those arrive with MQ-2's sources.
 */
object QueryPlanner {

    /**
     * How many rows one answer may cite. Also MQ-D10's cost bound: every lowered chain
     * carries a terminal `take`, so a plan can never ask the proxy to materialise the
     * whole realm.
     */
    const val DEFAULT_LIMIT = 20

    /** Words that mean "images", the one category the file plane can answer on. */
    private val PHOTO_WORDS = setOf(
        "photo", "photos", "photograph", "photographs",
        "picture", "pictures", "pic", "pics", "image", "images",
    )

    /** Words that mean "any file" — enough to intend a search, not enough to narrow one. */
    private val FILE_WORDS = setOf("file", "files", "document", "documents", "doc", "docs")

    /** Words that turn a device question into a device *command*. */
    private val TURN_ON_WORDS = setOf("on")
    private val TURN_OFF_WORDS = setOf("off")
    private val ACTION_VERBS = setOf("turn", "switch", "put")

    /** Smart-home nouns. A device question is always *about* one of these. */
    /**
     * RE-5. ⚠ Both nouns: Home's own UI calls them *automations* and the service calls them
     * *routines*, so a user who has only ever seen the Automations tab must not be told there is
     * no such thing.
     */
    private val ROUTINE_WORDS = setOf("routine", "routines", "automation", "automations")

    /**
     * ⚠⚠ **A routine noun alone is not an instruction.** "show my routines" is a request to *see*
     * them, and firing one in response would be the worst possible misreading — the same rule
     * `deviceIntent` applies to a bare "on". The verb is what makes it a command.
     */
    private val RUN_VERBS = setOf("run", "start", "trigger", "execute", "fire", "activate")

    private val DEVICE_WORDS = setOf(
        "light", "lights", "lamp", "lamps", "plug", "plugs", "socket", "sockets",
        "switch", "switches", "thermostat", "sensor", "sensors", "device", "devices",
    )

    /**
     * Movement verbs and nouns — the vocabulary of a workout question.
     *
     * ⚠⚠ **Every form of every word, and the plurals are not optional.** This list shipped with
     * `run` but not `runs`, and §14.4 measured what that cost on a device: "my **runs** last week"
     * was answered *"I don't know a place called “runs”"*, because a word that misses here does not
     * fall through to "I didn't understand" — it falls through to the arms below, which accept any
     * leftover text. A vocabulary gap becomes a **confident answer about something else**, which is
     * the failure MQ-D4 exists to prevent.
     */
    private val FITNESS_WORDS = setOf(
        "run", "runs", "ran", "running", "walk", "walks", "walked", "walking",
        "ride", "rides", "rode", "riding", "cycling", "cycle", "cycles", "cycled",
        "swim", "swims", "swam", "swimming", "workout", "workouts", "exercise", "exercises",
        "training", "trained", "hike", "hikes", "hiked", "hiking",
    )

    /**
     * Words that mean "put this in my calendar".
     *
     * ⚠⚠ **Verbs only, and `meeting` / `appointment` were deliberately removed (§15.2).** They are
     * nouns, and a noun appears in the *question* as readily as in the instruction: "do I have a
     * meeting tomorrow" carried one and was routed to `CreateEvent`, which then asked what time to
     * put it at. That is §10.3's rule one source over — "is the kitchen light on" stays a question
     * because **the verb is what makes an utterance an instruction** — and it matters more here,
     * because a device command is confirmed and reversible while a misheard event persists, syncs
     * and fires a reminder weeks later (MQ-D1's amendment).
     *
     * ⚠ The cost, accepted: a verbless "meeting with anna tomorrow at 3" no longer creates
     * anything. Asking for a verb is a small price for not writing rows from phrases that were
     * never instructions.
     */
    private val CREATE_EVENT_WORDS = setOf(
        "add", "schedule", "book", "remind", "put", "create",
    )

    /**
     * Words that ask to **read** the calendar (MQ-D4a's read half, §15).
     *
     * ⚠ Introduced in §14.5 as a refusal — *"I can't read your calendar yet"* — because without it
     * "what is on my calendar tomorrow" fell through to the file-name arm and came back **"Searched
     * your files for names containing “calendar tomorrow”"**. It now routes to a real answer.
     *
     * ⚠ Checked *after* [CREATE_EVENT_WORDS], so "add lunch to my calendar" still writes.
     * ⚠ The nouns are here rather than in [CREATE_EVENT_WORDS]: "do I have a meeting tomorrow" is a
     * question about the calendar, and only a verb makes it an instruction.
     */
    private val CALENDAR_READ_WORDS = setOf(
        "calendar", "agenda", "events", "event", "meeting", "meetings",
        "appointment", "appointments",
    )

    /**
     * The word that turns a photo question into a question about **what is in** the photo (IN-4).
     *
     * ⚠⚠ Exactly one word, and deliberately: "photos of a cat" is about content, "photos in Munich"
     * is about place, and "photos from Tuesday" is about time. English gives us three prepositions
     * that separate three intents cleanly, and guessing between them without one is the
     * confidently-wrong failure MQ-D4 exists to avoid.
     */
    private const val CONTENT_ANCHOR = "of"

    /**
     * Words that ask for a **visual** match rather than a tag (MQ-4).
     *
     * ⚠⚠ Separate anchors rather than a fallback behind [CONTENT_ANCHOR]. "Of" asks about a label
     * the model already wrote down; these ask to rank the whole library against a description, and
     * they are the phrasings that carry a thing no vocabulary contains — *"photos like my red
     * bicycle by the canal"*. Since the plan is what the user is shown, the two intents have to be
     * separable in the sentence rather than decided invisibly after one of them returns nothing.
     *
     * ⚠ Checked BEFORE the "of" arm. These words are rarer and more deliberate than "of", so when
     * one appears the user has said something specific and it should win.
     */
    private val LIKE_ANCHORS = setOf("like", "showing", "resembling")

    /**
     * Words that anchor the rest of an utterance to a location.
     *
     * ⚠ **"from" is here even though it also introduces a time** ("photos from yesterday"), and it
     * has to be: "my pictures from Munich" is how people actually ask. It is safe because
     * [placeAnchor] drops time words from the residue, so a temporal "from" leaves nothing behind
     * and the plan falls through to the ordinary windowed photo search.
     */
    private val PLACE_ANCHORS = setOf("in", "at", "near", "around", "from")

    /**
     * Words that make an utterance a question **about the user's own life** rather than about
     * their files (`takeout-import-and-recall.md` TB-D5).
     *
     * ⚠⚠ This set exists to stop the last-resort file-name arm from swallowing recall questions.
     * Measured before it existed: "when was I last in paris" searched files for `"when paris"`
     * and "when did I last visit the gym" for `"when gym"` — a filename grep with an explanation
     * that reads as though Mia understood, which is worse than a refusal (MQ-D4).
     */
    private val RECALL_WORDS = setOf(
        "when", "trip", "trips", "visited", "been", "did",
    )

    /**
     * Recall words that specifically ask for **the latest** one.
     *
     * ⚠ "last" is also a time word ("last week"). The time grammar runs first and consumes it when
     * a unit follows — see [isLatestSignal], which is where that actually gets checked.
     */
    private val LATEST_WORDS = setOf("last", "latest", "recently", "recent")

    /**
     * Whether the word at [index] is asking for **the most recent one**, rather than being part of
     * a time expression the grammar has already used.
     *
     * ⚠⚠ **This check is the fix for §14.4, and its absence is what made "runs last week" a
     * question about a place called "runs".** [LATEST_WORDS]' own comment always claimed the time
     * grammar consumed "last week" — but nothing tested it, so the "last" in "last week" counted as
     * a recall signal too, and the recall arm accepts a bare leftover as a place name. One word ran
     * two intents.
     *
     * The rule: "last" followed by a time word ("last **week**", "last **summer**",
     * "last **Tuesday**") belongs to the window. Followed by anything else ("last **at** the gym",
     * "last **in** Paris") it means *most recent*.
     */
    private fun isLatestSignal(words: List<String>, index: Int): Boolean {
        if (words[index] !in LATEST_WORDS) return false
        val next = words.getOrNull(index + 1) ?: return true
        return !TimeGrammar.isTimeWord(next)
    }

    /** Words that name a *place* rather than a file. */
    private val PLACE_WORDS = setOf(
        "place", "places", "cafe", "café", "cafes", "restaurant", "restaurants", "bar",
        "bars", "shop", "shops", "venue", "venues",
    )

    /** Openers and filler the grammar strips before looking for a name fragment. */
    private val STOP_WORDS = setOf(
        "find", "show", "me", "my", "the", "a", "an", "get", "search", "for", "of",
        "look", "up", "where", "is", "are", "what", "which", "do", "did", "i", "have",
        "in", "on", "at", "from", "please", "all", "any", "some", "and", "to",
        "called", "named", "titled", "that", "with", "was", "were", "saved", "stored",
        "far", "long", "much", "many", "how", "been", "go", "went", "visit", "visited",
        "turn", "switch", "put", "off", "please",
        // ⚠ The recall vocabulary is stripped from a *name* the same way "find" and "show" are:
        // "when did I last visit the gym" must leave "gym", not "when gym". The words still
        // route the intent — `wantsRecall` reads the raw utterance — they just do not survive
        // into the text being searched for.
        "when", "trip", "trips",
    ) + PHOTO_WORDS + FILE_WORDS

    /**
     * ⚠ A one- or two-character leftover is noise, not a search term. Searching for "a"
     * matches every file in the realm, which reads to the user as a broken answer rather
     * than as a bad query.
     */
    private const val MIN_TEXT_LENGTH = 3

    /**
     * @param granted the sources the user currently allows (MQ-D6). A plan whose source is
     *   absent is replaced by a [QueryPlan.NoMatch] that **names it** — MQ-D6's "a revoked
     *   source drops out of planning entirely … and Mia says why, rather than silently
     *   returning less". Defaults to everything so callers that predate grants — and the
     *   grammar tests, which are about language and not permissions — are unaffected.
     * @param actionOffer whether this runtime may offer an action at all. ⚠ Required: see
     *   [MiaActionOffer]. On [MiaActionOffer.WITHHELD] the three action intents plan to
     *   [QueryPlan.ActionWithheld] and every read is untouched.
     */
    fun plan(
        utterance: String,
        nowMs: Long,
        zone: TimeZone,
        actionOffer: MiaActionOffer,
        granted: Set<MiaSource> = MiaSource.entries.toSet(),
    ): QueryPlan {
        val plan = planIgnoringGrants(utterance, nowMs, zone, actionOffer)
        val source = plan.source ?: return plan
        if (source in granted) return plan

        // ⚠ Names the source and where to change it. "I can't answer that" would be
        // indistinguishable from not understanding — the one thing MQ-D4's no-match verdict
        // exists to keep separate.
        return QueryPlan.NoMatch(
            "I understood that, but I'm not allowed to read your ${sourceLabel(source)} — " +
                "you can turn that back on in Mia's settings."
        )
    }

    /**
     * Build the plan for an intent **something else chose** — MQ-5's seam (§16).
     *
     * ⭐⭐ **The model picks the question; this file still fills the slots.** A time expression is
     * code and localisation-bearing (MQ-D4), a place needs its anchor word (§12.4), a calendar
     * window leans forward (§15.3) — none of that gets better by being generated, and all of it
     * gets silently worse. So the division is: *which* question is a judgement, *what* it contains
     * is a parse.
     *
     * ⚠⚠ **Returns `null` when the utterance cannot fill the chosen intent**, and the caller then
     * falls back to the deterministic plan. That case is not exotic — it is precisely what a wrong
     * choice looks like ("photos at a place", in a sentence naming no place) — and answering it
     * with a `NoMatch` would let a bad guess *replace* an answer the grammar could have given.
     *
     * ⚠ The grant check runs here too, in [plan]'s own shape: a model-chosen source the user
     * revoked is refused by name, exactly as a grammar-chosen one is. The generator changed; the
     * boundary did not (MQ-D10).
     */
    fun planForIntent(
        utterance: String,
        nowMs: Long,
        zone: TimeZone,
        intent: MiaIntent,
        granted: Set<MiaSource> = MiaSource.entries.toSet(),
    ): QueryPlan? {
        val s = slotsOf(utterance, nowMs, zone) ?: return null

        val plan = when (intent) {
            MiaIntent.FIND_PHOTOS -> findPhotos(s.window, nowMs)
            MiaIntent.FIND_FILES_NAMED -> (s.quoted ?: s.text)?.let { findFilesNamed(it) }
            MiaIntent.FIND_PLACES -> s.text?.let { findPlaces(it) }
            MiaIntent.FIND_PHOTOS_AT_PLACE -> s.atPlace?.let { findPhotosAtPlace(it) }
            MiaIntent.FIND_PHOTOS_OF_LABEL -> s.ofContent?.let { findPhotosOfLabel(it) }
            MiaIntent.FIND_PHOTOS_LIKE -> s.likeContent?.let { findPhotosLike(it) }
            MiaIntent.RECALL_MOMENTS -> s.window?.let { recallMoments(it) }
            MiaIntent.RECALL_AT_PLACE ->
                s.recallPlace?.let { recallAtPlace(it, latest = s.wantsLatest) }
            MiaIntent.FIND_WORKOUTS -> findWorkouts(s.window, nowMs)
            MiaIntent.FIND_DEVICES -> s.text?.let { findDevices(it) }
            MiaIntent.QUERY_CALENDAR -> queryCalendar(s.words, nowMs, zone)
        } ?: return null

        val source = plan.source ?: return plan
        if (source in granted) return plan

        return QueryPlan.NoMatch(
            "I understood that, but I'm not allowed to read your ${sourceLabel(source)} — " +
                "you can turn that back on in Mia's settings."
        )
    }

    /**
     * ⚠ The label is deliberately the user's word for the source, not the service id: the ids
     * are internal `tenshu/…/service` paths, and the E2g fence banner already established
     * that printing one at a user is worse than saying nothing.
     */
    private fun sourceLabel(source: MiaSource): String = when (source) {
        MiaSource.FILES -> "files"
        MiaSource.TIMELINE -> "timeline"
        MiaSource.FITNESS -> "workouts"
        MiaSource.PLACES -> "places"
        MiaSource.DEVICES -> "smart-home devices"
        MiaSource.LABELS -> "photo contents"
        MiaSource.VISUAL_SEARCH -> "photos by what they look like"
        MiaSource.CALENDAR -> "calendar"
        MiaSource.ROUTINES -> "routines"
    }

    /**
     * Everything the grammar can read off one utterance, computed once.
     *
     * ⚠⚠ **Extracted in MQ-5 so that slot extraction and intent *selection* stop being the same
     * expression.** They were one `when`, which is fine while the only router is this file's
     * vocabulary — and impossible the moment something else chooses the intent, because a model
     * picking `findWorkouts` still needs the grammar's window, and a model picking
     * `findPhotosAtPlace` still needs its anchored place. ⭐ The routing below is **byte-for-byte
     * unchanged**; only where its inputs come from moved.
     */
    private class PlanSlots(
        val words: List<String>,
        val quoted: String?,
        val window: TimeWindow?,
        val text: String?,
        val atPlace: String?,
        val ofContent: String?,
        val likeContent: String?,
        val wantsPhotos: Boolean,
        val wantsFiles: Boolean,
        val wantsDevices: Boolean,
        val wantsFitness: Boolean,
        val wantsPlaces: Boolean,
        val wantsEvent: Boolean,
        val wantsRoutine: Boolean,
        val wantsLatest: Boolean,
        val wantsRecall: Boolean,
        val recallPlace: String?,
    )

    private fun slotsOf(utterance: String, nowMs: Long, zone: TimeZone): PlanSlots? {
        val words = tokenize(utterance)
        if (words.isEmpty()) return null

        val quoted = quotedPhrase(utterance)
        val window = TimeGrammar.resolve(words, nowMs, zone)
        val wantsPhotos = words.any { it in PHOTO_WORDS }
        val wantsFiles = words.any { it in FILE_WORDS }
        val wantsDevices = words.any { it in DEVICE_WORDS }
        val wantsFitness = words.any { it in FITNESS_WORDS }
        val wantsPlaces = words.any { it in PLACE_WORDS }

        // An explicitly quoted phrase is the least ambiguous thing a user can type, so it
        // outranks every other signal — including a photo word, which in `photos of
        // "Berlin"` is describing the name, not asking for the photo intent.
        val text = quoted ?: leftoverText(words, extraStopWords = DEVICE_WORDS + FITNESS_WORDS + PLACE_WORDS)
        // "taken in X" / "from X" / "in X" — the place a photo question is anchored to.
        val atPlace = placeAnchor(words)
        // "photos OF a cat" — what is in the picture, as opposed to where or when it was taken.
        val ofContent = contentAnchor(words)
        // "photos LIKE a sunset over water" — rank the library against a description (MQ-4).
        val likeContent = likeAnchor(words)

        val wantsEvent = words.any { it in CREATE_EVENT_WORDS }
        val wantsRoutine = words.any { it in ROUTINE_WORDS }
        val wantsLatest = words.indices.any { isLatestSignal(words, it) }
        val wantsRecall = words.any { it in RECALL_WORDS } || wantsLatest
        // ⚠ An anchor is preferred but not required here, unlike the photo intent: "when did I
        // last visit the gym" anchors on the VERB, not on a preposition, and demanding "at" would
        // refuse the most natural phrasing of the question this intent exists for.
        val recallPlace = atPlace ?: text

        return PlanSlots(
            words = words,
            quoted = quoted,
            window = window,
            text = text,
            atPlace = atPlace,
            ofContent = ofContent,
            likeContent = likeContent,
            wantsPhotos = wantsPhotos,
            wantsFiles = wantsFiles,
            wantsDevices = wantsDevices,
            wantsFitness = wantsFitness,
            wantsPlaces = wantsPlaces,
            wantsEvent = wantsEvent,
            wantsRoutine = wantsRoutine,
            wantsLatest = wantsLatest,
            wantsRecall = wantsRecall,
            recallPlace = recallPlace,
        )
    }

    private fun planIgnoringGrants(
        utterance: String,
        nowMs: Long,
        zone: TimeZone,
        actionOffer: MiaActionOffer,
    ): QueryPlan {
        val s = slotsOf(utterance, nowMs, zone) ?: return noMatch()
        val withheld = actionOffer == MiaActionOffer.WITHHELD
        val words = s.words
        val quoted = s.quoted
        val window = s.window
        val text = s.text
        val atPlace = s.atPlace
        val ofContent = s.ofContent
        val likeContent = s.likeContent
        val wantsPhotos = s.wantsPhotos
        val wantsFiles = s.wantsFiles
        val wantsDevices = s.wantsDevices
        val wantsFitness = s.wantsFitness
        val wantsPlaces = s.wantsPlaces
        val wantsEvent = s.wantsEvent
        val wantsRoutine = s.wantsRoutine
        val wantsLatest = s.wantsLatest
        val wantsRecall = s.wantsRecall
        val recallPlace = s.recallPlace

        return when {
            // ⚠ Ahead of every read: "add lunch with Anna next Tuesday at one" carries a place
            // word ("lunch"? no) and a time, and reading it as a search would silently answer a
            // different question instead of recording anything.
            // ⚠⚠ Withheld at the INTENT, ahead of the slot questions: "I can add that once you
            // tell me when" is itself an offer, and answering it would walk the user through
            // completing a request this runtime then refuses.
            wantsEvent && withheld -> QueryPlan.ActionWithheld(WITHHELD_EVENT)
            wantsEvent -> createEvent(words, nowMs, zone)

            // ⚠ Ahead of the reads for the same reason `wantsEvent` is: "run my evening routine"
            // carries a time word ("evening") and would otherwise be read as a search, quietly
            // answering a different question instead of doing the thing that was asked for.
            // ⚠ Every routine utterance, verb or not: the only thing the grammar can do with a
            // routine is run it, and its no-verb answer ("I can run a routine for you — try …")
            // is the same offer in another form.
            wantsRoutine && withheld -> QueryPlan.ActionWithheld(WITHHELD_ROUTINE)
            wantsRoutine -> routineIntent(words)

            quoted != null -> findFilesNamed(quoted)

            // ⚠ Ordered by how *specific* the signal is, not by how much each source
            // holds. A device or fitness noun names exactly one source and is almost never
            // incidental; "photo" and a bare leftover word are the vaguest signals, so
            // they resolve last. Getting this order wrong is how a router answers a
            // different question confidently (MQ-D4).
            wantsDevices -> deviceIntent(words, withheld)

            wantsFitness -> findWorkouts(window, nowMs)

            wantsPlaces && text != null -> findPlaces(text)
            // "places I went in Lisbon" with nothing left to match on is not answerable —
            // a place search needs something to search *for*.
            wantsPlaces -> QueryPlan.NoMatch(
                "Tell me part of the name and I can find the place — “where was that café Blue Bottle”."
            )

            // ⚠ Ahead of the plain photo intent: "photos taken in Munich" carries a photo word
            // AND a place, and answering it as "photos from all time" would drop the only part
            // of the sentence that narrows anything.
            // ⚠ Ahead of the place join: "photos of a cat in Munich" is *both*, and content is the
            // narrower of the two — a place matches thousands of photos, a label matches few. The
            // combination is not expressible yet (§14.4), so the more selective one wins.
            // ⚠ Ahead of the "of" arm: "like" / "showing" is rarer and more deliberate than "of",
            // so when a user reaches for one they have asked for a visual match specifically.
            wantsPhotos && likeContent != null -> findPhotosLike(likeContent)

            wantsPhotos && ofContent != null -> findPhotosOfLabel(ofContent)

            wantsPhotos && atPlace != null -> findPhotosAtPlace(atPlace)

            // ⭐ "what did I do in Paris" / "when was I last at the gym". Ahead of the plain
            // photo and file arms, and deliberately AFTER them for photos: "photos in Paris"
            // is a question about pictures that happens to name a place.
            wantsRecall && recallPlace != null ->
                recallAtPlace(recallPlace, latest = wantsLatest)

            wantsPhotos -> findPhotos(window, nowMs)

            // ⚠⚠ The file-name arm is now guarded. A recall question that got this far named
            // no time and no place we could use, and answering it with a filename search is the
            // confidently-wrong failure this guard exists for — say what is missing instead.
            wantsRecall && window == null -> QueryPlan.NoMatch(
                "I can look back over your timeline by time or by place — try “what did I do " +
                    "last Saturday” or “when was I last at the gym”."
            )

            // ⚠⚠ Ahead of the file-name arm, which is the catch-all that turns every gap in this
            // grammar into a confident answer about something else (§14.5).
            words.any { it in CALENDAR_READ_WORDS } -> queryCalendar(words, nowMs, zone)

            text != null -> findFilesNamed(text, viaCatchAll = true)

            // ⭐ A time window with no other signal is the "what did I do then" question.
            // It reads as a fallback and is in fact the most natural phrasing there is —
            // "last Saturday" on its own means exactly this.
            window != null && !wantsFiles -> recallMoments(window)

            // "my files from last week" names a time but the file plane cannot filter by
            // one: File.CreatedAt is a stored *property*, not a schema field, so no chain
            // predicate can reach it (the way `File.name` needed its own accessor). Say so
            // rather than quietly answering about photos.
            wantsFiles && window != null -> noMatchTimeOnFiles(window)
            else -> noMatch()
        }
    }

    /**
     * A device utterance is either a question or a command, and the difference is one verb.
     *
     * ⚠⚠ **"toggle" is deliberately not a command.** `home-device-service` ships `setPower`
     * rather than `toggle` because a flip is relative to a reading that may already be stale;
     * an utterance is even worse placed to know the current state than a UI control is. Asking
     * for it gets an honest refusal naming the two things that do work.
     */
    private fun deviceIntent(words: List<String>, withheld: Boolean): QueryPlan {
        if (words.any { it == "toggle" }) {
            // ⚠ "but not toggle it" teaches the two commands that do work — which, here, do not.
            if (withheld) return QueryPlan.ActionWithheld(WITHHELD_DEVICE)
            return QueryPlan.NoMatch(
                "I can turn something on or off, but not toggle it — I'd have to guess what " +
                    "state it is in right now. Try “turn off the kitchen light”."
            )
        }

        val hasVerb = words.any { it in ACTION_VERBS }
        val on = words.any { it in TURN_ON_WORDS }
        val off = words.any { it in TURN_OFF_WORDS }
        val text = deviceText(words)

        // ⚠ "on" alone is not a command — "is the kitchen light on" is a question, and
        // answering it by turning the light on would be the worst possible misreading. The
        // verb is what makes it an instruction.
        if (!hasVerb || (on == off)) return findDevices(text)

        // ⚠⚠ Only the COMMAND is withheld. The question above — "is the kitchen light on" — is a
        // read and stays one, which is the whole of what a read-only runtime owes devices.
        if (withheld) return QueryPlan.ActionWithheld(WITHHELD_DEVICE)

        return QueryPlan.ControlDevice(
            text = text,
            on = on,
            limit = DEFAULT_LIMIT,
            explanation = if (on) "Turn on “$text”" else "Turn off “$text”",
        )
    }

    /**
     * ⚠⚠ **Refuses rather than guesses a time.** A read with a missing slot returns a wrong
     * *answer*, which the user sees and ignores; a write with a missing slot creates a wrong
     * *row* that persists, syncs and fires a reminder three weeks later (MQ-D1's amendment).
     * So "when?" is the answer, never "probably now".
     */
    private fun createEvent(words: List<String>, nowMs: Long, zone: TimeZone): QueryPlan {
        val start = EventTimeGrammar.resolve(words, nowMs, zone)
            ?: return QueryPlan.NoMatch(
                "I can add that once you tell me when — “add lunch with Anna tomorrow at 1pm”."
            )

        val title = eventTitle(words)
            ?: return QueryPlan.NoMatch("What should I call it?")

        return QueryPlan.CreateEvent(
            title = title,
            startLocal = start,
            explanation = "Add “$title” to your calendar",
        )
    }

    /**
     * What is left once the verb and the whole time expression are removed.
     *
     * ⚠⚠ Deliberately does **not** strip [STOP_WORDS]. A search *query* wants filler removed;
     * a *title* is free text the user will read back — "lunch with anna" must not become
     * "lunch anna". The two look like the same operation and are not.
     */
    private fun eventTitle(words: List<String>): String? {
        val remaining = words.filterNot {
            it in CREATE_EVENT_WORDS ||
                it in EventTimeGrammar.TIME_WORDS ||
                TimeGrammar.isTimeWord(it) ||
                it.toIntOrNull() != null ||
                it.removeSuffix("pm").removeSuffix("am").toIntOrNull() != null ||
                it.split(':', '.').size == 2
        }
        return remaining.joinToString(" ").trim().takeIf { it.length >= MIN_TEXT_LENGTH }
    }

    private fun findDevices(text: String) = QueryPlan.FindDevices(
        text = text,
        limit = DEFAULT_LIMIT,
        explanation = if (text.isEmpty()) {
            "Checked your smart-home devices"
        } else {
            "Checked your smart-home devices matching “$text”"
        },
    )

    /**
     * "What is on my calendar tomorrow" (§15).
     *
     * ⚠⚠ **The default is named, not silent.** An utterance with no time word resolves to *today*
     * and the explanation says so — "Looked at your calendar for **today**" — because a default the
     * user cannot see is indistinguishable from a wrong answer. Track A's §12.2 made the same call
     * for the place it searched around.
     */
    private fun queryCalendar(words: List<String>, nowMs: Long, zone: TimeZone): QueryPlan {
        val window = AgendaTimeGrammar.resolve(words, nowMs, zone)
            ?: AgendaTimeGrammar.today(
                Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
            )

        return QueryPlan.QueryCalendar(
            window = window,
            limit = DEFAULT_LIMIT,
            explanation = "Looked at your calendar for ${window.label}",
        )
    }

    private fun findWorkouts(window: TimeWindow?, nowMs: Long): QueryPlan.FindWorkouts {
        val resolved = window ?: TimeWindow.everything(nowMs)
        return QueryPlan.FindWorkouts(
            window = resolved,
            limit = DEFAULT_LIMIT,
            explanation = if (window == null) {
                "Looked through your workouts"
            } else {
                "Looked through your workouts from ${resolved.label}"
            },
        )
    }

    /**
     * The place an utterance anchors to, or `null`.
     *
     * ⚠ Requires an explicit anchor word ([PLACE_ANCHORS]). A bare leftover is a
     * *name* search — "photos beach" means files called beach, not photos taken at a place
     * called beach — and guessing between the two is the confidently-wrong failure MQ-D4 exists
     * to avoid.
     */
    private fun placeAnchor(words: List<String>): String? {
        val at = words.indexOfLast { it in PLACE_ANCHORS }
        if (at < 0) return null
        val rest = words.drop(at + 1)
            .filterNot { it in STOP_WORDS || TimeGrammar.isTimeWord(it) }
            .joinToString(" ")
            .trim()
        return rest.takeIf { it.length >= MIN_TEXT_LENGTH }
    }

    /**
     * What follows "of", or `null`.
     *
     * ⚠ "of" is in [STOP_WORDS] so it never survives into a *name* search — this reads the raw
     * word list, before that stripping, which is why the two do not conflict.
     */
    /**
     * What follows "like" / "showing" / "resembling", or `null` (MQ-4).
     *
     * ⚠ Unlike [contentAnchor] the residue is kept nearly whole — only the anchor's own stop words
     * are dropped. A semantic query is a *description*, so trimming it to keywords would throw away
     * exactly the phrasing that makes it worth more than a label: "a sunset over water" and
     * "sunset water" are not the same question.
     */
    private fun likeAnchor(words: List<String>): String? {
        val at = words.indexOfLast { it in LIKE_ANCHORS }
        if (at < 0) return null
        val rest = words.drop(at + 1)
            .filterNot { TimeGrammar.isTimeWord(it) }
            .joinToString(" ")
            .trim()
        return rest.takeIf { it.length >= MIN_TEXT_LENGTH }
    }

    private fun contentAnchor(words: List<String>): String? {
        val at = words.indexOfLast { it == CONTENT_ANCHOR }
        if (at < 0) return null
        val rest = words.drop(at + 1)
            .filterNot { it in STOP_WORDS || TimeGrammar.isTimeWord(it) || it in PLACE_ANCHORS }
            .joinToString(" ")
            .trim()
        return rest.takeIf { it.length >= MIN_TEXT_LENGTH }
    }

    private fun findPhotosLike(text: String) = QueryPlan.FindPhotosLike(
        text = text,
        limit = DEFAULT_LIMIT,
        // ⚠ Says "look like", never "of": the user is about to be shown ranked guesses rather than
        // rows that carry the word they typed, and the explanation is the only thing that sets
        // their expectation before the results land.
        explanation = "Photos that look like “$text”.",
    )

    private fun findPhotosOfLabel(text: String) = QueryPlan.FindPhotosOfLabel(
        text = text,
        limit = DEFAULT_LIMIT,
        explanation = "Searched your photos for “$text”",
    )

    private fun findPhotosAtPlace(text: String) = QueryPlan.FindPhotosAtPlace(
        text = text,
        limit = DEFAULT_LIMIT,
        explanation = "Searched your photos taken around “$text”",
    )

    private fun recallAtPlace(text: String, latest: Boolean) = QueryPlan.RecallAtPlace(
        text = text,
        // ⚠ One row when the question is "when", because "when were you last there" has exactly
        // one true answer and a list of five would be answering "when have you been there".
        limit = if (latest) 1 else DEFAULT_LIMIT,
        latestOnly = latest,
        explanation = if (latest) "Looked for the last time you were at “$text”"
        else "Looked at what you did at “$text”",
    )

    private fun findPlaces(text: String) = QueryPlan.FindPlaces(
        text = text,
        limit = DEFAULT_LIMIT,
        explanation = "Searched the places you have been for “$text”",
    )

    private fun recallMoments(window: TimeWindow) = QueryPlan.RecallMoments(
        window = window,
        limit = DEFAULT_LIMIT,
        explanation = "Looked at what you did ${window.label}",
    )

    private fun findFilesNamed(text: String, viaCatchAll: Boolean = false) =
        QueryPlan.FindFilesNamed(
            text = text,
            limit = DEFAULT_LIMIT,
            explanation = "Searched your files for names containing “$text”",
            viaCatchAll = viaCatchAll,
        )

    private fun findPhotos(window: TimeWindow?, nowMs: Long): QueryPlan.FindPhotos {
        val resolved = window ?: TimeWindow.everything(nowMs)
        return QueryPlan.FindPhotos(
            window = resolved,
            limit = DEFAULT_LIMIT,
            explanation = if (window == null) {
                "Searched your files for photos"
            } else {
                // Naming the resolved range is what makes a hemisphere- or
                // calendar-dependent word correctable instead of silently wrong.
                "Searched your files for photos from ${resolved.label}"
            },
        )
    }

    private fun noMatchTimeOnFiles(window: TimeWindow) = QueryPlan.NoMatch(
        "I can only search files by name, not by date yet — " +
            "though I can find photos from ${window.label}."
    )

    private fun noMatch() = QueryPlan.NoMatch(CAPABILITIES)

    // ⚠⚠ Three sentences on one pattern, each naming (a) the thing that is not available, (b)
    // that THIS DEVICE and not the request is the reason, and (c) the read that does work.
    // "This device" rather than a platform name: the planner is common code, and the same
    // runtime is an iPad's.
    // ⚠ [CAPABILITIES] needs no twin — it has never listed an action.
    private const val WITHHELD_DEVICE =
        "I can't switch anything on or off from this device yet — here I can only look things " +
            "up. I can tell you what state something is in: “is the kitchen light on”."

    private const val WITHHELD_ROUTINE =
        "I can't run routines from this device yet — here I can only look things up."

    private const val WITHHELD_EVENT =
        "I can't add anything to your calendar from this device yet — here I can only look " +
            "things up. I can tell you what is already there: “what's on my calendar tomorrow”."

    /**
     * ⚠ Says what Mia *can* do rather than apologising. A no-match that teaches the
     * grammar is the difference between a closed intent set that feels small and one
     * that feels broken.
     */
    private const val CAPABILITIES =
        "I can find photos from a time you name (“photos from last summer”), files by name " +
            "(“find the invoice”), what you did on a day (“last Saturday”), places you have " +
            "been (“where was that café Blue Bottle”), your workouts (“how far did I run " +
            "this week”), and the state of your smart-home devices (“is the kitchen light on”)."

    private fun tokenize(utterance: String): List<String> =
        utterance.lowercase()
            .split(' ', '\t', '\n', '\r', ',', ';', '?', '!')
            .map { it.trim('.', '\'', '’', '"', '“', '”', '(', ')') }
            .filter { it.isNotEmpty() }

    /** The contents of the first `"…"` or `“…”` pair, if the user quoted anything. */
    private fun quotedPhrase(utterance: String): String? {
        val open = utterance.indexOfFirst { it == '"' || it == '“' }
        if (open < 0) return null
        val close = utterance.drop(open + 1).indexOfFirst { it == '"' || it == '”' }
        if (close < 0) return null
        return utterance.substring(open + 1, open + 1 + close).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * What is left once openers, filler and time words are removed — the name fragment
     * the user is really after. `null` when nothing substantial survives, which is what
     * keeps "show me my files" from becoming a search for the empty string.
     */
    /**
     * The device name the user meant, keeping the device noun itself — "kitchen light" is
     * the name on the row, so stripping "light" would search for "kitchen".
     */
    /**
     * A routine utterance is either a request to run one or a request to see them, and — exactly as
     * with devices — the difference is one verb (RE-5).
     *
     * ⚠⚠ Listing is **not** implemented, and this says so rather than falling through to a search
     * that would answer with files. Home's Automations tab is the place that lists them; naming it
     * is more useful than a generic no.
     */
    private fun routineIntent(words: List<String>): QueryPlan {
        if (words.none { it in RUN_VERBS }) {
            return QueryPlan.NoMatch(
                "I can run a routine for you — try “run my good morning routine”. To see them all, " +
                    "open the Automations tab in Home."
            )
        }

        val text = leftoverText(words, extraStopWords = ROUTINE_WORDS + RUN_VERBS)
            ?: return QueryPlan.NoMatch("Which routine should I run?")

        return QueryPlan.RunRoutine(text = text, explanation = "Run “$text”")
    }

    private fun deviceText(words: List<String>): String =
        words.filterNot { it in STOP_WORDS || TimeGrammar.isTimeWord(it) }
            .joinToString(" ")
            .trim()

    private fun leftoverText(words: List<String>, extraStopWords: Set<String> = emptySet()): String? {
        val remaining = words.filterNot {
            it in STOP_WORDS || it in extraStopWords || TimeGrammar.isTimeWord(it)
        }
        val joined = remaining.joinToString(" ").trim()
        return joined.takeIf { it.length >= MIN_TEXT_LENGTH }
    }
}
