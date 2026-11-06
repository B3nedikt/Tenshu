package org.tenshu.mia.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.request.RequestChainBuilder
import org.tenshu.core.core.TContext
import org.tenshu.core.contains
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.QueryOccurrences
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.Workout
import org.tenshu.core.equalTo
import org.tenshu.core.oneOf
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.inRange
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceService
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.TimelineService
import org.tenshu.inference.InferenceService
import org.tenshu.inference.MediaLabel
import org.tenshu.inference.PhotosLike
import org.tenshu.inference.SimilarPhoto
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.core.core.findOperation
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.Name
import org.tenshu.core.file.name
import org.tenshu.core.file.FileService
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.file.system.PhotosCapturedBetween
import org.tenshu.core.file.system.PhotosNear
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.mia.MiaSource
import org.tenshu.mia.plan.QueryPlan

/**
 * [QueryPlan] → an ordinary chain-DSL callstack (`mia-query-layer.md` MQ-D3).
 *
 * ⭐⭐ **The fact that makes this work: a chain is already data, not code.**
 * `RequestChain.getCallstack(): List<Data>` — each step an `OperationDto`, nested
 * predicates riding as `TList<Data>` — so building a query means constructing a typed
 * composite tree that can be walked, validated and costed *before* anything runs. Mia
 * gets no query language of its own; it emits exactly what every app's read path emits,
 * which is why [MiaChainValidator] is cheap rather than heroic.
 *
 * ⭐ And the chain is what buys the performance: a lowered `filter(...).take(n)` inherits
 * ordered-window fusion, `@defer` and projection pushdown automatically, none of which a
 * bespoke executor would get.
 *
 * ⚠ The chain is built with the ordinary client DSL over a [RequestChainBuilder], so the
 * callstack Mia produces is identical in shape to the one a client would have built for
 * the same read. Nothing here is a private back door.
 *
 * ⚠⚠ **It must be `RequestChainBuilder`, not `CallstackMaterializer`/`SimpleRequestChain`
 * — and the difference is invisible until a predicate is involved.** `SimpleRequestChain`
 * *mutates* its callstack in place, while `FilterRo` relies on the immutable `copy()`
 * semantics of `RequestChainBuilder`: it builds the predicate on a derived chain, reads
 * the nested `TList` off it, and then appends its `OperationDto` to the **original**.
 * Under a mutating chain the original has already grown the predicate list, so the
 * callstack ends up carrying a stray top-level `TList` and execution dies with
 * `Could not handle protocol: tenshu/core/list`. Dynamic-property relations never noticed
 * because their chains are linear.
 */
