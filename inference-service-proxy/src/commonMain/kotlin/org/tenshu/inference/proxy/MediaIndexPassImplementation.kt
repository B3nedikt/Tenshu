package org.tenshu.inference.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.inference.InferenceCapability
import org.tenshu.inference.proxy.vocabulary.firingRates
import org.tenshu.inference.proxy.vocabulary.report
import org.tenshu.sync.ScheduledRunDeferred

/**
 * Where a pass finds the user's images.
 *
 * ⚠⚠ **A seam, not a file walk, and deliberately so.** Enumerating the library is a *file-service*
 * concern; `:inference-service-proxy` depends on no other service, and no service-proxy in this
 * repo does. Composition across services is Control's proxy job — the same place the backends and
 * the model-delivery components are assembled — so the walk lives in `:file-service-proxy` and the
 * wiring lives in `:control-shared-proxy`. Taking a `:file-service` dependency here would have been
 * the first edge of its kind for a shortcut worth nothing.
 *
 * ⚠ Returning [IndexableMedia] rather than paths keeps the byte read lazy: see below.
 */
fun interface MediaSource {
    suspend fun media(executionContext: ExecutionContext): List<IndexableMedia>

    /**
     * [media], plus whether it is **the whole library** — the only list a pass may reconcile its
     * stores against.
     *
     * ⚠⚠ **Defaults to NOT complete, and that default is the protection**. The
     * file walk is lenient: a subtree it cannot read is skipped so one bad row never stops a pass.
     * A reconcile reading such a list as "everything the user has" would erase the face templates
     * and vectors of photographs that still exist. So a source that cannot vouch for its list never
     * triggers deletion by absence; one built on `walkImageLibrary` overrides this and answers
     * [MediaWalk.complete] when that walk says so — Control's `controlMediaSource`.
     */
    suspend fun walk(executionContext: ExecutionContext): MediaWalk =
        MediaWalk.partial(media(executionContext))
}

/**
 * What a [MediaSource] walk found, and — only when it is the whole library — the right to reconcile
 * against it ([MediaSource.walk]).
 *
 * ⚠⚠ **Completeness is a typed property, never a flag a caller can set alone** (hard constraint 2).
 * The only way to build a complete walk is [complete], and it cannot be called without the point
 * read the reconcile needs as its second proof. A source that has no such read cannot claim a whole
 * library, and a pass handed a [partial] walk has nothing to reconcile with.
 */
class MediaWalk private constructor(val media: List<IndexableMedia>, val wholeLibrary: WholeLibrary?) {

    companion object {
        /** A list that is not, or cannot be shown to be, the whole library. Reconciles nothing. */
        fun partial(media: List<IndexableMedia>): MediaWalk = MediaWalk(media, null)

        /**
         * Every live, untrashed image, from a walk that read the whole tree —
         * `walkImageLibrary().complete` — with [stillLive] to confirm each absence.
         */
        fun complete(media: List<IndexableMedia>, stillLive: LivePaths): MediaWalk =
            MediaWalk(media, WholeLibrary(stillLive))
    }
}

/**
 * The claim "this list is the whole library", and what makes it safe to act on.
 *
 * @property stillLive which of the paths it is asked about still name a live, untrashed file —
 *   `livePathsAmong` in `:file-service-proxy`. ⚠⚠ The reconcile asks it about every path the walk
 *   did not return before dropping any, because a walk is not a snapshot: see `MediaIndexer`'s
 *   reconcile for the move window this closes.
 */
class WholeLibrary(val stillLive: LivePaths)

/**
 * [org.tenshu.inference.MediaIndexPass] — hand every image to [MediaIndexer] and report what the
 * pass did (IN-D6).
 *
 * ⚠⚠ **The pass is the source plus the indexer, and nothing else.** Every decision about *whether*
 * to look at a file — consent, staleness, a missing backend — already lives in [MediaIndexer] and is
 * re-read per file there. Duplicating any of it here would give two places that can disagree about
 * whether a photo was indexed.
 */
