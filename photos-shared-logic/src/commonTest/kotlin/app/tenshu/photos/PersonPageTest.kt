package app.tenshu.photos

import app.tenshu.photos.model.FaceCover
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsUiState
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.ui.screens.collections.SmartPage
import app.tenshu.photos.ui.screens.collections.UnfinishedNaming
import app.tenshu.photos.ui.screens.collections.personPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `ios-face-grouping.md` F7: what a person's page shows, decided once for Compose's
 * `Destination.Smart` and the SwiftUI person page — moved out of `App.kt` unchanged.
 */
class PersonPageTest {

    private fun photo(id: String) = Photo("/$id.jpg", 1L, isVideo = false, contentId = id)
    private val face = FaceCover("/a1.jpg", FaceRegion(0.4f, 0.2f, 0.6f, 0.5f))

    /** Anna, named, who absorbed cluster g1 — so `person:g1` is one of her addresses. */
    private val anna = SmartAlbum(
        id = "person:name:Anna", title = "Anna", subtitle = "", photos = listOf(photo("a1"), photo("a2")),
        alternateIds = listOf("person:g1"), coverFace = face,
    )
    private val unnamed = SmartAlbum("person:g2", "", "", listOf(photo("b1")))
    private val place = SmartAlbum("place:Paris", "Paris", "", listOf(photo("a1")))
    private val state = CollectionsUiState(people = listOf(anna, unnamed), places = listOf(place))

    private fun live(album: SmartAlbum?) = SmartPage(album, loading = false, failed = false)

    @Test
    fun aPersonReachedByAnAliasIsAPersonAndIsNamedByTheirPrimaryId() {
        val page = state.personPage("person:g1", live(anna))
        val header = assertNotNull(page.header)
        assertEquals(face, header.cover, "the header's face is the person's cover")
        assertEquals("person:g1", header.key, "the With… selection is keyed on the ROUTE")
        assertEquals(listOf(unnamed), header.companions, "everyone else, never Anna under her old cluster id")
        assertEquals("person:name:Anna", page.nameId)
    }

    @Test
    fun aPlaceIsNoPersonAndOffersNoNaming() {
        val page = state.personPage("place:Paris", live(place))
        assertNull(page.header)
        assertNull(page.nameId)
    }

    /** ⚠ A snapshot card has no photographs yet: naming would name nothing. */
    @Test
    fun aSnapshotCardIsAPersonButCannotBeNamedYet() {
        val loading = state.personPage("person:g1", SmartPage(anna, loading = true, failed = false))
        assertNotNull(loading.header)
        assertNull(loading.nameId)
        assertNull(state.personPage("person:g1", SmartPage(anna, loading = false, failed = true)).nameId)
    }

    @Test
    fun aVerdictBelongsToThePersonItWasFor_throughEveryAddress() {
        val refused = state.copy(nameFailure = NamingOutcome.NoConsent, nameFailureFor = "person:name:Anna")
        assertEquals(NamingOutcome.NoConsent, refused.personPage("person:g1", live(anna)).nameFailure, "on her alias route")
        assertNull(refused.personPage("person:g2", live(unnamed)).nameFailure, "never on someone else's page")
        // ⚠⚠ A verdict recorded while she was still the cluster: a partial save folds g1 into Anna, so
        // the id it was recorded under is now one of her ALIASES — her page must still say it.
        val beforeTheFold = state.copy(nameFailure = NamingOutcome.PartiallySaved, nameFailureFor = "person:g1")
        assertEquals(NamingOutcome.PartiallySaved, beforeTheFold.personPage("person:name:Anna", live(anna)).nameFailure)
    }

    @Test
    fun anUnfinishedNamingIsTheNameThatWasBeingWritten_forThisPersonOnly() {
        val interrupted = state.copy(namingIncompleteFor = setOf(UnfinishedNaming("person:name:Anna", "Bob")))
        assertEquals("Bob", interrupted.personPage("person:g1", live(anna)).unfinishedName)
        assertNull(interrupted.personPage("person:g2", live(unnamed)).unfinishedName)
        val asTheCluster = state.copy(namingIncompleteFor = setOf(UnfinishedNaming("person:g1", "Anna")))
        assertEquals("Anna", asTheCluster.personPage("person:name:Anna", live(anna)).unfinishedName, "recorded under her alias")
    }

    @Test
    fun aCompanionIsFoundThroughTheirAliases() {
        val header = assertNotNull(state.personPage("person:g2", live(unnamed)).header)
        assertEquals(anna, header.chosenCompanion("person:g1"), "a selection made before Anna was named")
        assertNull(header.chosenCompanion(null))
        assertNull(header.chosenCompanion("person:gone"))
    }
}
