package org.tenshu.sync.proxy

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.entity.DataId

import org.tenshu.core.file.sync.DeclarableContent
import org.tenshu.sync.common.ContentDescription

/**
 * ⭐ **The one pass that reaches content uploaded before the declaration seam existed**
 * (`content-plane-refcount.md` §9.7) — a **describe-only** pass.
 *
 * ## ⭐ It describes ref sets and declares no edge
 *
 * Which holder references which content reaches the relay only with that holder's own push, as the
 * row's complete `contentEdgesOf` set, and leaves only with its tombstone, cascade or reap
 * (`content-plane-cr2-b3-wire-spec.md` §3.4/§5); the relay has no edge form of
 * `/sync/content/declare`. All this pass sends is what only a device can say: **which blob refs
 * each content object is made of** (§10.12 Q3), so the relay can free them once nothing references
 * the content. A description names no holder.
 *
 * Every emit declares inline, so new content is described by construction. Nothing describes the
 * **existing** corpus — §9.3's populations 1, 2 and 3 (bytes uploaded before inline declaration,
 * declarations whose request failed, and the legacy manifest path). A file is emitted **once**, so
 * none of them heals on its own: without this pass they stay undeclared for ever, and under §9.5's
 * positive rule undeclared means unreclaimable.
 *
 * ```
 * live files → (holder, property, contentId) → fetch + decrypt each manifest → refs → declare
 * ```
 *
 * ## ⚠⚠ Why this is not the veto's walk, and must not be run on its schedule
 *
 * The veto needs content **ids**, read locally in one pass. This needs each content object's
 * **refs**, and a ref is `sha256(ciphertext)` — so it must fetch and decrypt that content's
 * manifest, one `GET` per live content object. That is the cost §6 called impractical *per sweep*.
 * It is fine **once**, behind idle+charging, which is why it carries its own completion marker.
 *
 * ## ⚠⚠ Every failure direction here is a leak, and that is by construction
 *
 * Describing **less** leaves content undescribed, which under §9.5 makes it unreachable rather
 * than reclaimable: a leak, never a loss — which is precisely why it may describe what it found
 * even when the walk did not finish.
 *
 * ⚠ **What it may NOT do is record itself complete over a degraded run.** The pass runs once by
 * design, so a latched marker over a partial walk strands whatever it missed for ever. [Outcome]
 * separates the two facts and [run] only ever reports `complete = true` when both halves held.
 */