internal class QueryPlanLowering(
    private val context: TContext,
) {

    /** `null` for a plan with nothing to run ([QueryPlan.NoMatch]). */
    fun lower(plan: QueryPlan): List<Data>? = when (plan) {
        is QueryPlan.FindPhotos -> lowerFindPhotos(plan)
        is QueryPlan.FindFilesNamed -> lowerFindFilesNamed(plan)
        is QueryPlan.FindPlaces -> lowerFindPlaces(plan)
        // ⭐ Stage ONE only. This resolves the place NAME to Place rows; the photos come from a
        // second chain the implementation runs once it has their coordinates (§12). Both stages
        // are ordinary validated reads — the join is Mia's, not a new primitive.
        is QueryPlan.FindPhotosAtPlace -> lowerPlaceSearch(plan.text, plan.limit)
        is QueryPlan.RecallMoments -> lowerRecallMoments(plan)
        // ⚠ Stage ONE only — the place name resolves here, and the moments are read by
        // [lowerMomentsAtPlace] once the ids exist. Same two-stage shape as the photo join.
        is QueryPlan.RecallAtPlace -> lowerPlaceSearch(plan.text, RECALL_PLACE_MATCH_LIMIT)
        // ⚠ One chain, unlike the place joins: a label row already knows which file it is about.
        is QueryPlan.FindPhotosOfLabel -> lowerLabelSearch(plan.text, plan.limit)
        // ⚠⚠ The one read here that can REFUSE. Every other lowering either finds rows or finds
        // none; `photosLike` throws when the capability is off, no model is installed or nothing
        // is indexed, and `AskMiaImplementation` has to carry that reason to the user rather than
        // flattening it into "no matches".
        is QueryPlan.FindPhotosLike -> lowerPhotosLike(plan.text, plan.limit)
        is QueryPlan.FindWorkouts -> lowerFindWorkouts(plan)
        is QueryPlan.FindDevices -> lowerFindDevices(plan)
        // ⭐ A device COMMAND lowers to the same read as a device question. The write is not
        // in this chain and must never be (MQ-D10): resolving which device the user meant is
        // a read, and only `ConfirmMiaAction` mutates.
        is QueryPlan.ControlDevice -> lowerDeviceSearch(plan.text, plan.limit)
        // ⚠⚠ A write lowers to NOTHING. There is no read that resolves it — the defaults come
        // from preference rows, not from a chain — and putting the write itself in a chain is
        // exactly what MQ-D10's reads-only allowlist forbids.
        // ⭐ The calendar read: ONE op call. An occurrence is computed rather than stored, so the
        // expansion happens on the calendar's own side where it needs no chain bound —
        // see [lowerCalendarOccurrences].
        is QueryPlan.QueryCalendar -> lowerCalendarOccurrences(
            from = plan.window.from,
            to = plan.window.to,
            // ⚠ ONE more than will be shown, so "there may be more" can be a fact rather than a
            // guess: inferring saturation from `size == limit` is wrong at exactly `limit`.
            limit = minOf(plan.limit + 1, CALENDAR_OCCURRENCE_LIMIT),
        )
        // ⚠⚠ A write lowers to NOTHING. There is no read that resolves it — the defaults come
        // from preference rows, not from a chain — and putting the write itself in a chain is
        // exactly what MQ-D10's reads-only allowlist forbids.
        is QueryPlan.CreateEvent -> null
        // ⚠ Same rule, RE-5: firing a routine is a write, and the name resolution that precedes it
        // is a direct row read rather than a chain — so there is nothing here to lower either.
        is QueryPlan.RunRoutine -> null
        // ⚠ Nothing was resolved, so there is nothing to read (`MiaActionOffer.WITHHELD`).
        is QueryPlan.ActionWithheld -> null
        is QueryPlan.NoMatch -> null
    }

    /**
     * ⭐ The three window lowerings below are what `inRange` bought
     * (`core-operation-dispatch.md` §5). Each is the *same* shape — narrow the service's
     * own relation by a half-open window, bound it, project it — which is the point: a new
     * time-bounded source is now a source adapter, not an operation.
     */
    private fun lowerRecallMoments(plan: QueryPlan.RecallMoments): List<Data> =
        chainOver<TimelineService>(TimelineService.ID) {
            moments
                .filter<Moment> {
                    timestamp.inRange(plan.window.startMs, plan.window.endMsExclusive)
                }
                .take(plan.limit)
                .findOperation<GetAll<Moment>>(GetAll.ID)
                .getAll()
        }

    /**
     * ⚠ Filters on `start`, not `end`: a workout belongs to the day you set out on. An
     * activity spanning midnight would otherwise land in tomorrow, or in both windows if
     * the predicate looked at either end.
     */
    /**
     * Stage two of the recall join: the moments that happened at [placeId], newest first.
     *
     * ⭐⭐ **The first nested-field predicate in the codebase.** `subject.id` reaches through the
     * `SubjectRef` composite a moment carries, which nothing had asked the chain DSL to do before
     * — every other predicate here filters a top-level field. It builds and executes; the probe
     * that established it is now `momentsAtAPlaceReadThroughTheNestedSubjectField` in
     * `AskMiaRecallTest`.
     *
     * ⚠ Sorted descending in the chain rather than in Kotlin, so `sortByDescending(…).take(1)`
     * for "when was I last there" runs as ordered-window fusion's single bounded pass (F1) instead
     * of materialising every visit the user ever made to that place.
     */
    fun lowerMomentsAtPlace(placeId: String, limit: Int): List<Data> =
        chainOver<TimelineService>(TimelineService.ID) {
            moments
                .filter<Moment> { subject.id equalTo placeId }
                .sortByDescending<Moment> { timestamp }
                .take(limit)
                .findOperation<GetAll<Moment>>(GetAll.ID)
                .getAll()
        }

    /**
     * Stage one of the label join: which files a model said contain [text].
     *
     * ⭐ An ordinary `filter { contains }` — IN-D12's point that **label search costs Mia nothing
     * structural**, unlike the nearest-neighbour search that needs an op.
     *
     * ⚠ The projection is deliberately narrow: the file path and the label, nothing else. A
     * confidence or a `producedBy` in the citation would be showing our working.
     *
     * ⚠⚠ **There is no stage two, and that is deliberate.** A `MediaLabel` row already carries the
     * file's identity, so re-reading `File` would buy exactly one field — the capture time — at the
     * cost of a second chain per result. `MiaHit.atMs` is documented as `0` for "unknown, not
     * 1970", which is the honest answer here and cheaper than the alternative.
     */
    /**
     * ⚠ An **op**, not a chain of predicates — IN-D12's whole point. Nearest-neighbour over a
     * vector is expressible as no predicate the DSL has, and no predicate we could add would turn
     * a *ranked* similarity into a filter.
     */
    fun lowerPhotosLike(text: String, limit: Int): List<Data> =
        chainOver<InferenceService>(InferenceService.ID) {
            findOperation<PhotosLike>(PhotosLike.ID).photosLike(text, limit)
        }

    fun lowerLabelSearch(text: String, limit: Int): List<Data> =
        chainOver<InferenceService>(InferenceService.ID) {
            labels
                .filter<MediaLabel> { label.contains(text, ignoreCase = true) }
                .take(limit)
                .findOperation<GetAll<MediaLabel>>(GetAll.ID)
                .getAll()
        }

    /**
     * Which of [paths] name a `File` row that exists right now (P0g, `mia-query-layer.md` §18).
     *
     * ⚠⚠ **Machinery, not a source.** A label or a visual-search hit carries a path, and nothing
     * deletes those rows when the file goes — so before Mia cites one it asks the file plane whether
     * the path is still there. The answer is a set of paths it already holds, so this read discloses
     * nothing the label grant has not; it runs under [EXISTENCE_POLICY], never under the user's
     * FILES grant, or revoking file names would make every label hit unverifiable.
     *
     * ⚠ At most [EXISTENCE_CHUNK] paths per call, and the reply is bounded by them, never by the
     * realm: `oneOf` narrows what crosses the wire. The walk itself is O(realm), which is why the
     * caller sends one chunk per round, not one chain per hit.
     */
    fun lowerLiveFiles(paths: List<String>): List<Data> {
        require(paths.size <= EXISTENCE_CHUNK) { "at most $EXISTENCE_CHUNK paths per existence read" }
        return chainOverFileService {
            defaultFileSystem.root.files
                .traverse()
                .filter<File> { path oneOf paths }
                .take(paths.size)
                .select { fields(File.Field.PATH) }
                .findOperation<GetAll<File>>(GetAll.ID)
                .getAll()
        }
    }

    private fun lowerFindWorkouts(plan: QueryPlan.FindWorkouts): List<Data> =
        chainOver<HealthService>(HealthService.ID) {
            workouts
                .filter<Workout> {
                    start.inRange(plan.window.startMs, plan.window.endMsExclusive)
                }
                .take(plan.limit)
                .findOperation<GetAll<Workout>>(GetAll.ID)
                .getAll()
        }

    /**
     * The calendar read — **one op call**, bounded by occurrences rather than by series (§15.1).
     *
     * ⭐⭐ **This used to be two unfiltered scans and a fold in the proxy**, and the reason it
     * could not stay that way is the cap: the validator refuses an unbounded chain, so the scan
     * had to `take(200)` series — and **no ordering makes that truncation safe**, because a series
     * stores its *first* occurrence, so "the newest 200" drops precisely the old weekly meeting
     * that still happens tomorrow. `queryOccurrences` moves the scan inside the calendar service,
     * where it needs no chain bound, and caps the **answer** instead: occurrences inside a window
     * are naturally few and ordered by when they happen, so taking the earliest is exactly what a
     * window question means.
     *
     * ⚠ The fold did not move house — it moved *down*. `OccurrenceResolver` still does the
     * expansion, still in `:calendar-service-common`, still the same code the Calendar app and the
     * reminder scheduler use, so an agenda in Mia and an agenda in Calendar cannot disagree about
     * what recurs. What changed is who calls it.
     *
     * ⚠ Dates, not instants: an event is a civil date-time (`Event`'s "an event is not an
     * instant"), and the plan's window is already a pair of `LocalDate`s.
     */
    fun lowerCalendarOccurrences(from: LocalDate, to: LocalDate, limit: Int): List<Data> =
        chainOver<CalendarService>(CalendarService.ID) {
            findOperation<QueryOccurrences>(QueryOccurrences.ID)
                .queryOccurrences(
                    fromDate = from.toString(),
                    toDate = to.toString(),
                    limit = limit,
                )
        }

    private fun lowerFindPlaces(plan: QueryPlan.FindPlaces): List<Data> =
        lowerPlaceSearch(plan.text, plan.limit)

    private fun lowerPlaceSearch(text: String, limit: Int): List<Data> =
        chainOver<PlaceService>(PlaceService.ID) {
            places
                .filter<Place> { label.contains(text, ignoreCase = true) }
                .take(limit)
                .findOperation<GetAll<Place>>(GetAll.ID)
                .getAll()
        }

    /**
     * Stage two: photos near a resolved place. A separate callstack because it depends on stage
     * one's *result* — and each is validated in its own right, so the two-stage shape adds no
     * new surface to MQ-D10.
     */
    fun lowerPhotosNear(latE7: Long, lngE7: Long, radiusM: Long, limit: Int): List<Data> =
        chainOver<FileService>(FileService.ID) {
            defaultFileSystem.findOperation<PhotosNear>(PhotosNear.ID)
                .photosNear(latE7 = latE7, lngE7 = lngE7, radiusM = radiusM, limit = limit)
        }

    /**
     * ⚠ Reads the inventory only. The device's live state rides a *separate*
     * `DeviceState` row keyed by the same `deviceId`, joined answer-side rather than in the
     * chain — the DSL has no join, and inventing one for a two-list lookup would be a query
     * language, which MQ-D3 exists to avoid.
     */
    private fun lowerFindDevices(plan: QueryPlan.FindDevices): List<Data> =
        lowerDeviceSearch(plan.text, plan.limit)

    private fun lowerDeviceSearch(text: String, limit: Int): List<Data> =
        chainOver<HomeDeviceService>(HomeDeviceService.ID) {
            homeDevices
                .filter<HomeDevice> { name.contains(text, ignoreCase = true) }
                .take(limit)
                .findOperation<GetAll<HomeDevice>>(GetAll.ID)
                .getAll()
        }

    /**
     * ⚠ **Lowers to the shipped `photosCapturedBetween` op rather than to a predicate,
     * because a time *window* is not expressible in the chain DSL at all** — `equalTo`
     * and `contains` are the only two predicates, so there is no comparison to push down.
     * An op call is an ordinary callstack step, so MQ-D3 holds exactly as it does for the
     * filter path: the validator walks both the same way.
     */
    private fun lowerFindPhotos(plan: QueryPlan.FindPhotos): List<Data> =
        chainOverFileService {
            defaultFileSystem.findOperation<PhotosCapturedBetween>(PhotosCapturedBetween.ID)
                .photosCapturedBetween(
                    startMs = plan.window.startMs,
                    endMs = plan.window.endMsExclusive,
                    limit = plan.limit,
                )
        }

    /**
     * The same chain Drive's own search runs, plus a `take`.
     *
     * ⚠ `ignoreCase` is an explicit opt-in: `contains` defaults case-sensitive like
     * Kotlin's, and a user asking for "photo" means "Photo.jpg" too.
     *
     * ⚠ The `take` is not an optimisation, it is MQ-D10's cost bound made structural —
     * the validator refuses an unbounded chain, so a plan that forgot it cannot execute.
     */
    private fun lowerFindFilesNamed(plan: QueryPlan.FindFilesNamed): List<Data> =
        chainOverFileService {
            defaultFileSystem.root.files
                .traverse()
                .filter<File> { name.contains(plan.text, ignoreCase = true) }
                .take(plan.limit)
                .select {
                    fields(File.Field.PATH)
                    relations(File.CreatedAt)
                }
                .findOperation<GetAll<File>>(GetAll.ID)
                .getAll()
        }

    private fun chainOverFileService(block: FileService.() -> Data): List<Data> =
        chainOver(FileService.ID, block)

    /**
     * Runs [block] against a fresh request object for [serviceId] and returns its
     * callstack — the one place a plan becomes a chain, shared by every source.
     */
    private fun <S : Data> chainOver(serviceId: String, block: S.() -> Data): List<Data> {
        val schema = context.schemaProvider().getSchema(serviceId)
            ?: error("No schema registered for service: $serviceId")
        val service = context.roProvider().createRequestObject<S>(
            type = schema.toType(),
            requestChain = RequestChainBuilder(context),
            context = context,
        )
        return (service.block() as RequestObject).requestChain.getCallstack()
    }

    companion object {

        /**
         * What MQ-1 permits, and nothing more.
         *
         * ⚠ Hardcoded here **only** because MQ-1 has one source. MQ-2 replaces
         * [MiaChainValidator.Policy.allowedServiceIds] with the user's revocable
         * per-service grants — which is why the validator takes a policy rather than
         * closing over these sets.
         *
         * ⚠⚠ The operation allowlist is the read-only boundary. Every id below reads;
         * none writes. Adding a source means adding its *read* ops here deliberately,
         * one at a time — which is exactly the review moment this list exists to create.
         */
        /**
         * The policy for a given set of grants (MQ-D6). ⚠ [MiaChainValidator.Policy]'s
         * `allowedServiceIds` is **derived from the user's switches**, which is why the
         * validator took a policy rather than closing over a constant from the start.
         *
         * ⚠ Belt and braces with the planner's own grant check: the planner refuses a revoked
         * source with a message that names it, and this refuses the *chain* if one ever got
         * past. Two checks because they answer different questions — "should Mia have tried"
         * and "may this callstack run" — and only the second is a security boundary.
         */
        /**
         * ⚠⚠ **The op allowlist is narrowed per grant, not just the service allowlist.** Until MQ-4
         * every `MiaSource` owned a distinct service, so "which services may this chain touch"
         * separated them completely. `LABELS` and `VISUAL_SEARCH` share `InferenceService`, so the
         * service check alone would let a chain through on *either* grant — and revoking visual
         * search while keeping labels would have changed nothing the validator can see. The op is
         * the only thing that distinguishes the two chains, so the op set has to move with the
         * grant.
         *
         * ⚠ Defence in depth, not the primary control: the planner already refuses a plan whose
         * source is not granted, and does it by *name*, which is the answer the user should get.
         * This is what catches a planner bug.
         */
        fun policyFor(granted: Set<MiaSource>): MiaChainValidator.Policy =
            POLICY.copy(
                allowedServiceIds = granted.mapTo(mutableSetOf()) { it.chainReferenceId },
                allowedOperationIds = if (MiaSource.VISUAL_SEARCH in granted) {
                    POLICY.allowedOperationIds
                } else {
                    POLICY.allowedOperationIds - PhotosLike.ID
                },
            )

        val POLICY = MiaChainValidator.Policy(
            allowedOperationIds = setOf(
                PhotosCapturedBetween.ID,
                PhotosNear.ID,
                // MQ-4. ⚠ Removed again by `policyFor` when `VISUAL_SEARCH` is revoked.
                PhotosLike.ID,
                org.tenshu.core.collection.list.Traverse.ID,
                org.tenshu.core.collection.list.Filter.ID,
                Take.ID,
                org.tenshu.core.collection.list.Select.ID,
                GetAll.ID,
                org.tenshu.core.Contains.ID,
                org.tenshu.core.InRange.ID,
                // Track B: the recall join reads moments by their subject and orders them.
                org.tenshu.core.EqualTo.ID,
                org.tenshu.core.collection.list.SortBy.ID,
                // §15.1's fix: the calendar window read. ⚠ It replaces the two unfiltered
                // `events` / `eventOverrides` scans this policy used to have to allow.
                QueryOccurrences.ID,
            ),
            // ⚠⚠ Each entry is one deliberate widening of what Mia may reach, added the
            // day its source landed. MQ-2b replaces this constant with the user's
            // revocable per-service grants — the reason the validator takes a policy.
            //
            // ⚠ **Two shapes appear here, and they are not interchangeable.** A chain
            // reaching a `serviceDataRelation` (`service.moments`, `service.workouts`)
            // emits a `ServiceTypeReference` carrying the **service's own id**, because
            // `ServiceRo.serviceRelation` prepends it. The file plane is the odd one out:
            // `defaultFileSystem` is a *service-to-service* relation, so what lands in the
            // callstack is that **relation's** id and no bare service id at all. Listing
            // the wrong one of the two refuses every chain from that source with
            // `service not granted`.
            allowedServiceIds = MiaSource.entries.mapTo(mutableSetOf()) { it.chainReferenceId },
            // Metadata yes, content bytes no. `File.Data`, `File.Preview` and
            // `File.Thumbnail` are deliberately absent: an intent grammar never asks for
            // bytes implicitly, and a chain that asks for them explicitly is exactly what
            // the projection allowlist stands in for while per-property capabilities do
            // not exist.
            // ⚠ The window sources project nothing at all — `getAll()` returns whole rows
            // and the answer mapper reads three scalars off each. That is affordable
            // because these rows are small metadata (a `Moment` is a title, a kind and a
            // timestamp), and it is why no entry is needed for them here.
            allowedProjectionIds = setOf(
                File.Field.PATH,
                File.CreatedAt.id,
                File.Name.id,
            ),
            boundingOperations = mapOf(
                PhotosCapturedBetween.ID to PhotosCapturedBetween.Params.LIMIT,
                PhotosNear.ID to PhotosNear.Params.LIMIT,
                // MQ-4. ⚠ Registering the op in `allowedOperationIds` is not enough: an op that
                // returns a list has to declare WHICH param caps it, or the validator refuses the
                // chain as unbounded. It caught this on the first run — the check earning its keep
                // on the third op to need it.
                PhotosLike.ID to PhotosLike.Params.LIMIT,
                // ⚠⚠ Without this entry the validator refuses the chain as unbounded — an op that
                // returns a list must declare WHICH param caps it. It has caught every one of the
                // four ops that needed it.
                QueryOccurrences.ID to QueryOccurrences.Params.LIMIT,
            ),
            maxRows = MAX_ROWS,
            maxSteps = MAX_STEPS,
            maxNestingDepth = MAX_NESTING_DEPTH,
        )

        /**
         * ⚠ A ceiling on any single answer, well above [org.tenshu.mia.plan.QueryPlanner.DEFAULT_LIMIT]
         * so the grammar has room to move without the validator becoming the thing that
         * has to change. It bounds what a *future* generator can ask for, which is its
         * whole purpose.
         *
         * ⚠⚠ **It no longer bounds the calendar read's scan.** It used to, and that was the §15.1
         * defect: a *chain* bound is a bound on rows read, which for the calendar meant series —
         * the one thing there is no safe order to truncate. `queryOccurrences` puts the scan
         * behind an op, so this ceiling applies to the occurrences that come back instead.
         */
        const val MAX_ROWS = 200

        /**
         * How many `Place` rows a recall question resolves against.
         *
         * ⚠ Deliberately not the plan's own limit: "when was I last at the gym" asks for **one
         * moment**, not one place, and resolving a single place would answer about whichever row
         * happened to sort first when a user has "Gym" and "Gym (new)".
         */
        const val RECALL_PLACE_MATCH_LIMIT = 5

        /**
         * How many occurrences the calendar read will return.
         *
         * ⭐⭐ **This replaced a cap on the number of *series scanned*, and that is a different
         * kind of limit.** The old one truncated the input with no safe order to truncate by — a
         * series stores its **first** occurrence, so dropping "the oldest" drops the weekly meeting
         * that still happens tomorrow (§15.1). This one truncates the *output*, which the resolver
         * has already ordered by when it happens, so what falls off the end is the furthest away.
         *
         * ⚠ Generous rather than tight: a day holds few occurrences, and a caller asking for a
         * whole month should not be silently shortened. `AskMiaImplementation` asks for one more
         * than it will show, so it can say *"there may be more"* only when there truly are.
         */
        const val CALENDAR_OCCURRENCE_LIMIT = 200

        /**
         * Paths per existence read ([lowerLiveFiles]). ⚠ `oneOf` scans its candidates per row, so
         * a walk costs rows × candidates — the `photos-app.md` §39.2 chunk, for the same reason.
         * Equal to [MAX_ROWS], so the chain's `take` is always one the validator accepts.
         */
        const val EXISTENCE_CHUNK = 200

        /**
         * The policy the existence read runs under — the file plane, read ops, the path only.
         *
         * ⚠⚠ Fixed, not derived from the grants (see [lowerLiveFiles]), and **separate** from
         * [POLICY] so that `oneOf` is not quietly added to what a planned chain may say.
         */
        val EXISTENCE_POLICY = MiaChainValidator.Policy(
            allowedOperationIds = setOf(
                org.tenshu.core.collection.list.Traverse.ID,
                org.tenshu.core.collection.list.Filter.ID,
                Take.ID,
                org.tenshu.core.collection.list.Select.ID,
                GetAll.ID,
                org.tenshu.core.OneOf.ID,
            ),
            allowedServiceIds = setOf(MiaSource.FILES.chainReferenceId),
            allowedProjectionIds = setOf(File.Field.PATH),
            boundingOperations = emptyMap(),
            maxRows = EXISTENCE_CHUNK,
            maxSteps = MAX_STEPS,
            maxNestingDepth = MAX_NESTING_DEPTH,
        )

        /** Longest lowered chain today is 8 steps; twice that leaves room and still bounds. */
        const val MAX_STEPS = 16

        /** A predicate inside a selector inside a params list — three is already generous. */
        const val MAX_NESTING_DEPTH = 4
    }
}
