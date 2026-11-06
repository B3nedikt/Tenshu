package org.tenshu.file.proxy

import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.Preview
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.core.file.ViewerPreview
import org.tenshu.file.impl.UploadFileImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The rung table's own invariants (`drive-xxlarge-thumbnail-rung.md` X-0). Everything that enumerates
 * thumbnail classes reads [ThumbnailClasses], so a wrong row is wrong everywhere at once — these pin the
 * things a new row could get wrong without anything else failing.
 */
class ThumbnailClassesTest {

    @Test
    fun theStillLadderIsOrderedByEdge_smallestFirst() {
        val edges = ThumbnailClasses.STILLS.map { it.px }
        assertEquals(edges.sorted().distinct(), edges, "the resolver's ladder walks STILLS in order")
    }

    /**
     * ⚠⚠ A stretched `LARGE` is the blur the ladder exists to prevent
     * (`drive-synced-thumbnails.md` §14.2), so the small rungs never stretch at all. This half is
     * permanent.
     */
    @Test
    fun theSmallRungsNeverStretch() {
        assertFalse(ThumbnailClasses.LIST.stretches)
        assertFalse(ThumbnailClasses.LARGE.stretches)
    }

    /**
     * ⚠⚠ **A rung stretches only while some caller can decline the stretch** — the invariant X-1
     * shipped with the top rung's flag off to keep, and X-3 satisfies rather than removes.
     *
     * For `XLARGE` the escape is structural: `XXLARGE` covers above it. For the **top** rung nothing
     * covers above it, so the escape is `GetPreview.Params.PREFER_ORIGINAL` — and the only thing that
     * sets it is `File.ViewerPreview`. If that property is ever deleted or stops passing the flag,
     * this pairing is what should go red: `MAX_HERO_PX` is exactly `XXLARGE_PX × COVER_TOLERANCE`, so
     * a stretching top rung with no escape puts **every** hero request inside a band it cannot leave.
     */
    @Test
    fun theTopRungStretchesOnlyBecauseAViewerCanDeclineIt() {
        assertTrue(ThumbnailClasses.XLARGE.stretches)
        assertTrue(ThumbnailClasses.STILLS.last().stretches)
        // ⚠ The reference itself is the guard: delete `File.ViewerPreview` and this stops compiling,
        // which is the point — the band above must not outlive the way out of it.
        // ⚠⚠ That the property really *passes* the flag is pinned by exactly one assertion in the
        // whole suite — `SyncedThumbnailSystemTest`'s `hostViewerEdges` (2600 px, not 2048).
        // `GetPreviewRoPrefersOriginalTest` pins the `Ro`, which the property is not obliged to use,
        // so flipping `preferOriginal = false` in `File.kt` leaves every unit test in this module
        // green. If that system test is ever weakened, this band loses its only real guard.
        assertNotEquals(
            File.Preview.id,
            File.ViewerPreview.id,
            "the viewer's read must stay a property of its own, not an alias of the feed's",
        )
        val flags = ThumbnailClasses.STILLS.map { it.stretches }
        assertEquals(flags.sorted(), flags, "stretching rungs must be a suffix of STILLS: $flags")
    }

    /**
     * ⭐ D-13's edge and cap, locked 2026-09-18 against encoder measurements on all four platforms
     * (`drive-xxlarge-thumbnail-rung.md` §2.1). ⚠⚠ Neither is a round number someone may
     * tidy: the cap was chosen because iOS encodes ~1.65× heavier than the JVM at the same nominal
     * quality, so it is the iOS column that 1 MiB clears — raising or lowering it changes which real
     * photographs are declined outright, on one platform first.
     */
    @Test
    fun theTopRungsEdgeAndCapAreTheOnesTheMeasurementsLocked() {
        assertEquals(2048, GetStoredThumbnail.XXLARGE_PX)
        assertEquals(2048, ThumbnailClasses.XXLARGE.px)
        assertEquals(1024 * 1024, UploadFileImplementation.MAX_XXLARGE_THUMBNAIL_BYTES)
        assertEquals(1024 * 1024, ThumbnailClasses.XXLARGE.maxBytes)
        assertEquals(
            2 * UploadFileImplementation.MAX_XLARGE_THUMBNAIL_BYTES,
            UploadFileImplementation.MAX_XXLARGE_THUMBNAIL_BYTES,
            "twice the hero still's bytes for four times its pixels (§2.1)",
        )
        assertEquals(
            2 * GetStoredThumbnail.XLARGE_PX,
            GetStoredThumbnail.XXLARGE_PX,
            "one doubling above the hero rung — the ladder has no half-steps",
        )
    }

