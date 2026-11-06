package org.tenshu.file.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.relation.Property
import org.tenshu.core.toTByteArray
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * ⚠⚠ X-0 of `drive-xxlarge-thumbnail-rung.md` is a refactor with **no behaviour change**, and this is
 * the proof: the shared ladder is compared with a verbatim copy of the rule each `GetPreview` actual
 * carried before it — every request band edge and every combination of which classes are on this
 * device. The local reads are pinned separately: `best` now reads fewer than the copies did.
 */
class StoredThumbnailLadderTest {

    private val context = createFileProxy().tContext

    private val list = "list".encodeToByteArray().toTByteArray(context)
    private val large = "large".encodeToByteArray().toTByteArray(context)
    private val xlarge = "xlarge".encodeToByteArray().toTByteArray(context)
    private val xxlarge = "xxlarge".encodeToByteArray().toTByteArray(context)

    /** Band edges of the old rules, and a value either side of each. */
    private val requests = listOf(
        1, 99, 100, 101, 255, 256, 257, 1023, 1024, 1025, 2047, 2048, 2049, 4096, 10_000,
    )

    /** Every subset of the three still classes being present. */
    private val presences: List<Triple<Boolean, Boolean, Boolean>> =
        (0 until 8).map { Triple(it and 1 != 0, it and 2 != 0, it and 4 != 0) }

    private fun store(present: Triple<Boolean, Boolean, Boolean>): Map<String, TByteArray> = buildMap {
        if (present.first) put(File.Thumbnail.id, list)
        if (present.second) put(File.ThumbnailLarge.id, large)
        if (present.third) put(File.ThumbnailXLarge.id, xlarge)
    }

    /** `storedThumbnail` as the three actuals spelled it until X-0. */
    private fun oldBest(requestedMaxEdge: Int, s: Map<String, TByteArray>): TByteArray? {
        val list = s[File.Thumbnail.id]
        val large = s[File.ThumbnailLarge.id]
        val xlarge = s[File.ThumbnailXLarge.id]
        return when {
            requestedMaxEdge > GetStoredThumbnail.LARGE_PX && xlarge != null -> xlarge
            requestedMaxEdge > GetStoredThumbnail.LIST_PX && large != null -> large
            list != null -> list
            else -> xlarge ?: large
        }
    }

    /** `coveringStoredThumbnail` as the three actuals spelled it until X-0. */
    private fun oldCovering(requestedMaxEdge: Int, s: Map<String, TByteArray>): TByteArray? = when {
        requestedMaxEdge <= GetStoredThumbnail.LARGE_PX ->
            s[File.ThumbnailLarge.id] ?: s[File.ThumbnailXLarge.id]
        requestedMaxEdge <= (GetStoredThumbnail.XLARGE_PX * GetStoredThumbnail.COVER_TOLERANCE).toInt() ->
            s[File.ThumbnailXLarge.id]
        else -> null
    }

    @Test
    fun bestMatchesTheOldRuleForEveryRequestAndEveryPresence() {
        for (present in presences) {
            val s = store(present)
            for (request in requests) {
                assertSame(
                    oldBest(request, s),
                    StoredThumbnailLadder.best(request) { s[it.id] },
                    "best at $request px with list/large/xlarge present = $present",
                )
            }
        }
    }

    @Test
    fun coveringMatchesTheOldRuleForEveryRequestAndEveryPresence() {
        for (present in presences) {
            val s = store(present)
            // The actuals only ask `covering` above the LIST rung.
            for (request in requests.filter { it > GetStoredThumbnail.LIST_PX }) {
                assertSame(
                    oldCovering(request, s),
                    StoredThumbnailLadder.covering(request) { s[it.id] },
                    "covering at $request px with list/large/xlarge present = $present",
                )
            }
        }
    }

