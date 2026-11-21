package app.tenshu.photos

import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.FaceGroup
import app.tenshu.photos.model.PeopleStatus
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.collections.CollectionsLayout
import app.tenshu.photos.ui.screens.collections.CollectionsViewModel
import app.tenshu.photos.ui.screens.collections.PEOPLE_AND_PETS_TITLE
import app.tenshu.photos.ui.screens.collections.peopleTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.inference.FaceGroupsUnavailable
import org.tenshu.inference.InferenceCapability
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pets in Collections (`photos-pets.md` PT-3d, PT-D3): one *People & pets* row while pet recognition is
 * on, named first — and ⚠⚠ a pet card names a PET, never a person.
 */
class PetCollectionsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun photo(id: String) = Photo("/$id.jpg", 1_000L, isVideo = false, contentId = id)

    private val library = listOf(photo("a"), photo("b"), photo("c"), photo("d"))

    private fun repo() = MockPhotosRepository(photos = library).apply {
        peopleOutcome = listOf(FaceGroup("g1", listOf("/a.jpg", "/b.jpg"))) to PeopleStatus.Available
        petsOutcome = listOf(FaceGroup("p1", listOf("/c.jpg", "/d.jpg"))) to PeopleStatus.Available
    }

    @Test
    fun petsJoinThePeopleRow_namedFirst_underTheirOwnIds() = vmTest(dispatcher) { vms ->
        val repo = repo().apply {
            label("/c.jpg", "Mochi", capability = InferenceCapability.PET_RECOGNITION.id)
            label("/d.jpg", "Mochi", capability = InferenceCapability.PET_RECOGNITION.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        val row = vm.state.value.people
        assertEquals(listOf("pet:name:Mochi", "person:g1"), row.map { it.id }, "the named pet before the unnamed person")
        assertTrue("pet:p1" in row.first().alternateIds)
        assertTrue(vm.state.value.petsOn)
        assertEquals(PEOPLE_AND_PETS_TITLE, peopleTitle(vm.state.value))
    }

    @Test
    fun namingAPetCardWritesAPetName_neverAPersons() = vmTest(dispatcher) { vms ->
        val repo = repo()
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        vm.nameGroup("pet:p1", "Mochi")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(listOf("/c.jpg", "/d.jpg") to "Mochi"), repo.namedPets)
        assertTrue(repo.namedGroups.isEmpty(), "⚠⚠ a cat named as a person")

        vm.nameGroup("person:g1", "Anna")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(listOf("/a.jpg", "/b.jpg") to "Anna"), repo.namedGroups)
        assertEquals(1, repo.namedPets.size)
    }

    @Test
    fun withPetsOffThePeopleRowIsExactlyAsBefore() = vmTest(dispatcher) { vms ->
        val repo = repo().apply {
            petsOutcome = emptyList<FaceGroup>() to PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.NO_CONSENT)
            // A pet name synced from another device is not drawn where pets are off (PT-D3).
            label("/c.jpg", "Mochi", capability = InferenceCapability.PET_RECOGNITION.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("person:g1"), vm.state.value.people.map { it.id })
        assertFalse(vm.state.value.petsOn)
        assertEquals("People", peopleTitle(vm.state.value))
    }

    /**
     * ⚠⚠ `NO_BACKEND` proves nothing about consent: the proxy asks for the backend BEFORE it checks the
     * switch. A device with no pet models answers it whether pets are on or off, so it must read as off.
     */
    @Test
    fun noPetModelsIsNotPetsOn() = vmTest(dispatcher) { vms ->
        val repo = repo().apply {
            petsOutcome = emptyList<FaceGroup>() to PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.NO_BACKEND)
            label("/c.jpg", "Mochi", capability = InferenceCapability.PET_RECOGNITION.id)
            label("/d.jpg", "Mochi", capability = InferenceCapability.PET_RECOGNITION.id)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.petsOn)
        assertEquals(listOf("person:g1"), vm.state.value.people.map { it.id })

        // NOT_INDEXED comes after the consent check: the switch IS on, the pass has not run yet.
        repo.petsOutcome = emptyList<FaceGroup>() to PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.NOT_INDEXED)
        val indexing = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertTrue(indexing.state.value.petsOn)
    }

    @Test
    fun anOlderControlWithoutPetsLeavesPeopleUntouched() = vmTest(dispatcher) { vms ->
        val repo = repo().apply { petsOutcome = null }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("person:g1"), vm.state.value.people.map { it.id })
        assertFalse(vm.state.value.petsOn)
    }

    @Test
    fun petsWithFacesOffShowTheRow_withoutAskingForTheFaceSwitch() = vmTest(dispatcher) { vms ->
        val repo = repo().apply {
            peopleOutcome = emptyList<FaceGroup>() to PeopleStatus.Unavailable(FaceGroupsUnavailable.Reasons.NO_CONSENT)
        }
        val vm = vms.own(CollectionsViewModel(repo, yearOf = { 2026 }))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("pet:p1"), vm.state.value.people.map { it.id })
        val content = CollectionsLayout.of(vm.state.value, groupingRunsHere = true) as CollectionsLayout.Content
        val row = checkNotNull(content.people)
        assertEquals(PEOPLE_AND_PETS_TITLE, row.title)
        assertEquals(null, row.statusLine, "a user who chose pets and not faces is not asked for faces")
    }
}