    @Test
    fun classesAndPropertiesAreEachNamedOnce() {
        assertEquals(ThumbnailClasses.ALL.size, ThumbnailClasses.ALL.map { it.id }.toSet().size)
        assertEquals(ThumbnailClasses.ALL.size, ThumbnailClasses.ALL.map { it.property.id }.toSet().size)
    }

    @Test
    fun everyRungRoundTripsThroughTheWriter() {
        for (rung in ThumbnailClasses.ALL) {
            assertEquals(rung.id, FileBytesPropertyWriter.thumbnailClassFor(rung.property.id))
            assertEquals(rung.property.id, FileBytesPropertyWriter.propertyIdFor(rung.id))
        }
        assertEquals(ThumbnailClasses.ALL.map { it.id }.toSet(), FileBytesPropertyWriter.THUMBNAIL_CLASSES)
    }

    /**
     * ⚠⚠ The client-side wanted-set vocabulary lives in `:file-service`, which cannot see this table,
     * so the two are pinned equal here: a backfilled rung nobody can declare would never be minted for
     * existing files, and a declarable class the backfill does not own would be declared for nothing.
     */
    @Test
    fun theBackfilledRungsAreExactlyTheDeclarableClasses() {
        assertEquals(
            ThumbnailPreferences.DECLARABLE_CLASSES,
            ThumbnailClasses.ALL.filter { it.backfilled }.map { it.id }.toSet(),
        )
    }

    @Test
    fun theSmallRungsAreMintedAtUploadOnly() {
        assertFalse(ThumbnailClasses.LIST.backfilled)
        assertFalse(ThumbnailClasses.LARGE.backfilled)
        assertEquals(GetStoredThumbnail.CLASS_LIST, ThumbnailClasses.STILLS.first().id)
    }

    @Test
    fun whatEachKindCanCarry() {
        val still = ThumbnailClasses.XLARGE
        assertTrue(still.canCarry(FileCategory.Image, "/a.jpg", canRenderMotion = false))
        assertTrue(still.canCarry(FileCategory.Video, "/a.mp4", canRenderMotion = false), "a poster frame")
        assertTrue(still.canCarry(FileCategory.Document, "/a.PDF", canRenderMotion = false), "a PDF's first page")
        assertFalse(still.canCarry(FileCategory.Document, "/a.txt", canRenderMotion = false))

        val motion = ThumbnailClasses.MOTION
        assertTrue(motion.canCarry(FileCategory.Video, "/a.mp4", canRenderMotion = true))
        assertFalse(motion.canCarry(FileCategory.Video, "/a.mp4", canRenderMotion = false), "no renderer on this platform")
        assertFalse(motion.canCarry(FileCategory.Image, "/a.jpg", canRenderMotion = true))
    }

    /**
     * ⚠⚠ **Web mirrors these flags by hand and nothing compiles the two together.**
     * `web/drive/src/lib/largePreview.ts`'s `CLASS_COVERAGE` carries `{ edge, stretches }` per class
     * and multiplies the edge by `COVER_TOLERANCE` only for the rungs that stretch — that is what
     * decides whether the web lightbox serves a stored class or downloads a whole 6-18 MB original
     * (`drive-xxlarge-thumbnail-rung.md` §9.7). Flip a flag here without flipping it there and web
     * silently disagrees with the shared ladder, which is where this track's worst defects came from.
     *
     * ⚠ If this test is edited, `largePreview.ts` changes in the same commit.
     */
    @Test
    fun theCoverBandWebMirrorsIsStillPerRung() {
        assertTrue(ThumbnailClasses.XXLARGE.stretches, "xxlarge stretches — largePreview.ts mirrors true")
        assertTrue(ThumbnailClasses.XLARGE.stretches, "xlarge stretches — largePreview.ts mirrors true")
        assertFalse(ThumbnailClasses.LARGE.stretches, "large does NOT stretch — mirrors false")
        assertFalse(ThumbnailClasses.LIST.stretches, "list does NOT stretch — mirrors false")
    }
}