    /**
     * ⚠⚠ The reads, not just the answers. `best` serves every ≤ 100 px Files row, and the copies it
     * replaced read every class up front — a blob of up to 1 MB per row, now that `XXLARGE` exists,
     * that was then discarded. Each class is read at most once, and only when the rule could serve it.
     */
    @Test
    fun eachRuleReadsOnlyTheClassesItCouldServe() {
        fun reads(
            local: Map<String, TByteArray>,
            block: ((Property.Dynamic<TByteArray>) -> TByteArray?) -> Unit,
        ): List<String> {
            val seen = mutableListOf<String>()
            block { seen += it.id; local[it.id] }
            return seen
        }
        val none = emptyMap<String, TByteArray>()
        val all = store(Triple(true, true, true))

        assertEquals(listOf(File.Thumbnail.id), reads(all) { StoredThumbnailLadder.best(100, resolve = it) }, "a list row with its LIST reads only LIST")
        assertEquals(listOf(File.ThumbnailXLarge.id), reads(all) { StoredThumbnailLadder.best(500, resolve = it) })
        assertEquals(
            listOf(File.ThumbnailXLarge.id, File.ThumbnailLarge.id, File.Thumbnail.id, File.ThumbnailXXLarge.id),
            reads(none) { StoredThumbnailLadder.best(500, resolve = it) },
            "the larger rung is only reached by the largest-first fallback, once nothing else answered",
        )
        assertEquals(
            listOf(File.Thumbnail.id, File.ThumbnailXXLarge.id, File.ThumbnailXLarge.id, File.ThumbnailLarge.id),
            reads(none) { StoredThumbnailLadder.best(50, resolve = it) },
            "without LIST, the largest-first fallback reads the rest once each",
        )

        assertEquals(listOf(File.ThumbnailLarge.id), reads(all) { StoredThumbnailLadder.covering(200, resolve = it) }, "covering stops at the first class it finds")
        assertEquals(
            listOf(File.ThumbnailLarge.id, File.ThumbnailXLarge.id, File.ThumbnailXXLarge.id),
            reads(none) { StoredThumbnailLadder.covering(200, resolve = it) },
        )
        assertEquals(
            listOf(File.ThumbnailXXLarge.id, File.ThumbnailXLarge.id),
            reads(none) { StoredThumbnailLadder.covering(1500, resolve = it) },
            "the covering rung first, then the one it may stretch",
        )
        assertEquals(emptyList(), reads(none) { StoredThumbnailLadder.covering(5000, resolve = it) })
    }

    /**
     * §4.2's table, now that `XXLARGE` ships: which local class answers which request, and what
     * D-17's byte holder gets instead (`GetPreview.Params.PREFER_ORIGINAL` ⇒ `allowStretch = false`).
     */
    @Test
    fun coveringFollowsTheD14Table() {
        fun covering(request: Int, allowStretch: Boolean = true, vararg local: Pair<String, TByteArray>): TByteArray? {
            val s = local.toMap()
            val reads = mutableListOf<String>()
            val result = StoredThumbnailLadder.covering(request, allowStretch = allowStretch) { reads += it.id; s[it.id] }
            assertEquals(reads.distinct(), reads, "each class is read at most once at $request px")
            return result
        }
        val l = File.ThumbnailLarge.id to large
        val xl = File.ThumbnailXLarge.id to xlarge
        val xxl = File.ThumbnailXXLarge.id to xxlarge

        // ≤ 256: LARGE, then XLARGE, then XXLARGE — the smallest that covers wins.
        assertSame(large, covering(200, local = arrayOf(l, xl, xxl)))
        assertSame(xlarge, covering(200, local = arrayOf(xl, xxl)))
        assertSame(xxlarge, covering(200, local = arrayOf(xxl)))
        // ≤ 1024: XLARGE, then XXLARGE; never a stretched LARGE.
        assertSame(xlarge, covering(1000, local = arrayOf(l, xl, xxl)))
        assertSame(xxlarge, covering(1000, local = arrayOf(l, xxl)))
        assertNull(covering(1000, local = arrayOf(l)))
        // ≤ 2048: XXLARGE, then XLARGE within tolerance.
        assertSame(xxlarge, covering(1500, local = arrayOf(l, xl, xxl)))
        assertSame(xlarge, covering(1500, local = arrayOf(l, xl)))
        assertSame(xlarge, covering(2048, local = arrayOf(xl)))
        // ≤ 4096: XXLARGE within tolerance, and XLARGE is no longer close enough. ⚠⚠ Live since
        // X-3, and only because `File.ViewerPreview` can decline it — the `allowStretch = false`
        // cases below are the other half of this row, not a variant of it.
        assertSame(xxlarge, covering(3000, local = arrayOf(xl, xxl)))
        assertNull(covering(3000, local = arrayOf(l, xl)))
        // Above the band nothing stored may answer instead of a render.
        assertNull(covering(4097, local = arrayOf(l, xl, xxl)))

        // ⚠⚠ D-17: the device holding the original takes a covering rung, never a stretched one.
        assertSame(xxlarge, covering(1500, allowStretch = false, local = arrayOf(l, xl, xxl)))
        assertNull(
            covering(1500, allowStretch = false, local = arrayOf(l, xl)),
            "a stretched XLARGE is what the byte holder asked not to be given",
        )
        assertNull(covering(3000, allowStretch = false, local = arrayOf(xl, xxl)))
    }

