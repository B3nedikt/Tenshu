package org.tenshu.sync.common

import org.tenshu.core.TError

/**
 * The relay's typed `409` on `POST /sync/push` and `POST /sync/content/declare` (CR-2 B4,
 * `content-plane-cr2-b4-spec.md` §0): the request named content the relay has **freed**, or a revive
 * whose refs did not verify.
 *
 * ⚠⚠ Parsed from the JSON body `{"error": "...", "contentIds": [...]}`, never from a failure message:
 * `statusWithReason()` caps the body at 200 characters, and a refusal listing a few ids would arrive
 * truncated, which the caller would then act on as if it were the whole list.
 *
 * ⚠ [contentIds] lists every offending id of the request, deduplicated. Order carries no meaning.
 */
sealed interface ContentRefusal {
    val contentIds: List<String>

    /** `content_freed`: these ids are FREED. A push gets out with a revive; a describe never does. */
    data class ContentFreed(override val contentIds: List<String>) : ContentRefusal

    /** `revive_incomplete`: a revive named a ref the relay does not hold, or still holds queued for freeing. */
    data class ReviveIncomplete(override val contentIds: List<String>) : ContentRefusal

    companion object {
        const val CONTENT_FREED = "content_freed"
        const val REVIVE_INCOMPLETE = "revive_incomplete"

        /** The refusal an `error` token names, or null for a 409 that is not one of B4's. */
        fun of(error: String?, contentIds: List<String>): ContentRefusal? = when (error) {
            CONTENT_FREED -> ContentFreed(contentIds.distinct())
            REVIVE_INCOMPLETE -> ReviveIncomplete(contentIds.distinct())
            else -> null
        }
    }
}

/**
 * One freed content put back on the relay by this push (`EdgedPush.revive`, B4 §1.3): every ref the
 * re-upload produced, the chunks **and** the manifest at its derived ref.
 *
 * ⚠ A plain value rather than `ReviveEntry` because the port has no `TContext`; `RestSyncRelay` builds
 * the wire composite.
 */
data class RevivedContent(val contentId: String, val refs: List<String>)

/** What [SyncRelay.pushEdged] got back. */
sealed interface PushOutcome {
    /** 2xx: the block and its edges (and any revive) landed in one relay transaction. */
    data object Accepted : PushOutcome

    /** 409 with one of B4's bodies: nothing was written. */
    data class Refused(val refusal: ContentRefusal) : PushOutcome

    /** Anything else: transport, 401, 402, 426, a 409 without a B4 body. The block stays queued. */
    data class Failed(val error: TError) : PushOutcome
}

/** What [ContentBlobStore.describe] got back. */
sealed interface DescribeOutcome {
    /** Every description landed. */
    data object Described : DescribeOutcome

    /**
     * The relay refused [freed] as FREED (B4 §1.4). ⚠ Every **other** description was still sent and
     * accepted: a refused request writes nothing, so the store resends it without the freed ids.
     */
    data class Refused(val freed: List<String>) : DescribeOutcome

    /** Not described (transport or any other refusal). The caller's existing retry applies. */
    data object Failed : DescribeOutcome
}

/**
 * Carries a [ContentRefusal] out of the `PushBlock` operation to the drain that resolves it (B4 §2.2).
 *
 * ⚠ Not a `TenshuException`: the refusal is a Kotlin value, not a wire error, and the op is only ever
 * run in-process by the scheduler's drains. ⚠ Its only constructor takes the refusal, so coroutine
 * stack-trace recovery (which copies exceptions through a `(String)`/`(Throwable)` constructor) cannot
 * replace it with a copy that has lost the ids.
 */
class ContentRefusedException(val refusal: ContentRefusal) :
    RuntimeException("relay refused the push: $refusal")
