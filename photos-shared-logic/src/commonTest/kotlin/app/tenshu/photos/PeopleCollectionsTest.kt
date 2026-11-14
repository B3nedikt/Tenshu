package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import kotlin.test.assertNull
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.edit.Redaction
import app.tenshu.photos.edit.NormPoint
import app.tenshu.photos.edit.MarkupEdit
import app.tenshu.photos.edit.EditRecipe
import app.tenshu.photos.model.FaceGroup
import kotlin.test.assertNotNull
import app.tenshu.photos.model.FaceRegion
import app.tenshu.photos.model.GroupFace
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The People section of the Collections tab (IN-6 F3) — the Things section's sibling, one asset
 * class more sensitive.
 *
 * ⚠⚠ **Every one of these passes today against zero real faces**, exactly as `LabelCollectionsTest`
 * says of Things, and for one more reason: no checkpoint is licensed (§10 Q1) *and* no threshold is
 * calibrated, so `faceGroups` refuses on every device. These tests are what makes that a waiting
 * consumer rather than an unverified one.
 */
class PeopleCollectionsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(id: String) = Photo("/$id.jpg", 1_000L, isVideo = false, contentId = id)

    private val library = listOf(photo("a"), photo("b"), photo("c"))

    private fun repoWith(vararg groups: FaceGroup) = MockPhotosRepository(photos = library).apply {
        peopleOutcome = groups.toList() to PeopleStatus.Available
    }

    @Test
    fun faceGroupsBecomeAPeopleSectionWhenTheyExist() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        val people = vm.state.value.people
        assertEquals(1, people.size)
        assertEquals("person:g1", people.single().id)
        assertEquals("2 photos", people.single().subtitle)
        // ⚠⚠ Blank while NOBODY HAS NAMED THEM — not blank as a placeholder. A later
        // change that invents "Person 1" would put a label we made up where the user's own word
        // belongs (IN-D5), and this is the test that should stop it.
        assertEquals("", people.single().title)
    }

    @Test
    fun aNamedPersonShowsTheirName() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ The join that makes the whole naming flow visible, and its absence is
        // what shipped past review: the name saved, synced and became searchable while the card
        // the user had just named stayed blank on every device. A group and a name meet ONLY here.
        assertEquals("Anna", vm.state.value.people.single().title)
        // ⚠⚠ Addressed by NAME, with the cluster kept as an ALIAS. Deriving the primary id
        // from a cluster gave two people who share every photograph the SAME id, which the lazy
        // grid keys on — a duplicate-key crash rather than a wrong card.
        assertEquals("person:name:Anna", vm.state.value.people.single().id)
        assertTrue("person:g1" in vm.state.value.people.single().alternateIds)
    }

    @Test
    fun aPersonTakesTheNameMostOfTheirPhotographsCarry() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        // ⚠⚠ `/c.jpg` shows Anna AND Bob — the couple-in-one-frame case. Taking the
        // first label found would let the other person in that photograph rename this group.
        repo.label("/c.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/c.jpg", "Bob", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ The GROUP goes to Anna, who has three of its photographs to Bob's one. Bob is not
        // erased — he was named on `/c.jpg` and gets his own entry holding exactly that — but he
        // does not take Anna's group with him, which is the whole point of mutual-best matching.
        val anna = vm.state.value.people.single { it.title == "Anna" }
        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), anna.photos.map { it.path }.sorted())
        val bob = vm.state.value.people.single { it.title == "Bob" }
        assertEquals(listOf("/c.jpg"), bob.photos.map { it.path })
    }

    @Test
    fun aThingLabelNeverBecomesAPersonsName() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.label("/a.jpg", "dog", capability = InferenceCapability.CONTENT_TAGGING.id)
        repo.label("/b.jpg", "dog", capability = InferenceCapability.CONTENT_TAGGING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠ The mirror of `aPersonsNameIsNotAThing`: the capability filter has to hold in both
        // directions, or a photograph of someone with a dog names them "Dog".
        assertEquals("", vm.state.value.people.single().title)
    }

    @Test
    fun theRefusalCanBeDismissed() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.namingOutcome = NamingOutcome.NoConsent
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()
        assertEquals(NamingOutcome.NoConsent, vm.state.value.nameFailure)

        vm.dismissNameRefusal()

        assertEquals(null, vm.state.value.nameFailure)
    }

    @Test
    fun peopleLeadTheDerivedSections() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.label("/a.jpg", "dog")
        repo.label("/b.jpg", "dog")
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ `Destination.Smart` resolves against `smartAlbums`, so a section that renders but is
        // missing from this list opens an empty screen. Seeded WITH a Things collection so the
        // assertion pins the order rather than passing on an empty list.
        assertEquals("person:g1", vm.state.value.smartAlbums.first().id)
        assertTrue(vm.state.value.smartAlbums.any { it.id == "label:dog" })
    }

    @Test
    fun aPersonCarriesTheirPhotos_soOpeningOneCostsNothing() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("/a.jpg", "/b.jpg"),
            vm.state.value.people.single().photos.map { it.path },
        )
    }

    @Test
    fun onePhotographCanBelongToTwoPeople() = vmTest(dispatcher) { vms ->
        // The couple-in-one-frame case: `/b.jpg` shows both.
        val repo = repoWith(
            FaceGroup("g1", listOf("/a.jpg", "/b.jpg")),
            FaceGroup("g2", listOf("/b.jpg", "/c.jpg")),
        )
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ Both keep it. A join that keyed on path — the obvious way to write this — would give
        // the photograph to whichever group was processed last, and the other person loses a
        // photograph they are in without anything reporting an error.
        val people = vm.state.value.people
        assertEquals(2, people.size)
        assertTrue(people.all { album -> album.photos.any { it.path == "/b.jpg" } })
    }

    @Test
    fun aRefusalLeavesNoSectionAndSaysWhy() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = emptyList<FaceGroup>() to
                PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.UNCALIBRATED)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ The two halves are the whole point. No section, so the user is never shown an empty
        // People grid — AND the reason survives, so this is distinguishable from a library with
        // nobody in it by anyone reading the state.
        assertTrue(vm.state.value.people.isEmpty())
        assertEquals(
            PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.UNCALIBRATED),
            vm.state.value.peopleStatus,
        )
        assertTrue(
            vm.state.value.placesAndTimes.isNotEmpty(),
            "the sections that need no model still work",
        )
    }

    @Test
    fun aGroupWhosePhotographsHaveAllGoneIsDropped() = vmTest(dispatcher) { vms ->
        val repo = repoWith(
            FaceGroup("g1", listOf("/a.jpg")),
            // Trashed or moved since the index pass wrote the template.
            FaceGroup("gone", listOf("/deleted.jpg")),
        )
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠ A card with no cover and no photographs opens an empty screen; dropping it is the
        // honest rendering of "this person's photographs are gone".
        assertEquals(listOf("person:g1"), vm.state.value.people.map { it.id })
    }

    // ── naming (F3b) ─────────────────────────────────────────────────────────────────────────

    @Test
    fun namingSendsThePhotographsTheUserWasLookingAt() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ The PATHS travel, never the group id. An id is rebuilt per call and an index pass can
        // renumber it, so a write that re-resolved it could name a different person — silently, and
        // in synced data.
        assertEquals(listOf(listOf("/a.jpg", "/b.jpg") to "Anna"), repo.namedGroups)
    }

    @Test
    fun aRefusedNameIsReportedRatherThanLost() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.namingOutcome = NamingOutcome.NoConsent
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ Without this the sheet closes, the grid refreshes, and the person is simply still
        // unnamed — which a user reads as a missed tap. Consent withdrawn while a naming sheet is
        // open is a real window, and the one that produces exactly this.
        assertEquals(NamingOutcome.NoConsent, vm.state.value.nameFailure)
    }

    @Test
    fun aBlankNameIsADismissedSheetRatherThanAFailure() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "   ")
        testScheduler.advanceUntilIdle()

        assertTrue(repo.namedGroups.isEmpty(), "nothing was written")
        assertEquals(null, vm.state.value.nameFailure, "and nothing was reported as refused")
    }

    @Test
    fun aPersonsNameIsNotAThing() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(photos = library)
        // Exactly what naming a person produces: `MediaLabel` rows under FACE_CLUSTERING.
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/a.jpg", "dog", capability = InferenceCapability.CONTENT_TAGGING.id)
        repo.label("/b.jpg", "dog", capability = InferenceCapability.CONTENT_TAGGING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ The cross-feature bug this test exists for. A name is a `MediaLabel` exactly like a
        // tag, so without `THING_CAPABILITIES`' allowlist naming Anna would mint a **Things card
        // called "Anna"** beside "Dog", holding the same photographs as her People card under a
        // heading that means "what a model saw".
        assertEquals(listOf("Dog"), vm.state.value.things.map { it.title })
    }

    @Test
    fun oneSharedPhotographCannotNameSomeoneElsesGroup() = vmTest(dispatcher) { vms ->
        // Anna is named. `/b.jpg` shows Anna AND Bob, so it carries Anna's label — and it is also
        // a member of Bob's much larger group.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("anna", listOf("/a.jpg", "/b.jpg")),
                FaceGroup("bob", listOf("/b.jpg", "/c.jpg")),
            ) to PeopleStatus.Available
        }
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ Bob must stay UNNAMED. The reported defect: a per-GROUP "most common label" gives
        // Bob's group one `Anna` vote and no competing votes, so it takes the title "Anna" — and
        // because naming is hidden once a title is non-blank, **Bob could never be named**.
        //
        // ⚠ Honest about what this test is: a **regression pin, not a guard**. Measured by
        // mutation — deleting the mutual-best check does NOT fail it, because the current shape
        // makes the defect impossible a step earlier (a name claims at most ONE group, so it
        // cannot title two). The check itself is pinned by
        // `aPersonTakesTheNameMostOfTheirPhotographsCarry`, where dropping it bleeds one person's
        // photographs into another's card. This survives to catch a rewrite back to per-group
        // voting.
        val bob = vm.state.value.people.single { it.photos.any { p -> p.path == "/c.jpg" } }
        assertEquals("", bob.title)
        val anna = vm.state.value.people.single { it.title == "Anna" }
        assertTrue(anna.photos.any { it.path == "/a.jpg" })
    }

    @Test
    fun aNamedPersonAppearsWithNoLocalFaceGroupsAtAll() = vmTest(dispatcher) { vms ->
        // A receiving device: the labels synced, but no model has ever run here.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = emptyList<FaceGroup>() to PeopleStatus.Available
        }
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ This is what IN-D5 is FOR. Templates never sync and a laptop may have
        // no face backend at all; the durable artifact is the name. Building People only inside
        // `faceGroups` made the synced half invisible exactly where it was supposed to pay off.
        val anna = vm.state.value.people.single()
        assertEquals("Anna", anna.title)
        assertEquals(listOf("/a.jpg", "/b.jpg"), anna.photos.map { it.path }.sorted())
    }

    @Test
    fun aNamedPersonIsExactlyTheirLabelledPhotographs() = vmTest(dispatcher) { vms ->
        // Anna was named when her group held two photographs; a third has joined since.
        val repo = repoWith(FaceGroup("anna", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ **The deliberate loss, pinned so nobody "fixes" it back.** Her card used to
        // absorb `/c.jpg` because this device clusters it with her. Inferring that cost six review
        // rounds of increasingly baroque overlap rules, each right about its case and wrong about
        // the class, so a named person is now EXACTLY their labelled photographs.
        val anna = vm.state.value.people.single { it.title == "Anna" }
        assertEquals(listOf("/a.jpg", "/b.jpg"), anna.photos.map { it.path }.sorted())

        // ⚠ And `/c.jpg` is not lost — it is under the unnamed cluster it belongs to, where
        // naming adds it. That is the honest rendering: it genuinely carries no name.
        assertTrue(
            vm.state.value.people.any { it.title == "" && it.photos.any { p -> p.path == "/c.jpg" } },
        )
    }

    @Test
    fun aFailureThatIsNotConsentDoesNotClaimTheSwitchIsOff() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.namingOutcome = NamingOutcome.Failed
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ Control not running, or an older Control without this operation — which
        // adding an op does NOT fence — must not tell the user to check a setting already correct.
        assertEquals(NamingOutcome.Failed, vm.state.value.nameFailure)
    }


    @Test
    fun theOpenPersonRouteSurvivesNaming() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        val routeBefore = vm.state.value.people.single().id

        vm.nameGroup(routeBefore, "Anna")
        testScheduler.advanceUntilIdle()
        // The mock stores what was written; make the next read see it, as the realm would.
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        vm.refresh()
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ `Destination.Smart` holds an ID, and naming triggers a refresh. If the
        // entry's id changed on naming, the route the user is LOOKING AT would resolve to nothing
        // and the grid would go blank the instant they named someone — in the primary flow,
        // every time.
        // ⚠ Matched the way the route lookup does — primary OR alias.
        val after = vm.state.value.people.single {
            it.id == routeBefore || routeBefore in it.alternateIds
        }
        assertEquals("Anna", after.title)
    }

    @Test
    fun aSecondClusterOfANamedPersonIsNotShownTwice() = vmTest(dispatcher) { vms ->
        // This device split Anna across two clusters — normal, since clustering is per-device
        // and re-runs. Both hold photographs she has been named in.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("anna1", listOf("/a.jpg", "/b.jpg")),
                FaceGroup("anna2", listOf("/c.jpg")),
            ) to PeopleStatus.Available
        }
        listOf("/a.jpg", "/b.jpg", "/c.jpg").forEach {
            repo.label(it, "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ One card. The second cluster holds only photographs the named card already
        // shows, and naming it "Anna" would not merge it — it would only move which cluster
        // the name claims, leaving the other duplicated again.
        assertEquals(listOf("Anna"), vm.state.value.people.map { it.title })
        assertEquals(
            listOf("/a.jpg", "/b.jpg", "/c.jpg"),
            vm.state.value.people.single().photos.map { it.path }.sorted(),
        )
    }

    @Test
    fun oneSharedPhotographDoesNotHideAnEntireCluster() = vmTest(dispatcher) { vms ->
        // Anna is named; `/b.jpg` shows Anna and Bob; Bob's cluster is mostly his own.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("anna", listOf("/a.jpg", "/b.jpg")),
                FaceGroup("bob", listOf("/b.jpg", "/c.jpg")),
            ) to PeopleStatus.Available
        }
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ The duplicate-suppression needs a MAJORITY, not "any overlap": Bob's cluster
        // shares exactly one photograph with Anna's, and suppressing on overlap alone would hide
        // him entirely — the same defect mutual-best prevents, arriving by another route.
        assertTrue(vm.state.value.people.any { it.title == "" && it.photos.any { p -> p.path == "/c.jpg" } })
    }


    @Test
    fun aSyncedNameWithNoLocalClusterDoesNotSwallowSomeoneElsesGroup() = vmTest(dispatcher) { vms ->
        // A RECEIVING device: Anna's label synced, but no Anna cluster was ever built here. Her one
        // shared photograph sits inside Bob's cluster.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(FaceGroup("bob", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
                .to(PeopleStatus.Available)
        }
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ Mutual-best alone does NOT catch this: with no competing Anna cluster,
        // `bestGroupFor("Anna")` picks Bob's and `bestNameFor(bob)` answers Anna because she is the
        // only candidate. Without a coverage bar, Anna absorbs all three photographs and Bob's card
        // vanishes — on every device that has not indexed faces yet.
        val anna = vm.state.value.people.single { it.title == "Anna" }
        assertEquals(listOf("/a.jpg"), anna.photos.map { it.path })
        assertTrue(
            vm.state.value.people.any { it.title == "" && it.photos.size == 3 },
            "Bob's cluster survives, whole and unnamed",
        )
    }


    @Test
    fun whenTwoNamesBothCoverAClusterOnlyOneAbsorbsItsUnnamedPhotographs() = vmTest(dispatcher) { vms ->
        // An inseparable pair: every photograph the cluster holds shows BOTH of them, so both names
        // clear the coverage bar — and `/c.jpg` is a group-mate nobody has labelled.
        val repo = repoWith(FaceGroup("pair", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        listOf("/a.jpg", "/b.jpg").forEach {
            repo.label(it, "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
            repo.label(it, "Bob", capability = InferenceCapability.FACE_CLUSTERING.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ This is what mutual-best still buys once coverage is in place — measured
        // by mutation, because without this case the coverage bar alone passed every test. Two
        // names can BOTH cover one cluster when its photographs carry both labels; letting both
        // claim it puts the unlabelled group-mate under two people at once, and a photograph of
        // neither would then appear on both cards.
        //
        // ⚠ Which of them absorbs it is an arbitrary tie-break (by name), but it is
        // deterministic, and "one of them" is the property that matters.
        val withUnlabelled = vm.state.value.people.filter { p ->
            p.photos.any { it.path == "/c.jpg" }
        }
        assertEquals(1, withUnlabelled.size, "exactly one person absorbs the unlabelled group-mate")
    }


    @Test
    fun aPartialNamingIsReportedAsPartialAndTheScreenIsReRead() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        repo.namingAcceptsFirst = 1
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ "That name wasn't saved" would be a lie the user acts on: the accepted rows
        // are durable and already on their other devices, so they would retype a name that is half
        // applied and never see the half that is wrong.
        assertEquals(NamingOutcome.PartiallySaved, vm.state.value.nameFailure)
    }

    @Test
    fun aFailedNamingStillReReadsTheLibrary() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
        repo.namingOutcome = NamingOutcome.NoConsent
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        val readsBefore = repo.allPhotosReads

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ The re-read happens on EVERY outcome. Skipping it on failure leaves the
        // screen disagreeing with the realm — and `refresh()` must therefore not clear the
        // verdict, or showing the consequences would erase the explanation.
        assertTrue(repo.allPhotosReads > readsBefore, "the library was re-read")
        assertEquals(NamingOutcome.NoConsent, vm.state.value.nameFailure, "and the verdict survived it")
    }


    @Test
    fun twoOtherPeoplesPhotographsDoNotSuppressAThirdPersonsCluster() = vmTest(dispatcher) { vms ->
        // Bob's cluster holds one Anna photograph, one Carol photograph and one of his own.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(FaceGroup("bob", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
                .to(PeopleStatus.Available)
        }
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Carol", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ Neither name covers a majority, so neither claims the cluster — but the
        // UNION covers two of its three photographs. Measuring suppression against the union
        // deletes Bob's card on the strength of two other people's photographs. "Already on screen
        // under a name" has to mean under *a* name.
        assertTrue(
            vm.state.value.people.any { it.title == "" && it.photos.size == 3 },
            "Bob's cluster survives",
        )
    }

    @Test
    fun anUnfinishedNamingStaysReachable() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        repo.namingAcceptsFirst = 2
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()
        vm.dismissNameRefusal()

        // ⚠⚠ Past the halfway mark the card carries the name, which hides the naming
        // action — so "try again" would have nothing to press and the person would stay
        // permanently half synced. Dismissing the dialog must not take the way back with it.
        assertTrue(
            vm.state.value.namingIncompleteFor.any { it.smartId == "person:g1" },
            "still reachable after the dialog closes",
        )
        assertEquals(null, vm.state.value.nameFailure, "but the dialog itself is gone")
    }

    @Test
    fun aCompletedNamingClearsTheUnfinishedMarker() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        repo.namingAcceptsFirst = 2
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.namingIncompleteFor.any { it.smartId == "person:g1" })

        repo.namingAcceptsFirst = null
        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠ Cleared by a naming that COMPLETES, never by one that merely stops failing.
        assertTrue(vm.state.value.namingIncompleteFor.isEmpty())
    }


    @Test
    fun aClusterWhereMostPhotographsShowTwoPeopleStaysItsOwnPerson() = vmTest(dispatcher) { vms ->
        // A receiving device with no Anna cluster. Bob's cluster: two Anna-and-Bob photographs and
        // one that is only Bob.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(FaceGroup("bob", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
                .to(PeopleStatus.Available)
        }
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        repo.label("/b.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ The case a MAJORITY bar could not survive: Anna covers 2 of 3, so a
        // proportional rule claims Bob's cluster for her, hands her his solo photograph and deletes
        // his card. An exact rule — a group is hers only if EVERY photograph in it is — has
        // nothing to get wrong here.
        val anna = vm.state.value.people.single { it.title == "Anna" }
        assertEquals(listOf("/a.jpg", "/b.jpg"), anna.photos.map { it.path }.sorted())
        assertTrue(
            vm.state.value.people.any { it.title == "" && it.photos.size == 3 },
            "Bob's cluster survives whole",
        )
    }

    @Test
    fun finishingOnePersonsNamingDoesNotRetireAnothersRetry() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("g1", listOf("/a.jpg", "/b.jpg")),
                FaceGroup("g2", listOf("/c.jpg")),
            ) to PeopleStatus.Available
        }
        repo.namingAcceptsFirst = 1
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.namingIncompleteFor.any { it.smartId == "person:g1" })

        // A DIFFERENT person is named, and that one completes.
        repo.namingAcceptsFirst = null
        vm.nameGroup("person:g2", "Bob")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ A global flag would have been cleared here, permanently removing the retry
        // from the person who actually needed it — and offering it, meanwhile, to whichever
        // already-named person happened to be open.
        assertTrue(vm.state.value.namingIncompleteFor.any { it.smartId == "person:g1" })
    }


    @Test
    fun namingASecondClusterKeepsThatClustersRouteAddressable() = vmTest(dispatcher) { vms ->
        // Anna already fully covers cluster "a1". The user opens the OTHER cluster and names it
        // Anna too, which folds it into her.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("a1", listOf("/a.jpg")),
                FaceGroup("a2", listOf("/b.jpg", "/c.jpg")),
            ) to PeopleStatus.Available
        }
        listOf("/a.jpg", "/b.jpg", "/c.jpg").forEach {
            repo.label(it, "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠ Both clusters are fully hers, so only the lowest id can be the primary —
        // and the user was looking at the OTHER one. Without the aliases, `person:a2` resolves to
        // nothing the moment the name takes effect and the grid goes blank on success. That defect
        // has now appeared in three different shapes; the aliases are what close the class.
        val anna = vm.state.value.people.single { it.title == "Anna" }
        assertEquals("person:name:Anna", anna.id)
        assertTrue("person:a1" in anna.alternateIds, "one cluster still addresses her")
        assertTrue("person:a2" in anna.alternateIds, "and so does the other")
    }

    @Test
    fun aNamingFailureIsRememberedAgainstThePersonItBelongsTo() = vmTest(dispatcher) { vms ->
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("g1", listOf("/a.jpg", "/b.jpg")),
                FaceGroup("g2", listOf("/c.jpg")),
            ) to PeopleStatus.Available
        }
        repo.namingOutcome = NamingOutcome.NoConsent
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()

        // ⚠⚠ A naming of many photographs outlives the screen that started it. A global
        // verdict surfaces its dialog on whatever is open by the time it lands — an unrelated
        // person, a year, a place — and sits there until dismissed.
        assertEquals(NamingOutcome.NoConsent, vm.state.value.nameFailure)
        assertEquals("person:g1", vm.state.value.nameFailureFor)
    }


    @Test
    fun twoPeopleInEveryPhotographDoNotShareAnId() = vmTest(dispatcher) { vms ->
        // An inseparable pair: every photograph of every cluster shows both of them, so both names
        // fully cover exactly the same groups.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(
                FaceGroup("g1", listOf("/a.jpg")),
                FaceGroup("g2", listOf("/b.jpg")),
            ) to PeopleStatus.Available
        }
        listOf("/a.jpg", "/b.jpg").forEach {
            repo.label(it, "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
            repo.label(it, "Bob", capability = InferenceCapability.FACE_CLUSTERING.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ A CRASH, not a wrong card: `CollectionsScreen` keys its lazy grid on
        // `SmartAlbum.id`, and a cluster-derived id gives these two the same one. The primary id
        // comes from the name for exactly this reason — two people sharing a name are already
        // one entry here, so a name is unique across the list by construction.
        val ids = vm.state.value.people.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "every person has their own id")
        // ⚠ And across the whole list the grid keys on, not just People — `smartAlbums` is what
        // `CollectionsScreen` renders, and Years and Places are in it too.
        val all = vm.state.value.smartAlbums.map { it.id }
        assertEquals(all.size, all.distinct().size, "no two sections share a lazy-grid key")
    }


    @Test
    fun aClusterAddressesOnlyOneOfTwoPeopleWhoShareIt() = vmTest(dispatcher) { vms ->
        // Anna and Bob appear together in every photograph, so both fully cover every cluster.
        val repo = MockPhotosRepository(photos = library).apply {
            peopleOutcome = listOf(FaceGroup("g1", listOf("/a.jpg", "/b.jpg")))
                .to(PeopleStatus.Available)
        }
        listOf("/a.jpg", "/b.jpg").forEach {
            repo.label(it, "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
            repo.label(it, "Bob", capability = InferenceCapability.FACE_CLUSTERING.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ Unique primary ids were only half the fix. If BOTH list `person:g1` as
        // an alias, the route lookup takes the first match — so an existing Bob route silently
        // opens Anna. A cluster may address exactly one person.
        val owners = vm.state.value.people.filter { "person:g1" in it.alternateIds }
        assertEquals(1, owners.size, "exactly one person answers to the cluster's id")

        val everyAddress = vm.state.value.people.flatMap { listOf(it.id) + it.alternateIds }
        assertEquals(
            everyAddress.size,
            everyAddress.distinct().size,
            "and no address is shared between two people",
        )
    }


    @Test
    fun anInterruptedNamingRemembersWHICHNameWasBeingWritten() = vmTest(dispatcher) { vms ->
        // Anna's card exists. The user adds BOB from it, and the write stops part way.
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg", "/b.jpg", "/c.jpg")))
        listOf("/a.jpg", "/b.jpg", "/c.jpg").forEach {
            repo.label(it, "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        val annasCard = vm.state.value.people.single { it.title == "Anna" }.id

        repo.namingAcceptsFirst = 1
        vm.nameGroup(annasCard, "Bob")
        testScheduler.advanceUntilIdle()

        // ⚠⚠⚠ The NAME, not just the card. "Add another person" means the interrupted
        // write can be for someone the card is not titled after — so a retry that pre-filled the
        // card's title would rewrite ANNA, report success and clear the retry, while Bob stays
        // exactly as broken and nothing says so.
        assertEquals(
            "Bob",
            vm.state.value.namingIncompleteFor.single { it.smartId == annasCard }.name,
        )
    }

    // ---- IN-6 F6a: the header's face ------------------------------------------------------------

    private fun faceAt(path: String, size: Float, facesInPhoto: Int = 1, index: Int = 0) = GroupFace(
        path = path,
        faceIndex = index,
        region = FaceRegion(0.1f, 0.1f, 0.1f + size, 0.1f + size),
        facesInPhoto = facesInPhoto,
    )

    /** §7's cover rule, as far as today's data reaches: the LARGEST face, not the first photograph. */
    @Test
    fun anUnnamedGroupsCoverIsItsLargestFace() = vmTest(dispatcher) { vms ->
        val repo = repoWith(
            FaceGroup(
                "g1", listOf("/a.jpg", "/b.jpg"),
                faces = listOf(faceAt("/a.jpg", 0.1f), faceAt("/b.jpg", 0.4f)),
            ),
        )
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        val person = vm.state.value.people.single()
        assertEquals("/b.jpg", person.coverFace?.path)
        assertEquals("/b.jpg", person.coverPath, "the card's cover follows the face, not photo order")
    }

    /**
     * ⚠⚠ A named person's cover comes only from a cluster the name ENTIRELY covers. A labelled
     * photograph with two faces cannot say which face is hers (§6a), so it must never supply one.
     */
    @Test
    fun aNamedPersonsCoverComesOnlyFromAClusterTheNameFullyCovers() = vmTest(dispatcher) { vms ->
        val repo = repoWith(
            // Anna's own cluster: small faces.
            FaceGroup("g1", listOf("/a.jpg"), faces = listOf(faceAt("/a.jpg", 0.1f))),
            // A cluster holding /a.jpg AND an unlabelled photo: NOT entirely Anna's, big faces.
            FaceGroup(
                "g2", listOf("/a.jpg", "/c.jpg"),
                faces = listOf(faceAt("/a.jpg", 0.5f, facesInPhoto = 2, index = 1), faceAt("/c.jpg", 0.5f)),
            ),
        )
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        val anna = vm.state.value.people.first { it.title == "Anna" }
        val cover = assertNotNull(anna.coverFace)
        assertEquals(0.1f, cover.region.right - cover.region.left, 0.0001f)
    }

    // ---- photos-editing.md §12.13: an edited photo never supplies an avatar ----------------------

    /** A recipe that blacks out a box over the face — Markup → Redact. */
    private val redactTheFace = EditRecipe(
        markup = MarkupEdit(
            listOf(Redaction(1, listOf(NormPoint(0.1f, 0.1f), NormPoint(0.5f, 0.1f), NormPoint(0.5f, 0.5f), NormPoint(0.1f, 0.5f)))),
        ),
    )

    /** Every path a People surface could cut or draw a face from: the card, the header, iOS's cover. */
    private fun SmartAlbum.avatarPaths() = listOfNotNull(coverFace?.path, coverPath)

    /**
     * ⚠⚠ The redaction gap (§12.13): the face box is a fraction of the ORIGINAL, so an avatar cut
     * from an edited photo is cut from its original — the face the user blacked out. The edited
     * photo has the LARGEST face, so the old rule would pick it.
     */
    @Test
    fun aPhotoWithARedactingEdit_neverSuppliesAnAvatarCutFromItsOriginal() = vmTest(dispatcher) { vms ->
        val repo = repoWith(
            FaceGroup(
                "g1", listOf("/a.jpg", "/b.jpg"),
                faces = listOf(faceAt("/a.jpg", 0.4f), faceAt("/b.jpg", 0.1f)),
            ),
        )
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertEquals("/a.jpg", vm.state.value.people.single().coverFace?.path, "precondition: the largest face covers")

        val edited = assertNotNull(repo.saveEdit(photo("a"), redactTheFace, byteArrayOf(1)))
        assertTrue(assertNotNull(repo.photoEdit("a")).redacted, "precondition: the edit redacts")
        vm.refresh()
        testScheduler.advanceUntilIdle()

        val person = vm.state.value.people.single()
        assertEquals("/b.jpg", person.coverFace?.path, "the next-largest unedited face covers instead")
        assertTrue("/a.jpg" !in person.avatarPaths(), "no avatar is cut from the redacted original")
        // ⚠ Membership is untouched: the edited photo is still this person's, drawn by its rendition.
        assertEquals(edited.renditionPath, person.photos.single { it.contentId == "a" }.previewPath)
    }

    /** With no unedited face, the person has no face crop at all — the card draws the rendition, whole. */
    @Test
    fun aPersonWhoseOnlyFaceIsEdited_hasNoFaceCrop_andCoversWithTheRendition() = vmTest(dispatcher) { vms ->
        val repo = repoWith(FaceGroup("g1", listOf("/a.jpg"), faces = listOf(faceAt("/a.jpg", 0.4f))))
        repo.label("/a.jpg", "Anna", capability = InferenceCapability.FACE_CLUSTERING.id)
        val edited = assertNotNull(repo.saveEdit(photo("a"), redactTheFace, byteArrayOf(1)))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        val anna = vm.state.value.people.single()
        assertNull(anna.coverFace, "no face crop: the box belongs to the original")
        assertEquals(edited.renditionPath, anna.coverPath)
    }

    /** ⚠ Derived, never stored: a revert makes the face a candidate again; a re-edit keeps it out. */
    @Test
    fun aRevertedPhotoCoversAgain_andAReEditedOneStaysOut() = vmTest(dispatcher) { vms ->
        val repo = repoWith(
            FaceGroup(
                "g1", listOf("/a.jpg", "/b.jpg"),
                faces = listOf(faceAt("/a.jpg", 0.4f), faceAt("/b.jpg", 0.1f)),
            ),
        )
        val edited = assertNotNull(repo.saveEdit(photo("a"), redactTheFace, byteArrayOf(1)))
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertEquals("/b.jpg", vm.state.value.people.single().coverFace?.path)

        assertNotNull(repo.revertEdit(edited))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("/a.jpg", vm.state.value.people.single().coverFace?.path, "reverted: the original is the photo again")

        assertNotNull(repo.saveEdit(photo("a"), redactTheFace, byteArrayOf(2)))
        vm.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("/b.jpg", vm.state.value.people.single().coverFace?.path, "re-edited: out again")
    }
}
