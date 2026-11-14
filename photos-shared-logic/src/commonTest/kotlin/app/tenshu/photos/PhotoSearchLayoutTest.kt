package app.tenshu.photos

import app.tenshu.photos.data.citableLabelMatches
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoHit
import app.tenshu.photos.model.PhotoHitSource
import app.tenshu.photos.model.SemanticSearchStatus
import app.tenshu.photos.ui.screens.search.ModelsSwitchHome
import app.tenshu.photos.ui.screens.search.PhotoSearchCopy
import app.tenshu.photos.ui.screens.search.PhotoSearchLayout
import app.tenshu.photos.ui.screens.search.PhotoSearchLayout.Body
import app.tenshu.photos.ui.screens.search.PhotoSearchUiState
import org.tenshu.inference.INDEX_MARKER_LABEL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Search screen shows, decided once for both shells (`photos-ios.md` §8.19, PIO-D2).
 *
 * ⚠⚠ The tests that carry it keep three answers apart: *nothing matched*, *off* (a source cannot
 * run here) and *could not look* (a source was asked and did not answer).
 */
class PhotoSearchLayoutTest {

    private fun photo(name: String) = Photo("/Backup/Phone/$name", 1_000L, isVideo = false, contentId = "c$name")

    private fun hit(name: String, vararg sources: PhotoHitSource, label: String? = null) =
        PhotoHit(photo(name), sources.toSet(), matchedLabel = label)

    private val noBackend = SemanticSearchStatus.Unavailable("noBackend")
    private val noBackendLine =
        "Searching photos by what's in them needs a model on this device, and there isn't one yet."

    @Test
    fun anEmptyBarInvitesASearch_andSaysNothingElse() {
        val layout = PhotoSearchLayout.of(PhotoSearchUiState(semanticStatus = SemanticSearchStatus.NotBuiltHere("iPhone")))
        assertEquals(Body.Idle("Search by file name."), layout.body)
        assertTrue(layout.notes.isEmpty(), "no footnote on a question nobody has asked")
        assertFalse(layout.retryInNotes)
    }

    @Test
    fun theIdleLinePromisesDescriptionSearchOnlyOnceItWasSeenToRun() {
        // ⚠⚠ "It says what actually works" (`ios-on-device-inference.md` §1.8): every state that
        // would refuse, or might, keeps the plain line.
        // ⚠ The idle line reads what was ANSWERED, never the note's status (the seed is Available).
        fun idle(status: SemanticSearchStatus, answered: Boolean) =
            (PhotoSearchLayout.of(PhotoSearchUiState(semanticStatus = status, semanticKnown = status.takeIf { answered })).body as Body.Idle).text
        val plain = "Search by file name."
        val described = "Search by file name or by what's in a photo."
        assertEquals(described, idle(SemanticSearchStatus.Available, answered = true))
        // ⚠⚠ The seed: Available before anything was asked is an assumption, false on every device
        // with no backend.
        assertEquals(plain, idle(SemanticSearchStatus.Available, answered = false))
        for (reason in listOf("noBackend", "noConsent", "notIndexed", "noTextEmbedding", "somethingNewer")) {
            assertEquals(plain, idle(SemanticSearchStatus.Unavailable(reason), answered = true), reason)
        }
        assertEquals(plain, idle(SemanticSearchStatus.NotBuiltHere("iPhone"), answered = false))
        assertEquals(plain, idle(SemanticSearchStatus.NotBuiltHere("iPhone"), answered = true), "an unsuitable phone")
        assertEquals(plain, idle(SemanticSearchStatus.Failed, answered = true), "a last attempt that failed")
        assertEquals(PhotoSearchCopy.IDLE_WITH_DESCRIPTION, described)
        assertEquals(PhotoSearchCopy.IDLE, plain)
    }

    @Test
    fun whileItSearchesOnlyWhatIsTrueOfTheDeviceIsSaid() {
        // ⚠ "Off" is true whatever is typed, so it stays; "could not look" belongs to the answer before.
        val layout = PhotoSearchLayout.of(
            PhotoSearchUiState(
                query = "dog", searching = true,
                hits = listOf(hit("old.jpg", PhotoHitSource.NAME)),
                semanticStatus = noBackend,
                couldNotLook = setOf(PhotoHitSource.LABEL),
            ),
        )
        assertEquals(Body.Searching, layout.body)
        assertEquals(listOf(noBackendLine), layout.notes)
        assertFalse(layout.retryInNotes)
    }

