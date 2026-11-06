package app.tenshu.photos

import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.DuplicatesResult
import app.tenshu.photos.data.ExifReadResult
import app.tenshu.photos.data.TrashResult
import app.tenshu.photos.data.albumsReadFailure
import app.tenshu.photos.data.duplicatesReadFailure
import app.tenshu.photos.data.exifReadFailure
import app.tenshu.photos.data.trashReadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The same classification `PageReadFailureTest` pins for the feed, for the five other reads
 * (`ADR 0025`).
 *
 * ## ⚠⚠ Why each read classifies
 *
 * Unclassified, each read folds a failure straight to its own `Failed` —
 * `AlbumsResult.Failed("could not read albums")` and friends. Their `Unreachable` arm is then
 * reachable **only** from a `catch`, and a dead transport folds rather than throwing on both
 * platforms, so no `catch` runs. An uninstalled Control would then render *"something went
 * wrong"* with a **Try again** button on Collections, the trash, duplicates and the About panel.
 *
 * ⚠ These are five one-line functions and the tests are deliberately dull. The value is that the
 * decision is written **once per family** and can be checked without a `TenshuClient`: a fake
 * carrying a real connection state would be an order of magnitude larger than what it proves.
 */
class ReadFailureClassificationTest {

    /**
     * ⚠ `false` is authoritative — no call crosses a dead channel, so a read that failed while the
     * channel was down failed *because* of it. This is the arm that was unreachable.
     */
    @Test
    fun everyFamilyReportsAnOutageWhenTheChannelIsDown() {
        assertEquals(AlbumsResult.Unreachable, albumsReadFailure(false, "could not read albums"))
        assertEquals(TrashResult.Unreachable, trashReadFailure(false, "could not read the trash"))
        assertEquals(DuplicatesResult.Unreachable, duplicatesReadFailure(false, "no index"))
        assertEquals(ExifReadResult.Unreachable, exifReadFailure(false, "no probe answer"))
    }

    /**
     * ⚠⚠ `true` proves nothing about the read, only about the channel: a connected proxy still
     * refuses every read on a schema mismatch, and an unregistered schema (§18.4) fails at dispatch
     * over a perfectly live one. Reporting "unreachable" for those is what put the
     * Control-unreachable banner over a client-side bug.
     */
    @Test
    fun everyFamilyKeepsAGenuineReadFailureOverALiveChannel() {
        val albums = albumsReadFailure(true, "boom")
        assertIs<AlbumsResult.Failed>(albums)
        assertEquals("boom", albums.message)

        val trash = trashReadFailure(true, "boom")
        assertIs<TrashResult.Failed>(trash)
        assertEquals("boom", trash.message)

        val duplicates = duplicatesReadFailure(true, "boom")
        assertIs<DuplicatesResult.Failed>(duplicates)
        assertEquals("boom", duplicates.message)

        val exif = exifReadFailure(true, "boom")
        assertIs<ExifReadResult.Failed>(exif)
        assertEquals("boom", exif.message)
    }

    /**
     * ⚠⚠ The classification must not depend on the error's TEXT. The executor's connection failure
     * is a `GenericError` carrying the string *"Proxy not connected"*, and matching on it would be
     * a rename away from a silent production-only regression — the shape of the relay-401
     * status-text bug. Same words, opposite answers, decided entirely by the channel.
     */
    @Test
    fun theSameMessageClassifiesBothWays_becauseTheTextIsNotTheSignal() {
        val words = "Proxy not connected"
        assertEquals(AlbumsResult.Unreachable, albumsReadFailure(false, words))
        assertIs<AlbumsResult.Failed>(albumsReadFailure(true, words))
        assertEquals(TrashResult.Unreachable, trashReadFailure(false, words))
        assertIs<TrashResult.Failed>(trashReadFailure(true, words))
        assertEquals(DuplicatesResult.Unreachable, duplicatesReadFailure(false, words))
        assertIs<DuplicatesResult.Failed>(duplicatesReadFailure(true, words))
        assertEquals(ExifReadResult.Unreachable, exifReadFailure(false, words))
        assertIs<ExifReadResult.Failed>(exifReadFailure(true, words))
    }
}
