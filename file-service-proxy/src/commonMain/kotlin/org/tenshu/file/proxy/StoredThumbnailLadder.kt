package org.tenshu.file.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.relation.Property

/**
 * Which stored still class answers a preview request — the one ladder every `GetPreview` actual
 * serves from (`drive-synced-thumbnails.md` §10.2, §12.5 D-2, §14.2).
 *
 * ⚠⚠ Shared, and that is the point (`drive-xxlarge-thumbnail-rung.md` X-0). Android, the JVM and iOS
 * each carried a private, character-for-character identical copy of both rules; a new rung would have
 * had to land three times, and a copy that missed it would have served the next-smaller class
 * without a sound. Both rules read [ThumbnailClasses.STILLS], smallest edge first.
 *
 * [resolve] reads one class **locally** and answers `null` when it is not on this device — never a
 * fetch (§14.1). Both rules give the answers the copies gave; `best` reads fewer classes to get there.
 */
internal object StoredThumbnailLadder {

    /**
     * The best stored class for [requestedMaxEdge] when nothing better is possible — the fallback
     * after a render from the original was not an option.
     *
     * Largest first, each only when the request is bigger than the rung below it; then the smallest;
     * then the largest that exists at all.
     *
     * ⚠⚠ **Resolved lazily, each class at most once.** This serves every ≤ 100 px Files row, and the
     * copies it replaced read every class up front — so a row whose `LIST` was local still read the
     * file's `XLARGE` blob (up to 512 KB) and threw it away, and a larger rung would have multiplied
     * that per row (`drive-xxlarge-thumbnail-rung.md` §9.1). A row that has its `LIST` reads only it.
     */
    fun best(
        requestedMaxEdge: Int,
        stills: List<ThumbnailClass.Still> = ThumbnailClasses.STILLS,
        resolve: (Property.Dynamic<TByteArray>) -> TByteArray?,
    ): TByteArray? {
        val tried = BooleanArray(stills.size)
        fun read(i: Int): TByteArray? {
            tried[i] = true
            return resolve(stills[i].property)
        }
        for (i in stills.indices.reversed()) {
            if (i == 0) break
            if (requestedMaxEdge > stills[i - 1].px) read(i)?.let { return it }
        }
        read(0)?.let { return it }
        for (i in stills.indices.reversed()) {
            if (i == 0) break
            if (!tried[i]) read(i)?.let { return it }
        }
        return null
    }

    /**
     * The stored class to serve **instead of reading the whole file**, or `null` when none is good
     * enough (§12.5 D-2) — for a request above the smallest rung.
     *
     * First the smallest local rung that covers the request, falling back to larger ones: too big
     * still beats reading the whole original. Then, when none covers it, the largest local rung that
     * [ThumbnailClass.Still.stretches] and is within [GetStoredThumbnail.COVER_TOLERANCE] of it
     * (`drive-xxlarge-thumbnail-rung.md` §4.2, D-14) — so a device holding only `XLARGE` still
     * answers a request between it and the rung above. Resolved lazily, each class at most once.
     *
     * ⚠⚠ Never a stretched rung that does not [ThumbnailClass.Still.stretches]: the tolerance buys off
     * a whole-file read, it does not license a stretched small class (§14.2). ⚠ The smallest rung
     * (`LIST`) is outside this rule — its requests take [best].
     */
    fun covering(
        requestedMaxEdge: Int,
        stills: List<ThumbnailClass.Still> = ThumbnailClasses.STILLS,
        /**
         * ⚠⚠ `false` is D-17's byte holder (`GetPreview.Params.PREFER_ORIGINAL`): serve a rung only
         * when one **covers** the request, because this device can render the real thing at exactly
         * the size asked for. A covering rung is still a downscale of the same picture and far
         * cheaper than reading a whole file, so it keeps winning — only the stretch is refused.
         */
        allowStretch: Boolean = true,
        resolve: (Property.Dynamic<TByteArray>) -> TByteArray?,
    ): TByteArray? {
        val candidates = stills.drop(1)
        candidates.filter { it.px >= requestedMaxEdge }
            .firstNotNullOfOrNull { resolve(it.property) }
            ?.let { return it }
        if (!allowStretch) return null
        return candidates.asReversed()
            .filter {
                it.stretches &&
                    it.px < requestedMaxEdge &&
                    requestedMaxEdge <= (it.px * GetStoredThumbnail.COVER_TOLERANCE).toInt()
            }
            .firstNotNullOfOrNull { resolve(it.property) }
    }
}