    @Test
    fun nothingMatchedIsSaidOnlyWhenEverySourceAskedAnswered() {
        val answered = PhotoSearchUiState(query = " giraffe ", settled = true, semanticStatus = noBackend)
        assertEquals(Body.Empty("No photos match \"giraffe\"."), PhotoSearchLayout.of(answered).body)
        // ⚠ Off beside it, never instead of it.
        assertEquals(listOf(noBackendLine), PhotoSearchLayout.of(answered).notes)

        // ⚠⚠ The same empty list with a source that did not answer is NOT "no photos match".
        for (failed in listOf(PhotoHitSource.NAME, PhotoHitSource.LABEL, PhotoHitSource.SEMANTIC)) {
            val body = PhotoSearchLayout.of(answered.copy(couldNotLook = setOf(failed))).body
            assertTrue(body is Body.CouldNotLook, "$failed failed, got $body")
        }
    }

    @Test
    fun couldNotLookSaysHowMuchWasLookedThrough() {
        val some = "Couldn't search everything. Nothing turned up in what was looked through — " +
            "that doesn't mean nothing matches."
        val none = "Couldn't search your photos. Nothing was looked through — that doesn't mean nothing matches."
        fun body(status: SemanticSearchStatus, vararg failed: PhotoHitSource) = PhotoSearchLayout.of(
            PhotoSearchUiState(query = "dog", settled = true, semanticStatus = status, couldNotLook = failed.toSet()),
        ).body

        assertEquals(Body.CouldNotLook(some), body(noBackend, PhotoHitSource.NAME), "tags were looked through")
        // ⚠⚠ A refusal is not looking: with names and tags both failed beside a source that is OFF,
        // nothing at all was searched — "nothing turned up" would be a claim about a search not made.
        assertEquals(Body.CouldNotLook(none), body(noBackend, PhotoHitSource.NAME, PhotoHitSource.LABEL))
        assertEquals(
            Body.CouldNotLook(none),
            body(SemanticSearchStatus.NotBuiltHere("iPhone"), PhotoHitSource.NAME, PhotoHitSource.LABEL),
        )
        assertEquals(
            Body.CouldNotLook(none),
            body(SemanticSearchStatus.Failed, PhotoHitSource.NAME, PhotoHitSource.LABEL, PhotoHitSource.SEMANTIC),
        )
        assertEquals(
            Body.CouldNotLook(some),
            body(SemanticSearchStatus.Available, PhotoHitSource.NAME, PhotoHitSource.LABEL),
            "the description search did look",
        )
    }

    @Test
    fun resultsOverASourceThatCouldNotLookSayThereMayBeMore_andOfferTheRetry() {
        val state = PhotoSearchUiState(
            query = "dog", settled = true,
            hits = listOf(hit("dog.jpg", PhotoHitSource.NAME)),
            semanticStatus = SemanticSearchStatus.Failed,
            couldNotLook = setOf(PhotoHitSource.LABEL, PhotoHitSource.SEMANTIC),
        )
        val layout = PhotoSearchLayout.of(state)
        assertTrue(layout.body is Body.Results)
        assertEquals(
            listOf(
                "Couldn't search by tag this time, so there may be more.",
                "Searching photos by appearance didn't work this time.",
            ),
            layout.notes,
        )
        assertTrue(layout.retryInNotes)
        assertEquals(
            listOf("Couldn't search by file name or tag this time, so there may be more."),
            PhotoSearchCopy.missed(setOf(PhotoHitSource.NAME, PhotoHitSource.LABEL)),
        )
        assertFalse(PhotoSearchLayout.of(state.copy(couldNotLook = emptySet(), semanticStatus = noBackend)).retryInNotes)
    }

    @Test
    fun aCappedListSaysSo() {
        val hits = (1..3).map { hit("dog$it.jpg", PhotoHitSource.NAME) }
        val capped = PhotoSearchLayout.of(PhotoSearchUiState(query = "dog", settled = true, hits = hits, capped = true))
        assertEquals(
            listOf("Showing the first 3 matches. Narrow the search to see fewer, better ones."),
            (capped.body as Body.Results).footers,
        )
        val whole = PhotoSearchLayout.of(PhotoSearchUiState(query = "dog", settled = true, hits = hits))
        assertTrue((whole.body as Body.Results).footers.isEmpty())
    }

