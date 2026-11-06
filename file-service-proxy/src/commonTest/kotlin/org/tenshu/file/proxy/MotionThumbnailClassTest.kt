package org.tenshu.file.proxy

import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailMotion
import org.tenshu.file.impl.UploadFileImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `MOTION` class's wiring (`photos-video-support.md` M3).
 *
 * ⚠⚠ **The load-bearing assertion is the negative one.** `MOTION` shares [GetStoredThumbnail] with
 * the still classes because storage and delivery are identical, and that adjacency is exactly
 * what makes it tempting to fold into `GetPreview`'s "smallest class that covers the request"
 * ladder. It must never be: `GetPreview` answers *"give me a bitmap"*, and a request for a still
 * that resolved to an H.264 clip would hand a decoder a video and a caller a corrupt bitmap.
 */
class MotionThumbnailClassTest {

    @Test
    fun motionIsItsOwnClassAndAddressesItsOwnBlob() {
        assertEquals(
            GetStoredThumbnail.CLASS_MOTION,
            FileBytesPropertyWriter.thumbnailClassFor(File.ThumbnailMotion.id),
            "the writer must route the motion property to the motion class",
        )

        // ⚠ Its blob is keyed by class, so it cannot collide with the stills of the same file —
        // the property that lets a class be evicted without touching its siblings.
        val contentId = "content-abc"
        val motionPath = FileBytesPropertyWriter.thumbnailPath(contentId, GetStoredThumbnail.CLASS_MOTION)
        ThumbnailClasses.STILLS.map { it.id }.forEach { stillClass ->
            assertNotEquals(
                FileBytesPropertyWriter.thumbnailPath(contentId, stillClass),
                motionPath,
                "motion must not share a blob path with $stillClass",
            )
        }
    }

    /**
     * ⚠⚠ The guard. If `MOTION` ever joined `GetPreview`'s ladder, a still request could resolve to a
     * clip. The ladder is [ThumbnailClasses.STILLS] and the resolver is [StoredThumbnailLadder]
     * (`drive-xxlarge-thumbnail-rung.md` X-0), so this asks both: no still rung is the motion class,
     * and neither rule so much as **reads** the motion property, at any requested size.
     */
    @Test
    fun theStillLadderDoesNotIncludeMotion() {
        ThumbnailClasses.STILLS.forEach { rung ->
            assertEquals(rung.id, FileBytesPropertyWriter.thumbnailClassFor(rung.property.id))
            assertNotEquals(
                GetStoredThumbnail.CLASS_MOTION,
                rung.id,
                "${rung.property.id} is a still and must never resolve to the motion class",
            )
        }

        val read = mutableSetOf<String>()
        for (requestedMaxEdge in listOf(1, 100, 256, 257, 1024, 2048, 10_000)) {
            StoredThumbnailLadder.best(requestedMaxEdge) { read += it.id; null }
            StoredThumbnailLadder.covering(requestedMaxEdge) { read += it.id; null }
        }
        assertEquals(ThumbnailClasses.STILLS.map { it.property.id }.toSet(), read)
        assertTrue(File.ThumbnailMotion.id !in read, "the still ladder read the motion property")
    }

    /**
     * ⭐ M4: the cap is the one §12.5 locked, and it sits where the ladder says — under the hero
     * still, above `LARGE`. A "tidier" round number here is a different quality than was measured.
     */
    @Test
    fun theMotionCapIsTheLockedOneAndSitsInTheLadder() {
        assertEquals(96 * 1024, UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES)
        assertTrue(
            UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES < UploadFileImplementation.MAX_XLARGE_THUMBNAIL_BYTES,
            "a video's motion preview costs less than a photo's hero still (§12.5)",
        )
        assertTrue(
            UploadFileImplementation.MAX_MOTION_THUMBNAIL_BYTES > UploadFileImplementation.MAX_LARGE_THUMBNAIL_BYTES,
        )
        assertEquals(GetStoredThumbnail.LARGE_PX, GetStoredThumbnail.MOTION_PX, "256 px — the grid tile's own size (§4.5)")
    }

    /** ⚠ A property that is not a thumbnail at all still maps to nothing — the writer's null case. */
    @Test
    fun aNonThumbnailPropertyHasNoClass() {
        assertNull(FileBytesPropertyWriter.thumbnailClassFor(File.Data.id))
        assertNull(FileBytesPropertyWriter.thumbnailClassFor("tenshu/some/other/property"))
    }
}
