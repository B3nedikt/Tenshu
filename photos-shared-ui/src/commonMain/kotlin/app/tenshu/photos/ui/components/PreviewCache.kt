package app.tenshu.photos.ui.components

import app.tenshu.photos.data.PhotoPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The grid's memory of bytes it has already fetched (`photos-app.md` PH-G9).
 *
 * ⚠⚠ **A tile that scrolls away and comes back used to re-read its bytes.** `PhotoTile` keys
 * `produceState` on the path, which is right and must stay — it is what stops the view model
 * holding a decoded bitmap per row, which on a real library is hundreds of megabytes. But nothing
 * between the tile and the chain remembered anything, so the *request* was repeated, and a request
 * is a `navigateTo` + `search` + an eager `File.Preview` over IPC. Measured on a scroll down and
 * back up: **17 loads for 10 distinct photographs.**
 *
 * ⚠⚠ It caches the **encoded bytes, never the decoded bitmap.** A 256 px bitmap is 256 KB decoded
 * and a few kilobytes encoded — two orders of magnitude — so this holds what is cheap to hold and
 * lets `PhotoTile` keep owning the expensive half, per its own KDoc.
 *
 * ⚠ Bounded in bytes rather than entries, because a thumbnail's size is not a constant: the ladder
 * mints WebP on Android and PNG elsewhere, and a lossless 256 px PNG is an order of magnitude
 * larger than its WebP twin. An entry cap tuned against one would be wrong on the other.
 */
class PreviewCache(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
) {
    private val lock = Mutex()

    /**
     * ⚠ Insertion-ordered and re-inserted on read, which is what makes it an LRU: Kotlin's common
     * `LinkedHashMap` has no access-order mode, so a hit moves its own key to the back by hand.
     */
    private val entries = LinkedHashMap<String, ByteArray>()
    private var heldBytes = 0

    /**
     * ⚠⚠ The flood half of PH-G9. A fling composes and disposes tiles faster than the proxy can
     * answer, and without a ceiling every one of them is a chain read in flight at once. The permit
     * is taken around the fetch only — a cache hit never queues behind a miss.
     */
    private val inFlight = Semaphore(maxInFlight)

    /**
     * The bytes for [key], fetching them at most once. ⚠ A key, not a path: a photograph is fetched
     * at more than one size (`previewCacheKey`), and each size is its own entry.
     *
     * ⚠⚠ **A `null` is not remembered.** On a byte-less device `null` means "the thumbnail has not
     * arrived yet", which is a wait and not an answer — caching it would freeze a tile as a
     * placeholder for as long as the screen is open, and the content plane delivering the blob a
     * second later would change nothing. The same rule the count memo needed
     * (`core-keyset-paging.md` §13).
     *
     * ⚠⚠ **Nor is a [PhotoPreview.standIn]**, for the same reason one size up: it is served, so the
     * tile draws the best there is, but the hero class it stands in for may be minted or fetched a
     * moment later, and a kept stand-in would hold that tile blurry for the rest of the session.
     */
    suspend fun bytes(key: String, fetch: suspend (String) -> PhotoPreview?): ByteArray? {
        hit(key)?.let { return it }
        val fetched = inFlight.withPermit { fetch(key) } ?: return null
        // ⚠⚠ Empty bytes are no answer (`photos-app.md` §36): a transfer that delivered nothing,
        // cached, left a face blank until Photos restarted. Returned as `null`, never stored.
        if (fetched.bytes.isEmpty()) return null
        if (!fetched.standIn) store(key, fetched.bytes)
        return fetched.bytes
    }

    private suspend fun hit(key: String): ByteArray? = lock.withLock {
        entries.remove(key)?.also { entries[key] = it }
    }

    private suspend fun store(key: String, bytes: ByteArray) = lock.withLock {
        // ⚠ A single payload larger than the whole budget is served but not held: keeping it would
        // evict everything else to make room for one tile.
        if (bytes.size > maxBytes) return@withLock
        entries.remove(key)?.let { heldBytes -= it.size }
        entries[key] = bytes
        heldBytes += bytes.size
        while (heldBytes > maxBytes) {
            val oldest = entries.keys.firstOrNull() ?: break
            heldBytes -= (entries.remove(oldest)?.size ?: 0)
        }
    }

    /** For tests and for a screen that wants to prove it starts cold. */
    internal suspend fun heldBytesForTest(): Int = lock.withLock { heldBytes }

    companion object {
        /**
         * ⚠ 8 MB, which is hundreds of WebP thumbnails and a few dozen lossless PNG ones — and ~60
         * hero-rung JPEGs (~139 KB measured, 512 KB cap), which is what a tile the `LARGE` rung
         * cannot fill asks for (`tilePreviewRequestPx`): four or so screens of a 3-across grid.
         * Chosen to be small next to the ~10 MB the shared content index already costs
         * (`photos-feed-scrubber.md` §6) rather than from a measurement of hit rates — it is a
         * budget, and the honest thing is to say it was picked, not derived.
         */
        const val DEFAULT_MAX_BYTES: Int = 8 * 1024 * 1024

        /**
         * ⚠ Four, matching `content-plane-transfer-concurrency.md`'s chunk fan-out, for the same
         * reason: the proxy is one process and the win is in overlapping latency, not in queueing
         * more work than it can answer.
         */
        const val DEFAULT_MAX_IN_FLIGHT: Int = 4
    }
}