    @Test
    fun whereItIsNotBuilt_theSentenceOffersNothingTheDeviceCannotDo() {
        // ⚠⚠ `inference-service.md`: "Settings→Models is the only route to a model". A platform with no
        // Models screen must not be sent to one, offered a switch, or promised a download.
        val line = PhotoSearchCopy.off(SemanticSearchStatus.NotBuiltHere("iPad"), ModelsSwitchHome.CONTROL)!!
        assertEquals("Search by description isn't available on this iPad yet.", line)
        for (word in listOf("Control", "Settings", "Models", "Turn on", "switch", "model", "download")) {
            assertFalse(line.contains(word, ignoreCase = true), "\"$word\" in: $line")
        }
        // …and only the consent refusal — which the proxy alone can give — names the switch.
        val noConsent = SemanticSearchStatus.Unavailable("noConsent")
        assertTrue(PhotoSearchCopy.off(noConsent, ModelsSwitchHome.CONTROL)!!.endsWith("in Control, under Settings → Models."))
        // ⚠ Where this app runs its own proxy the switch is its own, and Control has none to name.
        assertTrue(PhotoSearchCopy.off(noConsent, ModelsSwitchHome.THIS_APP)!!.endsWith("in Photos, under Settings → Models."))
        assertTrue(
            PhotoSearchLayout.of(PhotoSearchUiState(query = "dog", settled = true, semanticStatus = noConsent, modelsSwitch = ModelsSwitchHome.THIS_APP))
                .notes.single().endsWith("in Photos, under Settings → Models."),
        )
        assertNull(PhotoSearchCopy.off(SemanticSearchStatus.Available, ModelsSwitchHome.CONTROL))
        assertNull(PhotoSearchCopy.off(SemanticSearchStatus.Failed, ModelsSwitchHome.CONTROL), "a failure is 'could not look', not 'off'")
    }

    @Test
    fun theBadgeSaysWhichSourceFoundARow() {
        assertNull(PhotoSearchCopy.badge(hit("dog.jpg", PhotoHitSource.NAME, PhotoHitSource.LABEL, label = "dog")))
        assertEquals("tagged \"dog\"", PhotoSearchCopy.badge(hit("a.jpg", PhotoHitSource.LABEL, label = "dog")))
        assertEquals("tagged", PhotoSearchCopy.badge(hit("a.jpg", PhotoHitSource.LABEL)))
        assertEquals("looks like this", PhotoSearchCopy.badge(hit("a.jpg", PhotoHitSource.SEMANTIC)))
    }

    // ── the label join's first half (`TenshuPhotosRepository.searchByLabelRead`) ─────────────────────

    @Test
    fun aLabelAtATrashPathIsNeverAMatch_andNeverUsesUpTheCap() {
        // ⚠⚠ `media-processing-pipeline-sweep.md` P0g: Photos' label search must not cite a trashed
        // photograph. A trashed photograph has a live `File` row — at `/Trash/…` — so only this filter
        // keeps it out once D14 keys a typed name there. ⚠ Before the cap: counted first, the trashed
        // rows would fill it and hide the live match behind them.
        val rows = listOf(
            "/Trash/c1-a.jpg" to "dog",
            "/Trash/c2-b.jpg" to "dog",
            "/Photos/c.jpg" to "Dog",
            "/Trashcan/d.jpg" to "hotdog",
        )
        assertEquals(
            listOf("/Photos/c.jpg" to "Dog", "/Trashcan/d.jpg" to "hotdog"),
            citableLabelMatches(rows, "dog", limit = 2),
        )
    }

    @Test
    fun theIndexMarkerIsNeverAMatch_andAPhotographMatchesOnce() {
        val rows = listOf(
            "/a.jpg" to INDEX_MARKER_LABEL,
            "/a.jpg" to "dog",
            "/a.jpg" to "dogs",
            "/b.jpg" to "cat",
        )
        assertEquals(listOf("/a.jpg" to "dog"), citableLabelMatches(rows, "dog", limit = 60))
        assertTrue(
            citableLabelMatches(rows, INDEX_MARKER_LABEL.take(4), limit = 60).isEmpty(),
            "a search for part of the sentinel must not return the whole library",
        )
    }
}