internal class MediaIndexPassImplementation(
    private val indexer: MediaIndexer,
    private val source: MediaSource,
    /**
     * ⚠ No `capability` parameter. It would be **silently dropped** whenever [runner] is present —
     * the runner owns which half a pass tags under, because a pass that joins another cannot have
     * two answers — and a parameter that is honoured on one path and ignored on the other is worse
     * than none. The fallback below therefore uses the same default the runner does.
     *
     * §34. Present in production: the pass then JOINS one already running — manual or scheduled —
     * rather than starting a second walk over the same library.
     *
     * ⚠ It still **waits**: an OS-woken job holds its wakelock until this op returns, so returning
     * early would let Android stop the work mid-library.
     */
    private val runner: MediaIndexRunner? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val result = runner?.runToCompletion(executionContext)
            ?: source.walk(executionContext).let { walk ->
                indexer.index(
                    executionContext,
                    walk.media,
                    InferenceCapability.CONTENT_TAGGING,
                    // ⚠ Only a walk the source vouches for as complete may be reconciled against.
                    wholeLibrary = walk.wholeLibrary,
                )
            }

        // ⚠ The summary line is printed by `MediaIndexRunner` for EVERY pass, manual included; this
        // path prints only when there is no runner, so a scheduled pass is not reported twice.
        if (runner == null) println(result.summaryLine())

        // §7.7's "true and useless" check. ⚠⚠ Printed SEPARATELY from the counts above because it
        // is a different kind of fact: those describe the pass, this describes the *vocabulary*.
        // `indoors` reached the top 3 for 55–60% of a real library — invisible per image, and only
        // ever visible here.
        //
        // ⚠ A rate, not a verdict: §7.7's three responses (drop it, raise its threshold, demote it
        // to a filter never surfaced as a tag) are a per-label judgement that needs this data
        // first, and emitting a "too common" flag would invent the policy ahead of the evidence.
        if (result.labelFiringCounts.isNotEmpty()) {
            println(
                "MediaIndexPass -> label firing rates: " +
                    firingRates(result.labelFiringCounts, result.consideredImages).report()
            )
        }

        // `media-processing-pipeline-sweep.md` F3.1: a pass the device's state stood down — the
        // screen on at Control's startup, or unplugged mid-library — asks for another look in ~15
        // min rather than after the 12 h period. ⚠⚠ Only `deferred`: a pause is the user's "stop",
        // and no consent / no backend are counted no-ops a retry cannot change; those return
        // normally so the period stands. ⚠ After the report lines above, so a deferred pass is
        // still logged.
        if (result.deferred && !result.paused) {
            throw ScheduledRunDeferred(MEDIA_INDEX_RETRY_MS, "MediaIndexPass deferred: device not idle and charging")
        }

        return null as T
    }
}

/**
 * The one-line pass report (`MediaIndexPass -> …`) — a pass is invisible by construction, so
 * "indexed 0 of 4 000" with no reason is the report that gets filed as "the feature does nothing".
 *
 * ⚠ The face half reports its own counters: without them a face half that saw 55 photos looks
 * exactly like a finished pass.
 *
 * ⚠ `embedFailed` is photos, never a verdict: "the model is failing" is `embedStopped=modelFailing`,
 * and `embedNotAttempted` is what that cost.
 */
internal fun IndexPassResult.summaryLine(): String =
    "MediaIndexPass -> visited=$imagesVisited labelled=$labelled " +
        "current=$alreadyCurrent notOnDevice=$notOnDevice fromPreview=$fromPreview " +
        "noConsent=$skippedNoConsent noBackend=$skippedNoBackend " +
        "embedded=$embedded rejected=$embeddingsRejected " +
        "embedFailed=$embeddingsFailed embedNotAttempted=$embeddingsNotAttempted " +
        "embedStopped=${if (embeddingStoodDown) "modelFailing" else "no"} paused=$paused deferred=$deferred " +
        "facePhotos=$photosSearchedForFaces facesFound=$facesFound facesEmbedded=$facesEmbedded facesNotHuman=$facesNotHuman"