    /**
     * ⚠⚠ **The top rung's stretch band changes no byte-less answer** — checked, rather than asserted
     * in prose, because the claim carried two phases.
     *
     * X-1 shipped the band **off** and this proved that cost the byte-less device nothing; X-3 turned
     * it **on** and the same proof says turning it on is not a behaviour change for that device
     * either. Both directions reduce to one fact: a device without the original runs `covering`, gets
     * no bytes from `GetData`, and falls to `best` — which above 2048 px returns the same `XXLARGE`
     * the band would have, since the request clears the rung below it.
     *
     * ⚠ So the band is **only** ever about a device that can render, which is exactly the device
     * `File.ViewerPreview` speaks for. That is why D-14's top row and D-17 had to ship together.
     */
    @Test
    fun theByteLessAnswerIsTheSameWithOrWithoutTheTopRungsStretchBand() {
        val nonStretchingTop = ThumbnailClasses.STILLS.map { rung ->
            if (rung !== ThumbnailClasses.XXLARGE) rung else ThumbnailClass.Still(
                id = rung.id,
                property = rung.property,
                px = rung.px,
                maxBytes = rung.maxBytes,
                backfilled = rung.backfilled,
                stretches = false,
            )
        }

        /** What a device with no local original answers: `covering`, then — `GetData` null — `best`. */
        fun byteLess(request: Int, s: Map<String, TByteArray>, stills: List<ThumbnailClass.Still>) =
            StoredThumbnailLadder.covering(request, stills) { s[it.id] }
                ?: StoredThumbnailLadder.best(request, stills) { s[it.id] }

        val everything = buildMap {
            put(File.Thumbnail.id, list)
            put(File.ThumbnailLarge.id, large)
            put(File.ThumbnailXLarge.id, xlarge)
            put(File.ThumbnailXXLarge.id, xxlarge)
        }
        for (s in listOf(everything, everything - File.ThumbnailXXLarge.id, everything - File.ThumbnailXLarge.id)) {
            for (request in requests) {
                assertSame(
                    byteLess(request, s, nonStretchingTop),
                    byteLess(request, s, ThumbnailClasses.STILLS),
                    "the byte-less answer at $request px depends on the top rung's stretch band",
                )
            }
        }

        // ⚠ The two ladders do differ where the flag bites — without this the loop above could pass
        // for the trivial reason that `nonStretchingTop` was built wrong.
        assertSame(xxlarge, StoredThumbnailLadder.covering(3000, ThumbnailClasses.STILLS) { everything[it.id] })
        assertNull(StoredThumbnailLadder.covering(3000, nonStretchingTop) { everything[it.id] })

        // And it is `XXLARGE` that answers up there, not a smaller class.
        assertSame(xxlarge, byteLess(3000, everything, ThumbnailClasses.STILLS))
        assertSame(xxlarge, byteLess(4096, everything, ThumbnailClasses.STILLS))
        assertSame(xlarge, byteLess(3000, everything - File.ThumbnailXXLarge.id, ThumbnailClasses.STILLS))
    }
}