class ContentDeclarationBackfill(
    private val declarableContent: suspend () -> DeclarableContent.Result,
    /** Chunk refs of one contentId, or null when its manifest cannot be fetched or parsed. */
    private val chunkRefsOf: suspend (contentId: String) -> List<String>?,
    /**
     * Whether the relay holds this content's manifest: true, false (its 404), or **null when it could
     * not be asked**. Production points this at `ContentBlobStore.hasChunk` of the manifest ref.
     *
     * ⚠⚠ Required, with no default, because [chunkRefsOf] cannot tell these apart: it answers null
     * for a 404, for a 500, for a socket timeout and for a manifest that will not parse. Read as one
     * thing they let a transient relay failure pass for "this thumbnail was never emitted", and the
     * pass would record itself complete over content it had described without its refs.
     */
    private val manifestPresent: suspend (contentId: String) -> Boolean?,
    /**
     * Hands a content the relay holds **no manifest** for to this device's repair, which acts only
     * when the device holds the bytes of a repairable property (`localBytesContentEnsure`). True when
     * it acted. `null` on a platform that wires no repair.
     *
     * ⚠⚠ Required, with no default: the pass is the one place that learns, on the device that
     * uploaded a file, that its manifest never reached the relay, and an omitted hook leaves that file
     * broken for every other device with nothing said: such a file has all its chunks stored, no
     * manifest and no upload intent, so nothing else ever retries it.
     * ⚠ Called only for the relay's own 404, never for an unanswered fetch.
     * ⚠⚠ A pass that handed anything over does NOT record itself complete. "Acted" is not "landed":
     * the upload can be parked behind a gate, or publish and then have its declare refused, and this
     * pass described that content with no refs before the repair ran. The next pass finds the
     * manifest, describes its refs and latches; until then it runs again.
     */
    private val repairAbsent: (suspend (holder: DataId, propertyId: String, contentId: String) -> Boolean)?,
    /** The manifest's own blob ref, derivable with no fetch. */
    private val manifestRefOf: (contentId: String) -> String,
    /**
     * Describes a batch of content objects onto the content plane. Production points this at
     * `ContentBlobStore.describeContent`.
     */
    private val describe: suspend (List<ContentDescription>) -> Boolean,
    /**
     * Whether this device has finished catching up — `syncManager.backfillCursor == 0L`, the same
     * predicate `SliceSnapshotWriter` asks before it seals anything.
     *
     * ⚠⚠ **Required, with no default: a default would let the pass latch over a realm still
     * arriving.** The marker records "this device has described its corpus"; a walk over a realm that is still
     * arriving has not seen that corpus, however cleanly it finished. See [run].
     */
    private val isCaughtUp: suspend () -> Boolean,
    /**
     * This runtime's content emits, so the pass can tell whether one raced it — see [EmitGuard].
     * Production passes `SyncObserver.backfillEmitGuard`.
     *
     * ⚠⚠ Required, with no default, for the same reason as [isCaughtUp]: a pass-through default would
     * reinstate the race it exists to close, silently.
     */
    private val emitGuard: EmitGuard,
    /** Whether a previous pass already recorded itself complete. */
    private val readComplete: suspend () -> Boolean,
    private val writeComplete: suspend (Boolean) -> Unit,
    private val report: (String) -> Unit = {},
) {

    /**
     * ⚠⚠ **The pass resolves and describes OUTSIDE the emit keys**, so an emit of the same content can
     * land in between. Every emit mints a fresh CEK, so it publishes a new manifest B and describes it,
     * while this pass still holds the refs A it fetched earlier. Its later describe then writes A over B,
     * and B, the LIVE chunk set, is left undescribed — and a sweep of the undescribed remainder would
     * then free live bytes.
     *
     * So the pass stays batched, and **checks afterwards**, without holding any key:
     * - the stamp sequence is read before the walk reads a single holder ([EmitGuard.currentSequence]);
     * - once the batches have landed, each content's [stampOf] is read. At or below that value, and not
     *   busy, proves no emit of that content overlapped the walk or the describe (see `EmitKeyLocks`), so
     *   what was described is what the row and the manifest name;
     * - otherwise the content is re-resolved and re-described, and checked again. The attempts are
     *   bounded, and a content that never settles, or whose refs cannot be re-resolved after a detected
     *   race, leaves the pass **incomplete** (it runs again) rather than latched over a stale description.
     *
     * ⚠ Per **content**, not per `(content, property)`: the manifest is content-addressed, so an emit
     * through any property replaces it for every property that references it.
     * ⚠ No key is held. A multi-key holder deadlocks against an overwrite (which holds the old key while it
     * takes the new), and holding keys across hundreds of manifest `GET`s would stall every emit.
     * ⚠ Only this runtime's emits are visible. Another device's publish of the same derived content id
     * is not serialisable from here, and is the relay-side decision M3 owns
     * (`content-plane-cr2-quorumless.md`).
     */
    interface EmitGuard {
        /**
         * Moves whenever an emit of this content begins or ends, and is null while one is in progress. Stamps
         * come from one monotonic sequence, so a stamp above [currentSequence] read earlier was set since.
         */
        suspend fun stampOf(contentId: String): Long?

        /** The newest stamp handed out so far — read before the walk, it bounds every stamp that predates it. */
        suspend fun currentSequence(): Long

        /**
         * The content [holder] names now, or null when it is retired OR unreadable — the two are not told
         * apart, so a caller must not treat null as proof of either. A moved stamp can also mean an
         * overwrite moved the holder on to other content.
         */
        suspend fun currentContentOf(holder: DataId, propertyId: String): String?
    }

    /**
     * [complete] is the marker's value, not "nothing went wrong": it is true only when the walk
     * finished **and** every batch was accepted **and** every manifest fetch got an answer.
     *
     * ⚠ [withoutRefs] is the interesting number in a live run: content the walk found and could not
     * describe, because its manifest does not exist (usually a thumbnail class never emitted), would
     * not read, or names more refs than one description may carry. Silent would make an unreclaimable
     * population look reclaimed.
     */
    data class Outcome(
        val skipped: String? = null,
        val contentsDescribed: Int = 0,
        val refsDescribed: Int = 0,
        val withoutRefs: Int = 0,
        /** Contents with no manifest on the relay that this device's repair acted on. */
        val handedToRepair: Int = 0,
        /** Rows the walk read and could not ask about. Not described, and not in [withoutRefs]. */
        val rowsSkipped: Int = 0,
        /** Properties whose content id could not be resolved, on rows that were otherwise read. */
        val propertiesUnresolved: Int = 0,
        val complete: Boolean = false,
    )

    /**
     * How much the walk read, and what it could not ask about. On every line that reports a walk,
     * including "nothing to describe": a walk that skipped every row finds nothing either.
     */
    private fun walkSummary(found: DeclarableContent.Result): String {
        val s = found.skipped
        // ⚠ A counted zero is printed. Only a walk that does not count at all says nothing here.
        return (found.rowsRead?.let { ", from $it rows" } ?: ", rows not counted") +
            (if (s.rows > 0) {
                ", ${s.rows} ROWS SKIPPED UNDESCRIBED (${s.undecodable} would not decode, ${s.unkeyed} had no DataId)"
            } else "") +
            (if (s.unresolvedProperties > 0) {
                ", ${s.unresolvedProperties} PROPERTIES SKIPPED UNDESCRIBED (resolving the content id threw)"
            } else "")
    }

    suspend fun run(): Outcome {
        // ⚠⚠ EVERY exit reports, including the ones that did nothing: otherwise "already
        // backfilled", "nothing to describe" and "the op never ran at all" are indistinguishable
        // in a log.
        if (readComplete()) {
            report("ContentDeclarationBackfill -> skipped: already backfilled at this format")
            return Outcome(skipped = "already backfilled")
        }

        // ⚠⚠ A realm that is still arriving cannot be summarised by a walk over what has arrived so
        // far — so this pass does not run at all until the device is settled, the shape
        // `SliceSnapshotWriter` uses. Content with no description is unreachable by the sweep by
        // construction (§9.5), so waiting costs nothing but time.
        if (!isCaughtUp()) {
            report("ContentDeclarationBackfill -> skipped: this device is still catching up")
            return Outcome(skipped = "still catching up")
        }

        // ⚠⚠ Read BEFORE the walk, not after it. The walk reads each holder's content;
        // an overwrite finishing between that read and a later per-content snapshot would already be in the
        // snapshot, and the stale description the pass then sends would pass revalidation. Every stamp at
        // or below this value predates the walk, so any content touched since reads as moved.
        val walkStartedAt = emitGuard.currentSequence()
        val found = declarableContent()
        if (found.entries.isEmpty()) {
            // ⚠ Only a complete walk over an empty realm may latch. An unreadable root walks to
            // zero entries too, and latching there would strand the whole realm. That the realm is
            // *settled* — not merely wiped a moment ago, a transiently empty store — is what the
            // `isCaughtUp` return above buys.
            if (found.walkComplete) writeComplete(true)
            report(
                "ContentDeclarationBackfill -> nothing to describe" +
                    (if (found.walkComplete) " (walk complete, device settled — recorded done)"
                    else " ⚠ WALK INCOMPLETE, so NOT recorded done") +
                    walkSummary(found)
            )
            return Outcome(
                skipped = "nothing to describe",
                rowsSkipped = found.skipped.rows,
                propertiesUnresolved = found.skipped.unresolvedProperties,
                complete = found.walkComplete,
            )
        }

        // ⚠⚠ Said BEFORE the long part, so a pass waiting on one answer for an hour does not look
        // exactly like a pass that is still working.
        report(
            "ContentDeclarationBackfill -> walked: ${found.entries.size} references to " +
                "${found.entries.map { it.contentId }.distinct().size} contents" + walkSummary(found) +
                "; resolving manifests"
        )
        val d = describeEntries(found.entries)
        if (d.stoppedEarly) {
            // Its own line was printed where it stopped. Nothing was described, so there is nothing to
            // revalidate, and the usual summary would read "N without refs" over content it never asked.
            return Outcome(
                rowsSkipped = found.skipped.rows,
                propertiesUnresolved = found.skipped.unresolvedProperties,
                complete = false,
            )
        }
        val revalidated = revalidate(found.entries, walkStartedAt)
        // ⚠ After the describes, never during the scan: a repair uploads, and the scan must not wait on
        // it. Each call is cheap unless it acts — the hook declines every property that is not a file's
        // own bytes, and every file this device holds no bytes for, before any I/O.
        var handedToRepair = 0
        var repairsFailed = 0
        if (repairAbsent != null && d.absent.isNotEmpty()) {
            for (entry in found.entries) {
                if (entry.contentId !in d.absent) continue
                val acted = try {
                    repairAbsent.invoke(entry.holder, entry.propertyId, entry.contentId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // ⚠ A repair that THREW established nothing durable: it can fail before the upload
                    // intent is journalled. The pass must run again, or the marker ends the only
                    // thing that would ever retry it.
                    report("ContentDeclarationBackfill -> repair of ${entry.contentId} failed — $e")
                    repairsFailed++
                    false
                }
                if (acted) handedToRepair++
            }
        }

        val withoutRefs = found.entries.map { it.contentId }.distinct().count { it !in d.refsByContent } + d.oversized
        // ⚠⚠ **Completeness asks "did I see everything?", NOT "was everything describable?"** A
        // thumbnail class that was never emitted has a derivable content id and no manifest,
        // legitimately, and counting those against completeness would make the once-only pass an
        // every-pass one.
        //
        // What does block the marker is *not having seen everything*: a walk that did not finish, a
        // batch the relay refused, or a manifest fetch that got NO ANSWER (the manifest may well
        // exist). ⚠ A manifest the relay HOLDS and this device cannot read is permanent, so it is
        // reported and does not block the marker, or the pass would re-run for ever.
        val complete = found.walkComplete && d.allAccepted && revalidated.allAccepted && d.unanswered == 0 &&
            repairsFailed == 0 && handedToRepair == 0
        if (complete) writeComplete(true)

        report(
            "ContentDeclarationBackfill -> described ${d.described} contents, ${d.refs} refs" +
                walkSummary(found) +
                (if (withoutRefs > 0) ", $withoutRefs without refs (no manifest — usually a thumbnail class that was never emitted — or over the per-description cap)" else "") +
                (if (handedToRepair > 0) ", $handedToRepair with NO MANIFEST ON THE RELAY handed to repair from this device's bytes (the next pass confirms them)" else "") +
                (if (repairsFailed > 0) ", $repairsFailed REPAIRS FAILED (the pass runs again)" else "") +
                (if (d.unanswered > 0) ", ${d.unanswered} MANIFEST FETCHES GOT NO ANSWER (not described; the pass runs again)" else "") +
                (if (d.unreadable > 0) ", ${d.unreadable} MANIFESTS THE RELAY HOLDS COULD NOT BE READ (not described)" else "") +
                (if (!found.walkComplete) ", WALK INCOMPLETE" else "") +
                (if (!d.allAccepted) ", SOME BATCHES REFUSED" else "") +
                (if (revalidated.redescribed > 0) ", ${revalidated.redescribed} re-described after a racing emit" else "") +
                (if (!revalidated.allAccepted) ", A RE-DESCRIBE AFTER A RACING EMIT FAILED" else "") +
                (if (complete) " — recorded complete" else " — NOT recorded complete, will run again")
        )

        return Outcome(
            contentsDescribed = d.described,
            refsDescribed = d.refs,
            withoutRefs = withoutRefs,
            handedToRepair = handedToRepair,
            rowsSkipped = found.skipped.rows,
            propertiesUnresolved = found.skipped.unresolvedProperties,
            complete = complete,
        )
    }

    private class Revalidated(val redescribed: Int, val allAccepted: Boolean)

    /** See [EmitGuard]: re-describe every content an emit overlapped, until its stamp holds still. */
    private suspend fun revalidate(
        entries: List<DeclarableContent.Entry>,
        walkStartedAt: Long,
    ): Revalidated {
        var redescribed = 0
        var allAccepted = true
        // Each content with the stamp read before its refs were last fetched. ⚠ A holder an overwrite moved
        // on joins the queue under its NEW content with no stamp, which forces one re-describe of it: the
        // pass may have written the old refs over the new description.
        // `before` is an upper bound: a stamp at or below it was set before the window being checked. For the
        // first check that is the walk's start; after a re-do it is the exact stamp read before it.
        val queue = ArrayDeque(entries.groupBy { it.contentId }.map { (contentId, group) -> Triple<String, List<DeclarableContent.Entry>, Long?>(contentId, group, walkStartedAt) })
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val (contentId, group, firstStamp) = queue.removeFirst()
            var before = firstStamp
            var attempts = 0
            while (true) {
                val now = emitGuard.stampOf(contentId)
                if (now != null && before != null && now <= before) break
                if (attempts++ == MAX_REVALIDATION_ATTEMPTS) {
                    allAccepted = false
                    report("ContentDeclarationBackfill -> $contentId kept changing under the pass — NOT recorded complete")
                    break
                }
                // ⚠ Busy: an emit is running now. Wait it out rather than read a manifest mid-publish.
                if (now == null) {
                    delay(BUSY_WAIT_MS)
                    continue
                }
                before = now
                val still = mutableListOf<DeclarableContent.Entry>()
                var inconclusive = false
                for (entry in group) {
                    when (val current = emitGuard.currentContentOf(entry.holder, entry.propertyId)) {
                        contentId -> still += entry
                        // ⚠⚠ Null is "retired" OR "unreadable" — the production writer turns a failed read
                        // into null. After a detected race that is not evidence the
                        // description is safe to leave, so the pass stays incomplete. A holder truly retired
                        // is absent from the next pass's walk, so this cannot keep it incomplete for ever.
                        null -> inconclusive = true
                        else -> if (seen.add("${entry.holder}|${entry.propertyId}|$current")) {
                            queue.addLast(Triple(current, listOf(entry.copy(contentId = current)), null))
                        }
                    }
                }
                if (inconclusive) allAccepted = false
                if (still.isEmpty()) break
                val redo = describeEntries(still)
                redescribed += still.size
                // ⚠⚠ An emit overlapped, so a manifest exists: refs that do not resolve now are a transient
                // failure, and leaving it would leave the stale description authoritative.
                if (!redo.allAccepted || contentId !in redo.refsByContent) {
                    allAccepted = false
                    break
                }
                // and loop: the stamp must hold still across THIS re-resolve too.
            }
        }
        return Revalidated(redescribed, allAccepted)
    }

    private class Described(
        val described: Int,
        val refs: Int,
        val oversized: Int,
        /** Manifest fetches with no usable answer: a timeout, or a failure the relay could not be asked about. */
        val unanswered: Int,
        /** Manifests the relay holds that still would not resolve on a second fetch. */
        val unreadable: Int,
        val allAccepted: Boolean,
        val refsByContent: Map<String, List<String>>,
        /** The fetch loop gave up on an unreachable relay; nothing was declared. */
        val stoppedEarly: Boolean = false,
        /** Contents the relay answered 404 for: no manifest there. */
        val absent: Set<String> = emptySet(),
    )

    /** Wraps a fetch's answer, so a null answer ("no manifest") is not confused with a timeout. */
    private class Fetched(val refs: List<String>?)

    /** The same for the presence probe, whose own answer may be null ("could not ask"). */
    private class Probed(val present: Boolean?)

    /** What one content's manifest resolution came to: four outcomes, as data. */
    private class Resolution(val kind: Kind, val refs: List<String>? = null) {
        enum class Kind { RESOLVED, UNANSWERED, ABSENT, UNREADABLE }
    }

    /**
     * One content's manifest: fetch, and on a null ask the relay which of four facts it was.
     *
     * ⚠⚠ Bounded. One manifest fetch that never answers would otherwise suspend the whole pass for
     * ever, with no failure and no line. The pass moves on, and the timeout keeps it from recording
     * itself complete.
     */
    private suspend fun resolve(contentId: String): Resolution {
        val fetched = withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) { Fetched(chunkRefsOf(contentId)) }
            ?: return Resolution(Resolution.Kind.UNANSWERED)
        fetched.refs?.let { return Resolution(Resolution.Kind.RESOLVED, it) }
        // ⚠⚠ A null from the fetch is four different facts. Ask the relay which one it is.
        // (A probe that times out is a probe that could not ask.)
        return when (withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) { Probed(manifestPresent(contentId)) }?.present) {
            // The relay's 404: a content that never existed, or an upload whose manifest never
            // landed. The legitimate "without refs"; [repairAbsent] tells them apart.
            false -> Resolution(Resolution.Kind.ABSENT)
            // Could not ask either: the first failure may have been the transport's.
            null -> Resolution(Resolution.Kind.UNANSWERED)
            // The relay holds it. Either the first fetch failed in transit, or it will not parse: one
            // more fetch tells them apart. ⚠ A second fetch that TIMES OUT is not an unreadable
            // manifest: only a fetch that completed and returned nothing is. Unreadable does not
            // block the marker, so the two must not be folded together.
            true -> {
                val again = withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) { Fetched(chunkRefsOf(contentId)) }
                when {
                    again == null -> Resolution(Resolution.Kind.UNANSWERED)
                    again.refs == null -> Resolution(Resolution.Kind.UNREADABLE)
                    else -> Resolution(Resolution.Kind.RESOLVED, again.refs)
                }
            }
        }
    }

    /** Resolve refs, build the descriptions, and send them in ref-bounded batches. */
    private suspend fun describeEntries(entries: List<DeclarableContent.Entry>): Described {
        // ⚠ Resolved once per contentId, not once per entry: a copy or a trash/restore legitimately
        // repeats one, and refetching a manifest per holder would multiply the pass's only expensive
        // step by the sharing factor.
        val refsByContent = mutableMapOf<String, List<String>>()
        var unresolved = 0
        var unanswered = 0
        var unreadable = 0
        var unansweredInARow = 0
        val unansweredIds = mutableListOf<String>()
        val absent = mutableSetOf<String>()
        val contentIds = entries.map { it.contentId }.distinct()
        // ⚠ Resolved [FETCH_CONCURRENCY] at a time: fetched strictly one at a time, one manifest per
        // round trip, a 30-minute job would cover at most ~21,000 manifests. The results are taken IN ORDER, so the counters
        // and the "unanswered in a row" stop read exactly as a serial loop's would; a stop wastes at
        // most the rest of one window.
        var resolvedUpTo = 0
        var window: List<Resolution> = emptyList()
        for ((index, contentId) in contentIds.withIndex()) {
            if (index >= resolvedUpTo) {
                val batch = contentIds.subList(index, minOf(index + FETCH_CONCURRENCY, contentIds.size))
                window = coroutineScope { batch.map { id -> async { resolve(id) } }.awaitAll() }
                resolvedUpTo = index + batch.size
            }
            val resolution = window[index - (resolvedUpTo - window.size)]
            val unansweredBefore = unanswered
            val chunks = resolution.refs
            when (resolution.kind) {
                Resolution.Kind.RESOLVED -> Unit
                Resolution.Kind.UNANSWERED -> unanswered++
                Resolution.Kind.ABSENT -> absent += contentId
                Resolution.Kind.UNREADABLE -> unreadable++
            }
            if (chunks == null) unresolved++ else refsByContent[contentId] = chunks + manifestRefOf(contentId)
            // ⚠⚠ Stop when the relay has plainly gone, rather than run through every manifest against
            // a network the OS has cut and then send describes that cannot land either. A run of
            // unanswered fetches is not 404s and not slowness; nothing after it can succeed, and every
            // description sent from here on would go out without its refs.
            if (unanswered > unansweredBefore) {
                unansweredInARow++
                unansweredIds += contentId
            } else {
                unansweredInARow = 0
            }
            if (unansweredInARow >= MAX_UNANSWERED_IN_A_ROW) {
                report(
                    "ContentDeclarationBackfill -> STOPPED after ${index + 1} of ${contentIds.size} manifests: " +
                        "the last $unansweredInARow fetches got no answer (relay unreachable, or this " +
                        "process has no network); nothing described"
                )
                return Described(
                    described = 0, refs = 0, oversized = 0,
                    unanswered = unanswered, unreadable = unreadable,
                    allAccepted = false, refsByContent = refsByContent, stoppedEarly = true,
                )
            }
            val done = index + 1
            if (done % PROGRESS_EVERY == 0 && done < contentIds.size) {
                report(
                    "ContentDeclarationBackfill -> resolved $done of ${contentIds.size} manifests" +
                        " ($unresolved without one" + (if (unanswered > 0) ", $unanswered GOT NO ANSWER" else "") + ")"
                )
            }
        }

        // ⚠⚠ One more attempt at what got no answer, before it is allowed to block the marker. An
        // unanswered fetch keeps the pass incomplete, and the pass then fetches EVERYTHING again. On a
        // phone that is a few transient failures (momentary name-resolution failures) per run, every
        // run, at different positions each time; minutes later the same fetch simply works.
        // ⚠ Once, not until it works: a relay that is really failing must still leave the pass incomplete.
        if (unansweredIds.isNotEmpty()) {
            var recovered = 0
            var notOnRelay = 0
            var stillInARow = 0
            for ((index, contentId) in unansweredIds.withIndex()) {
                val fetched = withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) { Fetched(chunkRefsOf(contentId)) }
                val chunks = fetched?.refs
                if (chunks != null) {
                    refsByContent[contentId] = chunks + manifestRefOf(contentId)
                    unresolved--
                    unanswered--
                    recovered++
                    stillInARow = 0
                } else if (fetched == null) {
                    stillInARow++
                } else {
                    // The same three-way question as the scan: the relay may answer now.
                    when (withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) { Probed(manifestPresent(contentId)) }?.present) {
                        // It says 404: it was a content that never existed.
                        false -> {
                            unanswered--
                            absent += contentId
                            notOnRelay++
                            stillInARow = 0
                        }
                        // It holds the manifest, so the relay is answering. One more fetch; if that is
                        // still null the manifest will not parse, which is permanent and must not block.
                        true -> {
                            val again = withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS) { Fetched(chunkRefsOf(contentId)) }
                            val refs = again?.refs
                            when {
                                // Timed out again: still unanswered, and still blocking.
                                again == null -> stillInARow++
                                refs != null -> {
                                    refsByContent[contentId] = refs + manifestRefOf(contentId)
                                    unresolved--
                                    unanswered--
                                    recovered++
                                    stillInARow = 0
                                }
                                else -> {
                                    unanswered--
                                    unreadable++
                                    stillInARow = 0
                                }
                            }
                        }
                        null -> stillInARow++
                    }
                }
                // ⚠⚠ The same cutoff as the first scan. The failures collected
                // above need not have been consecutive, so there can be thousands of them, and if the
                // network went before this point each retry costs up to two bounded waits: hours of
                // holding the liveness key and keeping the enumeration sweep waiting.
                if (stillInARow >= MAX_UNANSWERED_IN_A_ROW) {
                    report(
                        "ContentDeclarationBackfill -> STOPPED while retrying (${index + 1} of " +
                            "${unansweredIds.size}): the last $stillInARow got no answer again (relay " +
                            "unreachable, or this process has no network); nothing described"
                    )
                    return Described(
                    described = 0, refs = 0, oversized = 0,
                    unanswered = unanswered, unreadable = unreadable,
                    allAccepted = false, refsByContent = refsByContent, stoppedEarly = true,
                )
                }
            }
            report(
                "ContentDeclarationBackfill -> retried ${unansweredIds.size} unanswered manifests: " +
                    "$recovered resolved, $notOnRelay not on the relay, $unanswered still unanswered"
            )
        }

        val oversizedContents = mutableSetOf<String>()
        // ⚠ One description per content: two rows naming one content describe the same refs.
        val descriptions = LinkedHashMap<String, ContentDescription>()
        for (entry in entries) {
            val entryRefs = refsByContent[entry.contentId] ?: continue
            // ⚠⚠ A description is only legal with a NON-EMPTY ref set within the per-description cap —
            // the relay 400s both, and the 400 fails the WHOLE batch, so the marker would never latch:
            // "a backfill that runs for ever and finishes nothing". Oversized content is left
            // undescribed and reported; it simply can never be freed.
            if (entryRefs.size > MAX_REFS_PER_DESCRIPTION) {
                oversizedContents += entry.contentId
                continue
            }
            descriptions.getOrPut(entry.contentId) {
                ContentDescription(contentId = entry.contentId, refs = entryRefs)
            }
        }

        var described = 0
        var refs = 0
        var allAccepted = true
        // ⚠⚠ Batched by REF COUNT, not by description count, and getting this wrong stalls the pass
        // silently: the relay caps a request at MAX_TOTAL_REFS, and a batch over it would 400 on every
        // pass, so the marker would never latch.
        val batches = descriptions.values.toList().batchedByRefs()
        for ((index, batch) in batches.withIndex()) {
            // ⚠ Bounded for the same reason as the fetch. A describe that does not answer is a refused
            // batch: nothing is counted, and the pass runs again.
            val accepted = withTimeoutOrNull(DESCRIBE_TIMEOUT_MS) { describe(batch) }
            if (batches.size > 1) {
                report(
                    "ContentDeclarationBackfill -> described batch ${index + 1} of ${batches.size}" +
                        when (accepted) {
                            true -> ""
                            false -> " — REFUSED"
                            null -> " — NO ANSWER within ${DESCRIBE_TIMEOUT_MS / 1000} s"
                        }
                )
            }
            if (accepted == true) {
                // ⚠ Counted on ACCEPTANCE: a number that means "intended" where the others mean
                // "landed" is worse than no number, since the report is this pass's only signal.
                described += batch.size
                refs += batch.sumOf { it.refs.size }
            } else {
                // ⚠ Kept going rather than aborting. Each batch is independent, and a batch that
                // did not land leaves its content undescribed — which is where it already was.
                allAccepted = false
            }
        }

        return Described(
            described = described,
            refs = refs,
            oversized = oversizedContents.size,
            unanswered = unanswered,
            unreadable = unreadable,
            allAccepted = allAccepted,
            refsByContent = refsByContent,
            absent = absent,
        )
    }

    /**
     * Greedy batches that respect **both** relay ceilings — at most [MAX_DESCRIPTIONS_PER_REQUEST]
     * descriptions and at most [MAX_TOTAL_REFS] refs. ⚠ A description always lands in some batch, even
     * alone: the per-description cap already guarantees none exceeds the request ceiling.
     */
    private fun List<ContentDescription>.batchedByRefs(): List<List<ContentDescription>> {
        val batches = mutableListOf<List<ContentDescription>>()
        var current = mutableListOf<ContentDescription>()
        var currentRefs = 0
        for (description in this) {
            val cost = description.refs.size
            val wouldExceed = current.isNotEmpty() &&
                (current.size >= MAX_DESCRIPTIONS_PER_REQUEST || currentRefs + cost > MAX_TOTAL_REFS)
            if (wouldExceed) {
                batches += current
                current = mutableListOf()
                currentRefs = 0
            }
            current += description
            currentRefs += cost
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    companion object {
        /** Mirrors the relay's `MAX_DESCRIBE_CONTENTS`. */
        const val MAX_DESCRIPTIONS_PER_REQUEST = 512

        /** Mirrors the relay's `MAX_TOTAL_REFS_PER_REQUEST`. */
        const val MAX_TOTAL_REFS = 8_192

        /** Mirrors the relay's `MAX_REFS_PER_EDGE`, the cap one description's ref set must fit. */
        const val MAX_REFS_PER_DESCRIPTION = 4_096

        /** Re-describes of one content before the pass gives up on it and stays incomplete. */
        const val MAX_REVALIDATION_ATTEMPTS = 5

        /** How long to wait out an emit in progress before looking at its stamp again. */
        const val BUSY_WAIT_MS = 200L

        /**
         * How long one manifest fetch may take. Above the client's 30 s socket timeout, so this fires
         * only when that one did not: a request the transport itself lost track of.
         */
        const val MANIFEST_FETCH_TIMEOUT_MS = 60_000L

        /** One describe request. The relay has been measured at about 8 s for 512 descriptions. */
        const val DESCRIBE_TIMEOUT_MS = 180_000L

        /**
         * Unanswered fetches in a row after which the pass gives up. Small: with the network cut each
         * one fails in milliseconds, and when it hangs instead each costs up to two bounded waits.
         */
        const val MAX_UNANSWERED_IN_A_ROW = 10

        /** Manifests resolved at once: the content plane's own transfer width. */
        const val FETCH_CONCURRENCY = 4

        /** A progress line every this many manifests, so a long pass is visibly alive or visibly stuck. */
        const val PROGRESS_EVERY = 500
    }
}
