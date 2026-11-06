package org.tenshu.sdk.ios

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.ReapRemoval

/**
 * The iOS route-1 content-plane store (ios-drive-parity.md P3b). Decorates the foreground
 * [org.tenshu.sync.proxy.RestContentBlobStore]:
 *
 *  - `putChunk` means not "PUT now" but "stage + enqueue on the background `URLSession`"
 *    (via [BackgroundUploadManager]) so a bulk upload survives the app being suspended or
 *    terminated (O3-B).
 *  - `getChunk` (the on-demand read path) delegates unchanged to [foreground] — downloads are
 *    interactive and don't want a background transfer.
 *
 * `SyncObserver.emitFileContent` is untouched: it still calls `putChunk(ref, ciphertext)` per
 * chunk and once for the manifest blob; the whole background-transport restructure lives behind
 * this one seam. Drive-only — Control-on-iOS keeps the plain foreground store (on iOS route-1
 * Drive is the background syncer).
 */
internal class BackgroundUrlSessionContentBlobStore(
    private val foreground: ContentBlobStore,
    private val manager: BackgroundUploadManager,
) : ContentBlobStore {

    override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> =
        manager.stageAndEnqueue(ref, ciphertext)

    override suspend fun getChunk(ref: String): TResult<TByteArray> =
        foreground.getChunk(ref)

    /**
     * ⚠ A staged background upload counts as present. `putChunk` returns once a blob is staged and
     * journalled, long before the relay has it, so asking the relay alone would report every upload
     * still in `nsurlsessiond` as missing and re-stage the whole file. [BackgroundUploadManager]'s
     * journal owns that blob's completion.
     */
    override suspend fun hasChunk(ref: String): Boolean? =
        if (manager.isPending(ref)) true else foreground.hasChunk(ref)

    // ⚠⚠ EVERY OTHER MEMBER MUST DELEGATE. This class implements `ContentBlobStore` explicitly
    // rather than `by foreground`, so anything it does not override falls to the PORT'S DEFAULT —
    // and the declaration defaults are `= false` / a forward that drops its argument, not a
    // delegation. On iOS Drive (route-1, the background syncer) a missing member means every
    // upload describes no handle: a live row with no `content_reference`, which is exactly the
    // population §6e frees. It fails silently because a default that returns `false` looks like a
    // relay that refused, and `SyncObserver` only prints a line for it.
    //
    // ⚠ `by foreground` would prevent the whole class of bug; it is not used because two of the
    // overrides above need `manager`, and switching would change the other members' behaviour in
    // one edit. Delegating explicitly is the smaller, checkable change.

    // ⚠ §10.12 Q3 owes this a wait for the background upload's COMPLETION, not for `putChunk`'s
    // hand-off to `nsurlsessiond`.
    override suspend fun describeContent(contents: List<ContentDescription>): Boolean =
        foreground.describeContent(contents)

    // CR-2 B4: the typed refusal must survive the delegation, or a freed describe reads as a failure.
    override suspend fun describe(contents: List<ContentDescription>): org.tenshu.sync.common.DescribeOutcome =
        foreground.describe(contents)

    override suspend fun reapRemovals(removals: List<ReapRemoval>): Boolean =
        foreground.reapRemovals(removals)
}
