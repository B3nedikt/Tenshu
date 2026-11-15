package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.FaceRejection
import app.tenshu.photos.model.GroupFace
import app.tenshu.photos.model.MAX_REJECTED_FACES
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoLabel
import app.tenshu.photos.model.nameSuggestions
import app.tenshu.photos.model.peopleFrom
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.personPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.inference.InferenceCapability
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *"Is this Anna?"* (IN-6 F6c name suggestions, `photos-people-and-pets.md` §6a *F6c-s as built*).
 *
 * ⚠⚠ The case that opened it (2026-11-11, the maintainer's phones): the iPhone's group was exactly
 * the two photographs named "Benedikt" and folded into him; the OnePlus's group for the same face
 * also held photographs only it had indexed, so it stayed an unnamed card beside "Benedikt".
 */
class NameSuggestionsTest {

    private val faceCapability = InferenceCapability.FACE_CLUSTERING.id

    private fun label(path: String, name: String) = PhotoLabel(path, name, 1_000, faceCapability)

    private val left = FaceRegion(0.1f, 0.1f, 0.4f, 0.5f)
    private val right = FaceRegion(0.6f, 0.1f, 0.9f, 0.5f)

    /** A face alone in its photograph — the detector found one. */
    private fun solo(path: String) = GroupFace(path, 0, left, facesInPhoto = 1)

    /** A face in a photograph where the detector found [inPhoto]. */
    private fun crowd(path: String, region: FaceRegion = left, index: Int = 0, inPhoto: Int = 2) =
        GroupFace(path, index, region, facesInPhoto = inPhoto)

    private fun group(id: String, vararg faces: GroupFace) =
        FaceGroup(id, faces.map { it.path }.distinct(), faces.toList())

    @Test
    fun theUnfoldedGroupHoldingAnnasOwnFacesIsAskedAboutAnna() {
        // The OnePlus: two photographs named, three more only this device indexed.
        val benedikt = group("g1", solo("/1"), solo("/2"), solo("/3"), solo("/4"), solo("/5"))
        val suggestions = nameSuggestions(
            listOf(benedikt),
            listOf(label("/1", "Benedikt"), label("/2", "Benedikt")),
            rejections = emptyList(),
        )
        val asked = suggestions.getValue("g1")
        assertEquals("Benedikt", asked.name)
        // ⚠ Every unambiguous Benedikt face is in this one group: the confident level, a fold.
        assertTrue(asked.confident)
        assertEquals(listOf("/3", "/4", "/5"), asked.unlabelledPaths)
        // ⚠ A "No" would record the faces the name is NOT already on — never the labelled ones.
        assertEquals(listOf("/3", "/4", "/5"), asked.faces.map { it.path })
    }

    @Test
    fun aFriendOnAnnasGroupPhotoIsNeverAskedAboutAnna() {
        // ⚠⚠ The maintainer's amendment: "/party" is labelled Anna and holds Anna AND her friend.
        val anna = group("anna", solo("/a1"), solo("/a2"), crowd("/party", left), solo("/a3"))
        val friend = group("friend", crowd("/party", right, index = 1), solo("/f1"), solo("/f2"))
        val labels = listOf(label("/a1", "Anna"), label("/a2", "Anna"), label("/party", "Anna"))

        val suggestions = nameSuggestions(listOf(anna, friend), labels, emptyList())

        assertEquals("Anna", suggestions["anna"]?.name)
        assertTrue(suggestions.getValue("anna").confident)
        assertNull(suggestions["friend"], "a group touching Anna only through a shared photograph is not Anna")
        // ⚠ And the fold draws the friend's group as its own unnamed card, Anna's into Anna.
        val people = peopleFrom(listOf(anna, friend), labels, displayFolds = mapOf("anna" to "Anna"))
        assertEquals(listOf("/a1", "/a2", "/party", "/a3"), people.single { it.name == "Anna" }.filePaths)
        assertEquals(listOf("/a3"), people.single { it.name == "Anna" }.foldedPaths)
        assertEquals(listOf("person:friend"), people.filter { it.name.isEmpty() }.map { it.id })
    }

    @Test
    fun aFriendIsNotAskedEvenWhenAnnasGroupIsFoldedAlready() {
        // The iPhone: Anna's group is exactly her named photographs, so it folds — and must still
        // win over the friend, or the friend becomes the only candidate left.
        val anna = group("anna", solo("/a1"), crowd("/party", left))
        val friend = group("friend", crowd("/party", right, index = 1), solo("/f1"))
        val labels = listOf(label("/a1", "Anna"), label("/party", "Anna"))

        val suggestions = nameSuggestions(listOf(anna, friend), labels, emptyList())

        assertTrue(suggestions.isEmpty(), "got $suggestions")
    }

    @Test
    fun oneNamedPhotoInsideABigGroupIsAQuestionNeverAFold() {
        // ⚠⚠ A mis-merged group of 200 with ONE of Anna's solo photographs in it: folding would draw
        // 200 photographs as hers, and Confirm would label every one.
        val big = group("big", solo("/a1"), *(1..199).map { solo("/x$it") }.toTypedArray())
        val one = nameSuggestions(listOf(big), listOf(label("/a1", "Anna")), emptyList()).getValue("big")
        assertEquals(false, one.confident, "one photograph is an anecdote")
        // … and so it is in a small group, where the share alone would pass.
        val small = group("small", solo("/a1"), solo("/x1"), solo("/x2"))
        assertEquals(false, nameSuggestions(listOf(small), listOf(label("/a1", "Anna")), emptyList()).getValue("small").confident)

        // Two sure photographs, but still a sliver of the group: asked.
        val twoOfMany = group("g", solo("/a1"), solo("/a2"), *(1..18).map { solo("/x$it") }.toTypedArray())
        val labels = listOf(label("/a1", "Anna"), label("/a2", "Anna"))
        assertEquals(false, nameSuggestions(listOf(twoOfMany), labels, emptyList()).getValue("g").confident)
        // Two of ten — the share the floor allows: folded.
        val twoOfTen = group("g", solo("/a1"), solo("/a2"), *(1..8).map { solo("/x$it") }.toTypedArray())
        assertEquals(true, nameSuggestions(listOf(twoOfTen), labels, emptyList()).getValue("g").confident)
    }

    @Test
    fun ofTwoGroupsTouchingAnnaOnlyTheOneWithMoreOfHerOwnFacesIsAsked() {
        val strong = group("strong", solo("/a1"), solo("/a2"), solo("/x1"))
        val weak = group("weak", solo("/a3"), crowd("/a4"), solo("/x2"))
        val labels = listOf("/a1", "/a2", "/a3", "/a4").map { label(it, "Anna") }

        val suggestions = nameSuggestions(listOf(strong, weak), labels, emptyList())

        assertEquals(setOf("strong"), suggestions.keys)
        // ⚠⚠ Anna's unambiguous faces are split across two groups: asked, never folded.
        assertEquals(false, suggestions.getValue("strong").confident)
    }

    @Test
    fun twoGroupsEquallyAnnaAreNeitherAsked() {
        val one = group("one", solo("/a1"), solo("/x1"))
        val two = group("two", solo("/a2"), solo("/x2"))
        val labels = listOf(label("/a1", "Anna"), label("/a2", "Anna"))

        assertTrue(nameSuggestions(listOf(one, two), labels, emptyList()).isEmpty())
    }

    @Test
    fun onlyCrowdedEvidenceNeedsAMajorityOfAnnasPhotographs() {
        // No unambiguous face: one shared photograph of three labelled is not enough …
        val thin = group("thin", crowd("/a1"), solo("/x1"))
        val threeLabelled = listOf("/a1", "/a2", "/a3").map { label(it, "Anna") }
        assertTrue(nameSuggestions(listOf(thin), threeLabelled, emptyList()).isEmpty())
        // … and ONE shared photograph is never enough, even of one labelled …
        assertTrue(nameSuggestions(listOf(thin), listOf(label("/a1", "Anna")), emptyList()).isEmpty())
        // … while two of three is a strict majority.
        val thick = group("thick", crowd("/a1"), crowd("/a2"), solo("/x1"))
        val crowded = nameSuggestions(listOf(thick), threeLabelled, emptyList()).getValue("thick")
        assertEquals("Anna", crowded.name)
        // ⚠⚠ Crowded-only evidence is never confident: asked, not folded.
        assertEquals(false, crowded.confident)
    }

    @Test
    fun aGroupThatIsTwoNamedPeopleIsAskedNothing() {
        // Its faces are Anna's on some photographs and Bob's on others: the grouping merged them.
        val merged = group("m", solo("/a1"), solo("/b1"), solo("/x1"))
        val labels = listOf(label("/a1", "Anna"), label("/b1", "Bob"))

        assertTrue(nameSuggestions(listOf(merged), labels, emptyList()).isEmpty())
    }

    @Test
    fun aGroupThatCrowdedEvidenceMakesTwoPeopleIsAskedNothing() {
        // No unambiguous face anywhere, so only "the group IS two names" can stop it.
        val merged = group("m", crowd("/a1"), crowd("/a2"), crowd("/b1"), crowd("/b2"), solo("/x1"))
        val labels = listOf("/a1", "/a2").map { label(it, "Anna") } + listOf("/b1", "/b2").map { label(it, "Bob") }

        assertTrue(nameSuggestions(listOf(merged), labels, emptyList()).isEmpty())
    }

    @Test
    fun aGroupWithBobsOwnFaceIsNotAskedAboutAnnaEvenWhenAnnaWinsIt() {
        // Anna's two solo photographs make this group hers; Bob's solo photograph is in it too and
        // Bob's best group is elsewhere — still a conflict, because this group holds Bob's face.
        val merged = group("m", solo("/a1"), solo("/a2"), solo("/b1"), solo("/x1"))
        val bob = group("bob", solo("/b2"), solo("/b3"))
        val labels = listOf(label("/a1", "Anna"), label("/a2", "Anna")) +
            listOf("/b1", "/b2", "/b3").map { label(it, "Bob") }

        assertNull(nameSuggestions(listOf(merged, bob), labels, emptyList())["m"])
    }

    @Test
    fun aFullyNamedGroupFoldsAndIsAskedNothing() {
        val anna = group("g1", solo("/a1"), solo("/a2"))
        val labels = listOf(label("/a1", "Anna"), label("/a2", "Anna"))

        assertTrue(nameSuggestions(listOf(anna), labels, emptyList()).isEmpty())
        // ⚠ And `peopleFrom` folds it, unchanged by this feature.
        assertEquals(listOf("Anna"), peopleFrom(listOf(anna), labels).map { it.name })
    }

    @Test
    fun aRejectedFaceSuppressesTheQuestionAcrossARecluster() {
        val before = group("/x1#0", solo("/a1"), solo("/x1"), solo("/x2"))
        val labels = listOf(label("/a1", "Anna"))
        val rejections = listOf(FaceRejection("/x1", "Anna", left))
        assertTrue(nameSuggestions(listOf(before), labels, rejections).isEmpty())

        // A re-index renumbers the group and nudges the box: the answer still finds the face.
        val nudged = FaceRegion(left.left + 0.02f, left.top, left.right + 0.02f, left.bottom)
        val after = group("/a1#0", solo("/a1"), GroupFace("/x1", 3, nudged, 1), solo("/x3"))
        assertTrue(nameSuggestions(listOf(after), labels, rejections).isEmpty())

        // ⚠ Scoped to the NAME: "not Anna" does not answer "is this Bob?".
        val bobLabels = listOf(label("/a1", "Bob"))
        assertEquals("Bob", nameSuggestions(listOf(after), bobLabels, rejections)["/a1#0"]?.name)
    }

    @Test
    fun aRejectionOfAnotherFaceInTheSamePhotographDoesNotSuppress() {
        val anna = group("g", solo("/a1"), crowd("/x1", left))
        val labels = listOf(label("/a1", "Anna"))
        val elsewhere = listOf(FaceRejection("/x1", "Anna", right))

        assertEquals("Anna", nameSuggestions(listOf(anna), labels, elsewhere)["g"]?.name)
    }

    @Test
    fun aGroupWithoutFaceDetailIsNeverAsked() {
        val bare = FaceGroup("g", listOf("/a1", "/x1"))
        assertTrue(nameSuggestions(listOf(bare), listOf(label("/a1", "Anna")), emptyList()).isEmpty())
    }

    @Test
    fun aNoRecordsABoundedSpreadOfFaces() {
        val many = (1..200).map { solo("/x$it") } + solo("/a1")
        val suggestion = nameSuggestions(listOf(group("g", *many.toTypedArray())), listOf(label("/a1", "Anna")), emptyList())
            .getValue("g")
        assertEquals(MAX_REJECTED_FACES, suggestion.faces.size)
        assertTrue(suggestion.faces.none { it.path == "/a1" })
    }

    // ---- the view model: the two levels, Yes, No, and what the next read shows ----------------

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(path: String) = Photo(path, 1_000L, isVideo = false, contentId = path)

    /** One group, two of whose three solo photographs are labelled Benedikt — the confident level. */
    private fun confident(): MockPhotosRepository =
        MockPhotosRepository(photos = listOf("/1", "/2", "/3").map(::photo)).apply {
            peopleOutcome = listOf(group("g1", solo("/1"), solo("/2"), solo("/3"))) to PeopleStatus.Available
            label("/1", "Benedikt", capability = faceCapability)
            label("/2", "Benedikt", capability = faceCapability)
        }

    /** Benedikt's solo photographs split across two groups — the grouping is not sure: ask. */
    private fun unsure(): MockPhotosRepository =
        MockPhotosRepository(photos = listOf("/1", "/2", "/3", "/4", "/5").map(::photo)).apply {
            peopleOutcome = listOf(
                group("g1", solo("/1"), solo("/2"), solo("/3")),
                group("g2", solo("/4"), solo("/5")),
            ) to PeopleStatus.Available
            label("/1", "Benedikt", capability = faceCapability)
            label("/2", "Benedikt", capability = faceCapability)
            label("/4", "Benedikt", capability = faceCapability)
        }

    private fun CollectionsViewModel.page(id: String) = state.value.personPage(id, state.value.smartPage(id))

    @Test
    fun aConfidentGroupFoldsOnScreenAndWritesNothing() = vmTest(dispatcher) { vms ->
        val repo = confident()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ One card, Benedikt, holding the union — and no unnamed card beside it.
        val people = vm.state.value.people
        assertEquals(listOf("Benedikt"), people.map { it.title })
        assertEquals(listOf("/1", "/2", "/3"), people.single().photos.map { it.path })
        // ⚠⚠⚠ Display only: no label written, no rejection written, no call at all.
        assertTrue(repo.namedGroups.isEmpty())
        assertTrue(repo.rejections.isEmpty())
        // The open route on the old unnamed card follows the fold.
        assertEquals("Benedikt", vm.state.value.smartAlbumFor("person:g1")?.title)
        // The page says one of them is matched, not named.
        assertEquals(1, vm.page("person:name:Benedikt").unconfirmedCount)
        assertNull(vm.page("person:name:Benedikt").suggestedName)
    }

    @Test
    fun theFoldIsRecomputedAndGoesWithItsGroup() = vmTest(dispatcher) { vms ->
        val repo = confident()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertEquals(3, vm.state.value.people.single().photos.size)

        // The grouping changes: the group is gone. The fold goes with it — nothing kept it.
        repo.peopleOutcome = emptyList<FaceGroup>() to PeopleStatus.Available
        vm.changed()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("/1", "/2"), vm.state.value.people.single().photos.map { it.path })
        assertEquals(0, vm.page("person:name:Benedikt").unconfirmedCount)
    }

    @Test
    fun confirmingAFoldNamesOnlyTheUnnamedPhotos() = vmTest(dispatcher) { vms ->
        val repo = confident()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.acceptSuggestion("person:name:Benedikt")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(listOf("/3") to "Benedikt"), repo.namedGroups)
    }

    @Test
    fun notThemUnfoldsAndIsNotAskedEither() = vmTest(dispatcher) { vms ->
        val repo = confident()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.rejectSuggestion("person:name:Benedikt")
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("/3"), repo.rejections.map { it.path }.toSet())
        // ⚠ No `changed()` here: the No itself must re-read, or the fold stays on screen.

        // ⚠⚠ The user said no: the group is its own unnamed card again, and not asked about.
        assertEquals(listOf("Benedikt", ""), vm.state.value.people.map { it.title })
        assertEquals(listOf("/1", "/2"), vm.state.value.people.first().photos.map { it.path })
        assertNull(vm.page("person:g1").suggestedName)
        assertTrue(repo.namedGroups.isEmpty(), "a No names nobody")
    }

    @Test
    fun anUnsureGroupIsAskedAndYesNamesItThenFolds() = vmTest(dispatcher) { vms ->
        val repo = unsure()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // Asked, not folded: Benedikt's card is his labelled photographs only.
        assertEquals(listOf("/1", "/2", "/4"), vm.state.value.people.single { it.title == "Benedikt" }.photos.map { it.path })
        assertEquals("Benedikt", vm.page("person:g1").suggestedName)
        assertNull(vm.page("person:g2").suggestedName)

        vm.acceptSuggestion("person:g1")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(listOf("/3") to "Benedikt"), repo.namedGroups)
        // The labels a real proxy would now hold: the next read folds the group by its labels.
        repo.label("/3", "Benedikt", capability = faceCapability)
        vm.changed()
        testScheduler.advanceUntilIdle()
        assertNull(vm.state.value.people.firstOrNull { it.id == "person:g1" })
        assertEquals("Benedikt", vm.state.value.smartAlbumFor("person:g1")?.title)
    }

    @Test
    fun noIsRecordedAndNotAskedAgain() = vmTest(dispatcher) { vms ->
        val repo = unsure()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.rejectSuggestion("person:g1")
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("/3"), repo.rejections.map { it.path })
        assertTrue(repo.rejections.all { it.name == "Benedikt" })
        vm.changed()
        testScheduler.advanceUntilIdle()
        assertNull(vm.page("person:g1").suggestedName)
    }

    @Test
    fun aRejectionFromAnotherDeviceIsNotSeenHere() = vmTest(dispatcher) { vms ->
        // ⚠ D2: a "No" is device-local. This device has none recorded, so it still asks — the
        // other device's answer never reached it, by design.
        val repo = unsure()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertEquals("Benedikt", vm.page("person:g1").suggestedName)
    }

    @Test
    fun aNoThatCouldNotBeRecordedKeepsTheQuestionAndSaysSo() = vmTest(dispatcher) { vms ->
        val repo = unsure().apply { rejectAccepted = false }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.rejectSuggestion("person:g1")
        testScheduler.advanceUntilIdle()

        assertEquals("Benedikt", vm.page("person:g1").suggestedName)
        assertTrue(vm.page("person:g1").suggestionRejectFailed)
    }

    @Test
    fun noRejectionsReadMeansNoFoldsAndNoQuestions() = vmTest(dispatcher) { vms ->
        // ⚠⚠ An unreadable "No" list must never bring an answered question — or fold — back.
        val repo = confident().apply { rejectionsReadable = false }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Benedikt", ""), vm.state.value.people.map { it.title })
        assertTrue(vm.state.value.nameSuggestions.isEmpty())
    }
}
